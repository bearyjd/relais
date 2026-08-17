/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.chat

import cc.grepon.relais.data.ReportSendState

/**
 * Why a report send did not land (#273), at the granularity a retry policy actually needs.
 *
 * [ContentReportDelivery.send] used to return a plain `Boolean`, which is why this file exists: a
 * 429, a 500, a refused connection and a 400 were all `false`. Retrying on that boolean would spend
 * the Worker's whole per-caller budget (10 requests per 60 minutes — `RATE_LIMIT`/
 * `RATE_WINDOW_SECONDS` in `report-worker/src/index.ts`) hammering a report the Worker is actively
 * throttling, and the operator's NEXT genuine report would then be the one rejected. The retry is
 * only safe once the outcomes are told apart.
 */
enum class ReportSendResult {
  /** The Worker answered 2xx. Terminal, and the only success. */
  SENT,

  /** HTTP 429 — the per-caller budget is spent. Retryable, but only after the window rolls over. */
  RATE_LIMITED,

  /** 5xx, 408, or no answer at all (IO/timeout). Retryable with backoff. */
  TRANSIENT,

  /**
   * Any other 4xx — a malformed or oversize body, or a rejected schema. Re-sending the identical
   * payload cannot succeed, so this is terminal: retrying would be a guaranteed-useless request
   * against a budget the operator's real reports need.
   */
  PERMANENT,
}

/**
 * Map an HTTP status onto a [ReportSendResult].
 *
 * Pure, and separate from the socket work in [ContentReportDelivery.send], so every status the
 * Worker can actually return (`reply()` in `report-worker/src/index.ts`: 403, 404, 405, 413, 429,
 * plus the 200 success) is unit-testable without a network stack.
 *
 * 408 joins the 5xx band: a server-side request timeout is a "try again", not a bad payload.
 */
internal fun classifySendResponse(httpCode: Int): ReportSendResult =
  when {
    httpCode in 200..299 -> ReportSendResult.SENT
    httpCode == 429 -> ReportSendResult.RATE_LIMITED
    httpCode == 408 || httpCode in 500..599 -> ReportSendResult.TRANSIENT
    else -> ReportSendResult.PERMANENT
  }

/**
 * What to write on the report row after an attempt, and when (if ever) to try again.
 *
 * [retryDelayMs] `null` means "do not schedule anything" — the row has reached a terminal state and
 * [state] is either [ReportSendState.SENT] or [ReportSendState.FAILED]. A terminal FAILED row is
 * still manually re-sendable from `CONFIGURE › REPORTED OUTPUT`; giving up automatically is not the
 * same as giving up.
 */
data class SendDisposition(val state: String, val attempts: Int, val retryDelayMs: Long?)

/** Give up automatic retries after this many *counted* attempts. Manual SEND is always still there. */
const val MAX_SEND_ATTEMPTS = 5

/** First backoff step; doubles per counted attempt up to [MAX_RETRY_DELAY_MS]. */
internal const val BASE_RETRY_DELAY_MS = 60_000L

/** Backoff ceiling — past this, waiting longer buys nothing a manual SEND wouldn't. */
internal const val MAX_RETRY_DELAY_MS = 30L * 60_000L

/**
 * Wait out the Worker's whole rate-limit window plus a margin, rather than backing off from it.
 *
 * The window is a fixed 60-minute KV TTL, so a retry inside it is certain to 429 again; the margin
 * covers clock skew between the device and the edge.
 */
internal const val RATE_LIMIT_COOLDOWN_MS = 65L * 60_000L

/**
 * The retry decision, as a pure function of the attempt's result and the attempts already counted.
 *
 * A [ReportSendResult.RATE_LIMITED] attempt deliberately does **not** consume an attempt: the report
 * was never actually evaluated, only refused at the door. Counting it would let a busy hour exhaust
 * [MAX_SEND_ATTEMPTS] and permanently fail a report the Worker would have accepted an hour later —
 * which is the exact "unrecoverable send" #273 exists to fix, reintroduced by its own retry budget.
 *
 * Pure JVM (no Context, no WorkManager, no Room) so the whole matrix is unit-tested in isolation —
 * mirrors [shouldUnloadIdleEngine]'s convention in RelaisIdleTtl.kt.
 */
fun dispositionFor(result: ReportSendResult, attemptsSoFar: Int): SendDisposition =
  when (result) {
    ReportSendResult.SENT -> SendDisposition(ReportSendState.SENT, attemptsSoFar, null)
    ReportSendResult.PERMANENT -> SendDisposition(ReportSendState.FAILED, attemptsSoFar, null)
    ReportSendResult.RATE_LIMITED ->
      SendDisposition(ReportSendState.PENDING, attemptsSoFar, RATE_LIMIT_COOLDOWN_MS)
    ReportSendResult.TRANSIENT -> {
      val attempts = attemptsSoFar + 1
      if (attempts >= MAX_SEND_ATTEMPTS) SendDisposition(ReportSendState.FAILED, attempts, null)
      else SendDisposition(ReportSendState.PENDING, attempts, backoffFor(attempts))
    }
  }

/**
 * The operator-facing line describing a report's delivery state, or **null** when there is nothing to
 * say (the operator never opted in, so no send status exists to report).
 *
 * Pure and separate from `ContentReportsActivity`'s composables for the reason [labelForReasonId]'s
 * sibling copy is not: a string built inside a composable is unreachable from a JVM test, and this one
 * makes a claim about data egress that must not be able to drift silently — the [ReportSendState.NONE]
 * branch returning null is what keeps the review screen from implying a report was transmitted when it
 * never was. Mirrors `controlPanelDetailLine` in RelaisControlPanelState.kt.
 */
internal fun sendStatusText(sendState: String, attempts: Int): String? =
  when (sendState) {
    ReportSendState.SENT -> "sent to the developer"
    ReportSendState.PENDING ->
      if (attempts == 0) "queued to send to the developer"
      else "sending to the developer — $attempts failed ${plural(attempts, "attempt")} so far"
    ReportSendState.FAILED ->
      "could not send to the developer after $attempts ${plural(attempts, "attempt")} — SEND to retry"
    else -> null
  }

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

/**
 * Exponential backoff for the [attempts]'th counted attempt, capped at [MAX_RETRY_DELAY_MS].
 *
 * Shifts on a coerced exponent rather than computing `BASE shl attempts` directly: with
 * [MAX_SEND_ATTEMPTS] raised past ~26 the unclamped shift would overflow `Long` and yield a
 * *negative* delay, which WorkManager would treat as "run immediately" — turning a backoff into a
 * tight loop against the rate limiter. Clamping the exponent, not just the result, makes that
 * unreachable regardless of what the constant is later set to.
 */
internal fun backoffFor(attempts: Int): Long {
  val steps = (attempts - 1).coerceIn(0, 20)
  return (BASE_RETRY_DELAY_MS shl steps).coerceAtMost(MAX_RETRY_DELAY_MS)
}
