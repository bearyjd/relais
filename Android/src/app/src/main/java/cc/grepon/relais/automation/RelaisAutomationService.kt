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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import cc.grepon.relais.R
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.core.RelaisInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelaisAutomationSvc"
private const val METRIC_ENDPOINT = "/automation"
private const val CHANNEL_ID = "relais_automation_status"
private const val PROGRESS_NOTIFICATION_ID = 0x52454C41 // "RELA" — automation progress slot

// Service-intent extras (private to the activity<->service handoff; the result vocabulary lives in
// RelaisIntentAbi). The caller's TOKEN is intentionally NOT among these: auth happens in the activity
// before the handoff, so the token never reaches this service.
private const val EXTRA_PROMPT = "cc.grepon.relais.automation.PROMPT"
private const val EXTRA_SYSTEM = "cc.grepon.relais.automation.SYSTEM"
private const val EXTRA_TIMEOUT_MS = "cc.grepon.relais.automation.TIMEOUT_MS"
private const val EXTRA_RESULT_PACKAGE = "cc.grepon.relais.automation.RESULT_PACKAGE"
private const val EXTRA_REQUEST_ID = "cc.grepon.relais.automation.REQUEST_ID"

/**
 * Short-lived foreground service that runs ONE Tasker/Automate ABI (#8) decode off the launching
 * activity's lifecycle — the fix for issue #44. The exported [RelaisTaskerActivity] is transparent
 * and finishes immediately; a 30–120s decode cannot survive there (NoDisplay/translucent activities,
 * especially under a cross-app `singleTask` launch, are torn down before the decode completes). The
 * decode therefore lives here, in an independently-scheduled service, exactly as the share path
 * ([cc.grepon.relais.share.RelaisShareService]) already does.
 *
 * Contract:
 *  - CONSUMER of the resident engine: re-asserts [RelaisInference.isReady] (defense in depth) and
 *    NEVER cold-starts. The activity already gated on readiness; the node could still have stopped in
 *    between.
 *  - Holds the PROCESS-GLOBAL single-flight latch (moved here from the activity, where it leaked when
 *    the activity was destroyed mid-decode) so a looped exported launch can't stack decodes.
 *  - Delivers the outcome via the package-targeted [RelaisIntentAbi.ACTION_INFER_RESULT] broadcast —
 *    the only reliable automation channel (a finished activity can't carry the async answer). Never a
 *    global broadcast; the RESULT never echoes the token.
 *  - `dataSync` foreground type; not exported.
 */
class RelaisAutomationService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // The main-thread handler. The stop decision ([stopIfIdle]) runs ONLY on the main thread — the same
  // thread onStartCommand is delivered on — so the `inFlight` check and the stop are serialized with
  // every start and can never race a concurrent one.
  private val mainHandler = Handler(Looper.getMainLooper())

  // The most recent startId. The service is a singleton: every start lands on this one instance, so a
  // rejected/duplicate start must never tear it down while a real decode runs (that silently lost the
  // answer — the #44 failure mode). We only ever stop when idle, via [stopIfIdle].
  @Volatile private var latestStartId = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    latestStartId = startId
    ensureChannel(this)
    startForeground(PROGRESS_NOTIFICATION_ID, buildProgress(), foregroundType())

    val job = jobFromIntent(intent)
    if (job == null) {
      // No usable payload reached the service (the activity gates on a non-blank prompt). Stop cleanly
      // (only if idle) without a decode or a misleading result.
      Log.i(TAG, "no payload; stopping if idle")
      stopIfIdle()
      return START_NOT_STICKY
    }

    // Single-flight: one ABI decode at a time across every exported launch. Busy => structured `busy`.
    // Do NOT stop the service on this path: a decode is in flight (that's why the CAS failed), and
    // stopSelf on this most-recent startId would cancel it — re-introducing #44 (the answer lost).
    if (!inFlight.compareAndSet(false, true)) {
      Log.i(TAG, "an ABI inference is already running; dropping this start")
      deliver(job, ok = false, response = null, error = RelaisIntentAbi.ERROR_BUSY)
      stopIfIdle() // no-op while a decode runs; only stops a genuinely idle service
      return START_NOT_STICKY
    }

    scope.launch {
      try {
        // Re-assert readiness: the activity gated on isReady() at hand-off, but the node could have
        // stopped before this runs — never let the run cold-start the engine.
        if (!RelaisInference.isReady()) {
          // Node went down between the activity's pre-flight gate and now. This is the mid-run
          // node-down case, recorded as 503 (same as the NodeNotReadyException catch below); the
          // activity's pre-flight `node_not_running` gate deliberately records no request metric.
          Log.i(TAG, "engine not resident at run time; refusing to cold-start")
          RelaisMetrics.recordRequest(METRIC_ENDPOINT, 503)
          deliver(job, ok = false, response = null, error = RelaisIntentAbi.ERROR_NODE_NOT_RUNNING)
          return@launch
        }
        val answer =
          withTimeout(job.timeoutMs) {
            RelaisInference.completeText(applicationContext, job.prompt, job.system)
          }
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 200)
        deliver(job, ok = true, response = answer, error = null)
      } catch (t: TimeoutCancellationException) {
        // withTimeout's own cancellation: a real ABI outcome (not caller cancellation) — deliver it.
        Log.i(TAG, "ABI inference timed out after ${job.timeoutMs}ms")
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 504)
        deliver(job, ok = false, response = null, error = RelaisIntentAbi.ERROR_TIMEOUT)
      } catch (t: CancellationException) {
        throw t // never swallow structured cancellation (e.g. the service being torn down)
      } catch (t: RelaisInference.NodeNotReadyException) {
        Log.i(TAG, "node went down mid-run", t)
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 503)
        deliver(job, ok = false, response = null, error = RelaisIntentAbi.ERROR_NODE_NOT_RUNNING)
      } catch (t: Throwable) {
        // Detail goes to logcat only; the `error` extra is broadcast to a third-party package, so it
        // must NOT carry an internal exception message — deliver the opaque, stable ABI code.
        Log.e(TAG, "ABI inference failed", t) // never silently swallow
        RelaisMetrics.recordRequest(METRIC_ENDPOINT, 500)
        deliver(job, ok = false, response = null, error = RelaisIntentAbi.ERROR_INFERENCE_FAILED)
      } finally {
        inFlight.set(false) // always release the single-flight latch, incl. on cancellation
        // Run the stop decision on the main thread so it's serialized with onStartCommand: a start
        // landing concurrently with this teardown is then either fully observed (we don't stop) or
        // not yet begun (we stop an idle service it simply restarts) — never a cancelled live decode.
        mainHandler.post { stopIfIdle() }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    inFlight.set(false) // defensive: never leave the latch stuck if torn down mid-run
    scope.cancel() // cancels the decode if the service is torn down (releases the engine lock)
    super.onDestroy()
  }

  /**
   * Stops the service ONLY when no decode is in flight, addressed to the most recent startId (so a
   * start that arrives during teardown is honored, not dropped). A rejected/duplicate start calling
   * this is a no-op while a real decode runs — that is the guard against silently cancelling the
   * in-flight answer (the #44 failure mode).
   *
   * MUST run on the main thread (called from `onStartCommand` directly, or posted via [mainHandler]
   * from the decode's `finally`). That serializes the [inFlight] check + stop with every start, so a
   * concurrent start is either fully observed here ([inFlight] true => we don't stop) or hasn't begun
   * (we stop an idle service the incoming start simply restarts) — never a cancelled live decode.
   */
  private fun stopIfIdle() {
    if (!inFlight.get()) stopSelf(latestStartId)
  }

  /**
   * Delivers the outcome via the package-targeted RESULT broadcast — ONLY when `result_package` is
   * set, and ALWAYS targeted via [Intent.setPackage] (never a global/implicit broadcast, which would
   * leak the model output to every installed app). The RESULT never carries the token.
   */
  private fun deliver(job: AutomationJob, ok: Boolean, response: String?, error: String?) {
    val pkg = job.resultPackage
    if (pkg == null) {
      // No automation channel: a finished activity can't carry the async answer either (documented
      // ABI limitation — set `result_package` to receive the result).
      Log.i(TAG, "no result_package; outcome not deliverable (ok=$ok)")
      return
    }
    val result =
      RelaisIntentAbi.buildResultIntent(
        action = RelaisIntentAbi.ACTION_INFER_RESULT,
        ok = ok,
        response = response,
        error = error,
        requestId = job.requestId,
      )
    val out = Intent(result).setPackage(pkg) // targeted only — scopes delivery to the named app
    runCatching { sendBroadcast(out) }
      .onFailure { Log.w(TAG, "failed to send targeted RESULT broadcast to $pkg", it) }
  }

  private fun buildProgress(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Relais · working")
      .setContentText("Running an automation prompt on-device…")
      .setSmallIcon(R.drawable.ic_relais_tile)
      .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
      .setOngoing(true)
      .setProgress(0, 0, true) // indeterminate
      .build()

  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else 0

  private fun ensureChannel(ctx: Context) {
    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Relais automation", NotificationManager.IMPORTANCE_LOW)
          .apply { description = "Transient status while an automation (Tasker/Automate) prompt runs" }
      )
    }
  }

  /** The activity->service payload. No token: auth is enforced in the activity before the handoff. */
  internal data class AutomationJob(
    val prompt: String,
    val system: String?,
    val timeoutMs: Long,
    val resultPackage: String?,
    val requestId: String?,
  )

  companion object {
    // Process-global single-flight: one ABI decode at a time, shared across every exported launch so
    // a malicious app looping the activity can't stack 30–120s decodes on the engine lock.
    private val inFlight = AtomicBoolean(false)

    /**
     * Builds the start intent carrying the (already-authenticated) ABI request. [prompt] is non-blank;
     * [system] is the resolved system prompt (request override or template); [timeoutMs] is the
     * clamped ABI timeout. The token is deliberately absent.
     */
    fun startIntent(
      context: Context,
      prompt: String,
      system: String?,
      timeoutMs: Long,
      resultPackage: String?,
      requestId: String?,
    ): Intent =
      Intent(context, RelaisAutomationService::class.java).apply {
        putExtra(EXTRA_PROMPT, prompt)
        if (system != null) putExtra(EXTRA_SYSTEM, system)
        putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
        if (resultPackage != null) putExtra(EXTRA_RESULT_PACKAGE, resultPackage)
        if (requestId != null) putExtra(EXTRA_REQUEST_ID, requestId)
      }

    /** Parses a start intent back into an [AutomationJob]; null when the prompt is missing/blank. */
    internal fun jobFromIntent(intent: Intent?): AutomationJob? {
      val prompt = intent?.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() } ?: return null
      return AutomationJob(
        prompt = prompt,
        system = intent.getStringExtra(EXTRA_SYSTEM)?.takeIf { it.isNotBlank() },
        timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, RelaisIntentAbi.DEFAULT_TIMEOUT_MS),
        resultPackage = intent.getStringExtra(EXTRA_RESULT_PACKAGE)?.takeIf { it.isNotBlank() },
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() },
      )
    }
  }
}
