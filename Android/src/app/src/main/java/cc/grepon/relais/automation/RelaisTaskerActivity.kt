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

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.templates.WorkflowRegistry
import java.security.MessageDigest

private const val TAG = "RelaisTasker"
private const val METRIC_ENDPOINT = "/automation"

/**
 * Exported, transparent trampoline backing the Tasker/Automate intent ABI (#8). An automation app (or
 * adb) launches it with [RelaisIntentAbi.ACTION_INFER], a prompt, and the node's API key; the answer
 * comes back via a package-targeted RESULT broadcast (set `result_package`).
 *
 * It is ONLY a gate + hand-off (the #44 fix). The multi-second decode does NOT run here: a transparent
 * activity — especially under a cross-app launch — is torn down before a 30–120s decode completes, so
 * the old in-activity coroutine never delivered the success path. The decode now runs in
 * [RelaisAutomationService] (a started foreground service), exactly like the share path. This activity:
 *
 *  1. Parses + validates the request ([RelaisIntentAbi.parseRequest]); a missing prompt/token => just
 *     RESULT_CANCELED + finish (no structured outcome to deliver).
 *  2. Runs the pure gate chain ([RelaisIntentAbi.gate]) in security order:
 *       AUTH (constant-time) -> READINESS (consumer only, never cold-starts) -> THERMAL.
 *     A failed gate is delivered SYNCHRONOUSLY here (activity-result + targeted broadcast, the broadcast
 *     suppressed for the unauthenticated path) and the activity finishes.
 *  3. On pass, resolves the system prompt, hands the (token-free) request to [RelaisAutomationService],
 *     sets RESULT_OK as a bare "accepted" ack, and finishes immediately.
 *
 * Single-flight + the decode + the run-outcome broadcast all live in the service. The result NEVER
 * echoes the token (see [RelaisIntentAbi.buildResultIntent]).
 */
class RelaisTaskerActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val request =
      RelaisIntentAbi.parseRequest(
        getString = { key -> intent?.getStringExtra(key) },
        getLong = { key, def -> intent?.getLongExtra(key, def) ?: def },
      )
    if (request == null) {
      // Missing/blank prompt or token: no structured outcome to deliver — just cancel + finish.
      setResult(RESULT_CANCELED)
      finish()
      return
    }

    when (val gate =
      RelaisIntentAbi.gate(
        authorized = authorized(request.token),
        ready = RelaisInference.isReady(),
        shouldShed = ThermalGovernor.shouldShed(),
      )
    ) {
      is RelaisIntentAbi.AbiGate.Reject -> rejectAndFinish(request, gate)
      RelaisIntentAbi.AbiGate.Run -> runAndFinish(request)
    }
  }

  /** Constant-time bearer-key compare, identical to the HTTP server's `authorized`. */
  private fun authorized(token: String): Boolean =
    MessageDigest.isEqual(token.toByteArray(), RelaisConfig.apiKey(this).toByteArray())

  /**
   * Delivers a gate failure synchronously and finishes. The unauthorized path records a 401 and
   * suppresses the broadcast; the thermal path records a shed. The cold-start (`node_not_running`) gate
   * records no request metric, matching the prior behavior.
   */
  private fun rejectAndFinish(
    request: RelaisIntentAbi.AbiRequest,
    reject: RelaisIntentAbi.AbiGate.Reject,
  ) {
    when (reject.error) {
      RelaisIntentAbi.ERROR_UNAUTHORIZED -> {
        Log.w(TAG, "rejected INFER intent: missing/invalid token")
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 401)
      }
      RelaisIntentAbi.ERROR_THERMAL -> {
        Log.i(TAG, "thermal backpressure; shedding INFER intent")
        RelaisMetrics.recordShed()
      }
      RelaisIntentAbi.ERROR_NODE_NOT_RUNNING ->
        Log.i(TAG, "node not running; refusing to cold-start from INFER intent")
    }
    // deliver() sets RESULT_CANCELED + the error data intent (and broadcasts unless suppressed); don't
    // overwrite it with a bare setResult, or startActivityForResult callers lose the error.
    deliver(request, ok = false, response = null, error = reject.error, broadcast = reject.broadcast)
    finish()
  }

  /** Resolves the system prompt, hands the request to the foreground service, and finishes. */
  private fun runAndFinish(request: RelaisIntentAbi.AbiRequest) {
    val system = request.system ?: WorkflowRegistry.resolve(this, request.templateId)?.system
    val svc =
      RelaisAutomationService.startIntent(
        context = this,
        prompt = request.prompt,
        system = system,
        timeoutMs = request.timeoutMs,
        resultPackage = request.resultPackage,
        requestId = request.requestId,
      )
    val started =
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
      }.isSuccess
    if (started) {
      // Bare "accepted" ack for startActivityForResult callers; the answer arrives via the RESULT
      // broadcast (a finished activity can't carry an async decode result).
      setResult(RESULT_OK)
    } else {
      // The OS rejected the FGS start (rare from a foreground activity); deliver a structured error
      // rather than silently dropping the request. The caller is already authenticated => broadcast ok.
      // deliver() sets RESULT_CANCELED + the error data intent itself.
      Log.w(TAG, "failed to start automation service")
      deliver(request, ok = false, response = null, error = RelaisIntentAbi.ERROR_UNAVAILABLE)
    }
    finish()
  }

  /**
   * Delivers a synchronous outcome two ways:
   *  - activity-result (for `startActivityForResult` callers).
   *  - a RESULT broadcast — ONLY when `result_package` is set and [broadcast] is true, and ALWAYS
   *    targeted via [Intent.setPackage]. Never global/implicit (that would leak output to every app);
   *    suppressed on the unauthenticated path so a bad token can't elicit a broadcast from the Relais
   *    UID. The RESULT never carries the token.
   */
  private fun deliver(
    request: RelaisIntentAbi.AbiRequest,
    ok: Boolean,
    response: String?,
    error: String?,
    broadcast: Boolean = true,
  ) {
    val result =
      RelaisIntentAbi.buildResultIntent(
        action = RelaisIntentAbi.ACTION_INFER_RESULT,
        ok = ok,
        response = response,
        error = error,
        requestId = request.requestId,
      )
    setResult(if (ok) RESULT_OK else RESULT_CANCELED, result)
    if (!broadcast) return
    request.resultPackage?.let { pkg ->
      val out = Intent(result).setPackage(pkg) // targeted only — setPackage scopes delivery
      runCatching { sendBroadcast(out) }
        .onFailure { Log.w(TAG, "failed to send targeted RESULT broadcast to $pkg", it) }
    }
  }
}
