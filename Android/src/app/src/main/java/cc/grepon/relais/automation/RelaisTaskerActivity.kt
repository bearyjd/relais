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
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.templates.WorkflowRegistry
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "RelaisTasker"
private const val METRIC_ENDPOINT = "/automation"

/**
 * Exported, NoDisplay activity backing the Tasker/Automate intent ABI (#8). An automation app (or
 * adb) launches it with [RelaisIntentAbi.ACTION_INFER], a prompt, and the node's API key; the answer
 * comes back via activity-result and (when `result_package` is set) a package-targeted RESULT
 * broadcast.
 *
 * Gating order (each gate delivers a structured error + finishes, never running inference past it):
 *  1. AUTH (mandatory) — constant-time token compare; mismatch => `unauthorized` (+ 401 metric).
 *  2. COLD-START GUARD — only a CONSUMER of the resident engine; if not [RelaisInference.isReady] =>
 *     `node_not_running`. NEVER cold-starts the engine from an exported intent.
 *  3. THERMAL (device-safety) — [ThermalGovernor.shouldShed] => `thermal_backpressure`. The ABI must
 *     not bypass thermal shedding.
 *  4. SINGLE-FLIGHT — a process-global latch so a looped exported launch can't stack decodes; busy =>
 *     `busy`.
 *
 * The 30–120s decode runs in a [lifecycleScope] coroutine on [Dispatchers.IO], bounded by
 * [withTimeout] — acceptable in a NoDisplay activity (it's a coroutine, not the main thread, so no
 * ANR). The result NEVER echoes the token (see [RelaisIntentAbi.buildResultIntent]).
 */
class RelaisTaskerActivity : ComponentActivity() {

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

    // 1. AUTH (mandatory). Constant-time compare, same as the HTTP path. No inference on a bad token.
    if (!authorized(request.token)) {
      Log.w(TAG, "rejected INFER intent: missing/invalid token")
      RelaisMetrics.recordRequest(METRIC_ENDPOINT, 401)
      // Activity-result only (broadcast=false): never emit a broadcast on an unauthenticated request.
      deliver(request, ok = false, response = null, error = "unauthorized", broadcast = false)
      finish()
      return
    }

    // 2. COLD-START GUARD. Consumer only — never provision/cold-start the engine from this intent.
    if (!RelaisInference.isReady()) {
      Log.i(TAG, "node not running; refusing to cold-start from INFER intent")
      finishWith(request, ok = false, error = "node_not_running")
      return
    }

    // 3. THERMAL (device-safety blocker). The ABI honors thermal shedding like every inference path.
    if (ThermalGovernor.shouldShed()) {
      Log.i(TAG, "thermal backpressure; shedding INFER intent")
      RelaisMetrics.recordShed()
      finishWith(request, ok = false, error = "thermal_backpressure")
      return
    }

    // 4. SINGLE-FLIGHT. One ABI decode at a time so a looped exported launch can't stack decodes on
    // the engine lock (mirrors the share service's process-global latch).
    if (!inFlight.compareAndSet(false, true)) {
      Log.i(TAG, "an ABI inference is already running; dropping this launch")
      finishWith(request, ok = false, error = "busy")
      return
    }

    val system = request.system ?: WorkflowRegistry.resolve(this, request.templateId)?.system
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val answer =
          withTimeout(request.timeoutMs) {
            RelaisInference.completeText(applicationContext, request.prompt, system)
          }
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 200)
        deliver(request, ok = true, response = answer, error = null)
      } catch (t: TimeoutCancellationException) {
        // withTimeout's own cancellation: a real ABI outcome, not caller cancellation — deliver it.
        Log.i(TAG, "ABI inference timed out after ${request.timeoutMs}ms")
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 504)
        deliver(request, ok = false, response = null, error = "timeout")
      } catch (t: CancellationException) {
        throw t // never swallow structured cancellation (e.g. the activity being torn down)
      } catch (t: RelaisInference.NodeNotReadyException) {
        Log.i(TAG, "node went down mid-run", t)
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 503)
        deliver(request, ok = false, response = null, error = "node_not_running")
      } catch (t: Throwable) {
        Log.e(TAG, "ABI inference failed", t) // never silently swallow
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 500)
        deliver(request, ok = false, response = null, error = t.message ?: "inference failed")
      } finally {
        inFlight.set(false) // always release the single-flight latch, incl. on cancellation
        finish()
      }
    }
  }

  /** Constant-time bearer-key compare, identical to the HTTP server's [authorized]. */
  private fun authorized(token: String): Boolean =
    MessageDigest.isEqual(token.toByteArray(), RelaisConfig.apiKey(this).toByteArray())

  /** Delivers an early-exit outcome (gate failure): result + targeted broadcast, then finishes. */
  private fun finishWith(request: RelaisIntentAbi.AbiRequest, ok: Boolean, error: String?) {
    deliver(request, ok = ok, response = null, error = error)
    setResult(if (ok) RESULT_OK else RESULT_CANCELED)
    finish()
  }

  /**
   * Delivers the outcome two ways:
   *  - activity-result (for `startActivityForResult` callers) — set whenever we have a structured
   *    outcome (the gate paths set their own RESULT_* before finishing; the run path sets it here).
   *  - a RESULT broadcast — ONLY when `result_package` is set, and ALWAYS targeted via
   *    [Intent.setPackage]. Never a global/implicit broadcast: that would leak the model output to
   *    every installed app. The RESULT intent never carries the token.
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
    // The broadcast is suppressed on the pre-auth (unauthenticated) path so an unauthenticated caller
    // can't elicit a RESULT broadcast from the Relais UID to a package it chose.
    if (!broadcast) return
    request.resultPackage?.let { pkg ->
      // Targeted broadcast only — setPackage scopes delivery to the named app (never implicit).
      val out = Intent(result).setPackage(pkg)
      runCatching { sendBroadcast(out) }
        .onFailure { Log.w(TAG, "failed to send targeted RESULT broadcast to $pkg", it) }
    }
  }

  companion object {
    // Process-global single-flight: one ABI decode at a time, shared across every exported launch so
    // a malicious app looping the activity can't stack 30–120s decodes on the engine lock.
    private val inFlight = AtomicBoolean(false)
  }
}
