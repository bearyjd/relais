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

package cc.grepon.relais.automation

import android.content.Intent

/**
 * Pure ABI for the Tasker/Automate intent surface (#8): action + extra-key constants, a wire-shaped
 * [AbiRequest], a lambda-driven [parseRequest] (so it unit-tests with no Android), and a
 * [buildResultIntent] that constructs the RESULT intent.
 *
 * SECURITY: [buildResultIntent] NEVER puts the caller's token (or any secret) into the result —
 * the result is delivered via activity-result and, when `result_package` is set, a package-targeted
 * broadcast, so any echoed secret would leak the node's bearer key to whoever captured it.
 */
object RelaisIntentAbi {

  /** Input action: an automation app launches the exported activity with this to request inference. */
  const val ACTION_INFER = "cc.grepon.relais.action.INFER"

  /** Result action: the broadcast (and activity-result) action carrying the inference outcome. */
  const val ACTION_INFER_RESULT = "cc.grepon.relais.action.INFER_RESULT"

  // ---- input extras ----
  const val EXTRA_PROMPT = "prompt"
  const val EXTRA_SYSTEM = "system"
  const val EXTRA_TEMPLATE_ID = "template_id"
  const val EXTRA_TIMEOUT_MS = "timeout_ms"
  const val EXTRA_TOKEN = "token"
  const val EXTRA_RESULT_PACKAGE = "result_package"
  const val EXTRA_REQUEST_ID = "request_id"

  // ---- result extras ----
  const val EXTRA_OK = "ok"
  const val EXTRA_RESPONSE = "response"
  const val EXTRA_ERROR = "error"
  // request_id is echoed back under EXTRA_REQUEST_ID (same key as the input) so the caller can
  // correlate a result with its request.

  // ---- error codes (the `error` extra vocabulary; wire values are part of the ABI contract) ----
  const val ERROR_UNAUTHORIZED = "unauthorized" // bad/missing token (delivered WITHOUT a broadcast)
  const val ERROR_NODE_NOT_RUNNING = "node_not_running" // engine not resident; never cold-started
  const val ERROR_THERMAL = "thermal_backpressure" // ThermalGovernor is shedding
  const val ERROR_BUSY = "busy" // a single-flight decode is already running
  const val ERROR_TIMEOUT = "timeout" // the decode exceeded the (clamped) request timeout
  const val ERROR_UNAVAILABLE = "unavailable" // the OS rejected the foreground-service handoff
  const val ERROR_INFERENCE_FAILED = "inference_failed" // opaque code when a decode throws

  // ---- bounds (clamps + caps) ----
  const val MIN_TIMEOUT_MS = 1_000L
  const val MAX_TIMEOUT_MS = 120_000L
  const val DEFAULT_TIMEOUT_MS = 60_000L
  const val MAX_PROMPT_CHARS = 16_000
  const val MAX_SYSTEM_CHARS = 8_192
  const val MAX_REQUEST_ID_CHARS = 256 // request_id is echoed verbatim into the result — bound it

  /**
   * Parsed, validated ABI request. [prompt] and [token] are non-blank and trimmed; [timeoutMs] is
   * clamped to [[MIN_TIMEOUT_MS], [MAX_TIMEOUT_MS]]; [prompt]/[system] are length-capped.
   */
  data class AbiRequest(
    val prompt: String,
    val system: String?,
    val templateId: String?,
    val timeoutMs: Long,
    val token: String,
    val resultPackage: String?,
    val requestId: String?,
  )

  /**
   * Parses an [AbiRequest] from extra accessors. Returns null when [prompt] or [token] is
   * missing/blank (the caller maps null to RESULT_CANCELED — no inference). Pure: [getString] mirrors
   * `Intent.getStringExtra` (null when absent) and [getLong] mirrors `Intent.getLongExtra(key, def)`.
   *
   * timeout: prefers a real long extra; when that returns the default, falls back to parsing a String
   * `timeout_ms` (Tasker can only set Strings). Garbage/absent => [DEFAULT_TIMEOUT_MS]. Always clamped.
   */
  fun parseRequest(getString: (String) -> String?, getLong: (String, Long) -> Long): AbiRequest? {
    val prompt = getString(EXTRA_PROMPT)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val token = getString(EXTRA_TOKEN)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return AbiRequest(
      prompt = prompt.take(MAX_PROMPT_CHARS),
      system = getString(EXTRA_SYSTEM)?.takeIf { it.isNotBlank() }?.take(MAX_SYSTEM_CHARS),
      templateId = getString(EXTRA_TEMPLATE_ID)?.takeIf { it.isNotBlank() },
      timeoutMs = resolveTimeout(getString, getLong),
      token = token,
      resultPackage = getString(EXTRA_RESULT_PACKAGE)?.takeIf { it.isNotBlank() },
      requestId = getString(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() }?.take(MAX_REQUEST_ID_CHARS),
    )
  }

  /**
   * Resolves the timeout: a real long extra is authoritative; otherwise (the accessor returned the
   * sentinel default) try the String form; a garbage/missing String yields [DEFAULT_TIMEOUT_MS]. The
   * result is always clamped to the safe band so a hostile caller can't request an unbounded decode.
   */
  private fun resolveTimeout(getString: (String) -> String?, getLong: (String, Long) -> Long): Long {
    val asLong = getLong(EXTRA_TIMEOUT_MS, Long.MIN_VALUE)
    val raw =
      if (asLong != Long.MIN_VALUE) asLong
      else getString(EXTRA_TIMEOUT_MS)?.trim()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
    return raw.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
  }

  /**
   * Builds the RESULT intent. Carries [ok], then [response] (success) or [error] (failure), and the
   * [requestId] when present (absent — not an empty string — when null, so callers can test presence).
   * NEVER puts the token or any secret; the only string echoed is the model [response] the caller
   * already owns.
   */
  fun buildResultIntent(
    action: String,
    ok: Boolean,
    response: String?,
    error: String?,
    requestId: String?,
  ): Intent {
    val intent = Intent(action).putExtra(EXTRA_OK, ok)
    if (ok) {
      if (response != null) intent.putExtra(EXTRA_RESPONSE, response)
    } else {
      if (error != null) intent.putExtra(EXTRA_ERROR, error)
    }
    if (requestId != null) intent.putExtra(EXTRA_REQUEST_ID, requestId)
    return intent
  }

  /**
   * Outcome of the pre-decode gate chain. [Run] => hand the request to the foreground service;
   * [Reject] => deliver the structured error and finish (no decode).
   */
  sealed interface AbiGate {
    /** All gates passed — the activity hands off to [RelaisAutomationService]. */
    data object Run : AbiGate

    /**
     * A gate failed. [error] is a wire error code (see ERROR_*); [broadcast] is false ONLY for the
     * unauthenticated path, so an unauthenticated caller can never elicit a RESULT broadcast from the
     * Relais UID to a package it named.
     */
    data class Reject(val error: String, val broadcast: Boolean) : AbiGate
  }

  /**
   * Pure pre-decode gate decision, evaluated in strict security order:
   *  1. AUTH      — `!authorized` => [AbiGate.Reject] `unauthorized`, **broadcast = false**.
   *  2. READINESS — `!ready`      => [AbiGate.Reject] `node_not_running` (never cold-start from intent).
   *  3. THERMAL   — `shouldShed`  => [AbiGate.Reject] `thermal_backpressure`.
   *  else => [AbiGate.Run].
   *
   * Extracted from the (exported, transparent) activity so the ordering and the broadcast-suppression
   * invariant are unit-testable as plain JVM logic instead of through a fragile activity launch.
   */
  fun gate(authorized: Boolean, ready: Boolean, shouldShed: Boolean): AbiGate =
    when {
      !authorized -> AbiGate.Reject(ERROR_UNAUTHORIZED, broadcast = false)
      !ready -> AbiGate.Reject(ERROR_NODE_NOT_RUNNING, broadcast = true)
      shouldShed -> AbiGate.Reject(ERROR_THERMAL, broadcast = true)
      else -> AbiGate.Run
    }
}
