/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

/**
 * Outcome of an admission decision: accept into the queue, or reject with a Retry-After hint.
 *
 * Used by the pure [admit] function and by [decideBackpressure] which composes the thermal-shed
 * check (503) with the queue check (429). The sealed hierarchy is exhaustive so callers are
 * forced to handle all cases at compile time.
 */
sealed interface AdmissionDecision {
  data object Accept : AdmissionDecision
  data class Reject(val retryAfterSeconds: Int) : AdmissionDecision
}

/**
 * Three-way outcome that encodes the ordering between the two backpressure signals:
 *
 *   Shed503 > QueueReject429 > Admit   (heat beats load)
 *
 * Returned by [decideBackpressure], which is the single seam unit-tested for ordering.
 */
sealed interface BackpressureOutcome {
  /** Thermal: return 503 + Retry-After. Takes precedence over everything. */
  data object Shed503 : BackpressureOutcome

  /** Queue full: return 429 + Retry-After. Only fires when the device is not hot. */
  data class QueueReject429(val retryAfterSeconds: Int) : BackpressureOutcome

  /** Admit into the queue (neither shed nor full). */
  data object Admit : BackpressureOutcome
}

// ---------------------------------------------------------------------------
// Capacity constants — tunable without touching the policy functions.
// ---------------------------------------------------------------------------

/** Hard cap for the FIFO admission queue. Kept < MAX_CONNECTIONS (16) so /health and /metrics
 *  stay serviceable and the pool has headroom to return 429 responses even under full load. */
const val QUEUE_CAPACITY = 8

/** Coarse per-request decode estimate used to scale the Retry-After hint (seconds). */
const val DEFAULT_SERVICE_SECONDS = 8

/** Floor for all Retry-After values emitted by admission rejection. */
const val MIN_RETRY_AFTER = 2

/** Ceiling for all Retry-After values emitted by admission rejection (anti-backoff-explosion). */
const val MAX_RETRY_AFTER = 30

// ---------------------------------------------------------------------------
// Pure policy functions — no Context, no Android types, trivially testable.
// ---------------------------------------------------------------------------

/**
 * Pure admission policy for the single-engine FIFO queue.
 *
 * Accepts iff [queuedOrRunning] is strictly below [capacity]. On rejection the Retry-After hint
 * scales linearly with how far over the limit the caller is, clamped to [[MIN_RETRY_AFTER],
 * [MAX_RETRY_AFTER]] to prevent both too-short (stampede) and too-long (client gives up) values.
 *
 * @param queuedOrRunning current in-flight count (queued + running); matches
 *   [RelaisMetrics.inFlight] / `relais_queue_depth`.
 * @param capacity hard cap; use [QUEUE_CAPACITY] at call sites.
 * @param estimatedServiceSeconds per-request time estimate for scaling the hint.
 */
fun admit(
  queuedOrRunning: Int,
  capacity: Int,
  estimatedServiceSeconds: Int = DEFAULT_SERVICE_SECONDS,
): AdmissionDecision =
  if (queuedOrRunning < capacity) {
    AdmissionDecision.Accept
  } else {
    val overage = queuedOrRunning - capacity + 1 // 1 at capacity, grows with backlog
    AdmissionDecision.Reject(
      retryAfterSeconds = (estimatedServiceSeconds * overage).coerceIn(MIN_RETRY_AFTER, MAX_RETRY_AFTER)
    )
  }

/**
 * Pure, unit-tested mirror of the call-site ordering between the two backpressure signals.
 *
 * **Ordering:** Shed503 > QueueReject429 > Admit (heat beats load). The HTTP call sites enforce
 * this same order via sequential guards (`shedIfHot` then `rejectIfQueueFull`) rather than
 * calling this function directly — [decideBackpressure] is the testable specification that those
 * guards are verified against, not a shared runtime helper.
 *
 * @param shed true iff [ThermalGovernor.shouldShed] returned true for this request.
 * @param queuedOrRunning current in-flight count (see [admit]).
 * @param capacity hard cap (see [admit]).
 */
fun decideBackpressure(
  shed: Boolean,
  queuedOrRunning: Int,
  capacity: Int,
): BackpressureOutcome {
  if (shed) return BackpressureOutcome.Shed503
  return when (val decision = admit(queuedOrRunning, capacity)) {
    is AdmissionDecision.Accept -> BackpressureOutcome.Admit
    is AdmissionDecision.Reject -> BackpressureOutcome.QueueReject429(decision.retryAfterSeconds)
  }
}
