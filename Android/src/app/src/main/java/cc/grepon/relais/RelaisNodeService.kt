/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "RelaisNodeService"
private const val CHANNEL_ID = "relais_node"
private const val NOTIFICATION_ID = 4242

/**
 * Pure decision behind [RelaisNodeService]'s startup dispatch guard: should a new init attempt be
 * launched right now? Extracted so the exact onCreate/onStartCommand gating logic is JVM-testable
 * without a Context/Service. The real concurrency guard is an `AtomicBoolean.compareAndSet` in
 * [RelaisNodeService] itself — this predicate is a readable pre-check, not the sole source of
 * atomicity (this file can't observe a CAS race in a plain JVM test).
 */
internal fun shouldDispatchStartup(ready: Boolean, dispatchInFlight: Boolean): Boolean =
  !ready && !dispatchInFlight

/**
 * Headless foreground service that hosts the resident multimodal engine (Gate 1) and the LAN
 * endpoint (Gate 3), and holds a partial wake lock so the engine survives screen-off / Doze
 * (Gate 2).
 *
 * Bind to it for in-process access via [LocalBinder]; the engine is also reachable over HTTP.
 */
class RelaisNodeService : Service() {
  private val binder = LocalBinder()
  private var httpServer: RelaisHttpServer? = null
  private var httpsServer: RelaisHttpServer? = null
  private var wakeLock: PowerManager.WakeLock? = null

  // Guards the single init path (delta review: onStartCommand used to be a bare START_STICKY, so a
  // retry START against an already-alive-but-failed service — gated-repo 401, bad model id, process
  // never died — silently did nothing; same dead path for the watchdog's revive). CAS makes repeated
  // dispatch calls (onCreate racing onStartCommand, multiple START taps, watchdog + operator racing)
  // launch at most one concurrent "relais-init" thread.
  private val startupDispatchInFlight = AtomicBoolean(false)

  inner class LocalBinder : Binder() {
    val isReady: Boolean
      get() = RelaisEngine.isReady

    fun generate(request: RelaisRequest): RelaisResult =
      RelaisEngine.generate(this@RelaisNodeService, request)
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIFICATION_ID, buildNotification("Starting…"), foregroundType())

    @Suppress("DEPRECATION")
    wakeLock =
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relais:node")
        .apply { setReferenceCounted(false); acquire() }

    RelaisConfig.incrementRestartCount(applicationContext) // process/service starts; via /metrics (Gate 3)
    ThermalGovernor.register(applicationContext) // thermal-aware backpressure (Gate 3)

    dispatchStartupIfNeeded()
  }

  /**
   * The single guarded entry point into the "relais-init" path — called from both [onCreate] (a
   * fresh process/service instance) and [onStartCommand] (every `startForegroundService` call,
   * including one against an already-alive instance). That second call site is the fix: a service
   * that's alive but never came up (failed init, no `stopSelf()`) previously had no way to re-init —
   * `onStartCommand` was a bare `START_STICKY` — so a retry START (control-panel or
   * [RelaisWatchdog]'s revive) silently did nothing.
   *
   * [shouldDispatchStartup] is a readable pre-check; [startupDispatchInFlight]'s `compareAndSet` is
   * what actually makes concurrent calls (onCreate racing onStartCommand, repeated START taps) safe.
   */
  private fun dispatchStartupIfNeeded() {
    if (!shouldDispatchStartup(RelaisEngine.isReady, startupDispatchInFlight.get())) return
    if (!startupDispatchInFlight.compareAndSet(false, true)) return // lost the race; another dispatch is already running

    // Provision the model (download if missing) then initialize the resident engine off the main
    // thread; start the endpoint when ready.
    thread(name = "relais-init") {
      RelaisEngine.lastInitFailed = false // new attempt: drop any prior failure so a restart-after-
      // failure doesn't flash NodeState.ERROR in the window before startupInProgress flips.
      RelaisEngine.startupInProgress = true // tell the watchdog "coming up", not "dead" (slow downloads)
      RelaisNodeProgress.reset() // drop any stale phase/bytes from a prior attempt (control-panel phase line)
      try {
        updateNotification("Provisioning model…")
        val modelPath =
          RelaisModelProvisioner.ensureModel(applicationContext) { pct ->
            updateNotification("Downloading model $pct%…")
          }
        RelaisNodeProgress.phase = ProvisionPhase.LOADING_ENGINE
        RelaisEngine.ensureInitialized(applicationContext, modelPath)
        // Register the EmbeddingGemma embedder so /v1/embeddings can report availability + provision
        // on demand. register() is cheap (no download/load). warmIfProvisioned() background-loads an
        // ALREADY-downloaded model (no token, no fetch) so a restart serves embeddings without a first
        // 503; on a fresh node it no-ops, and the endpoint provisions on the first embeddings request.
        cc.grepon.relais.embed.EmbeddingGemmaEmbedder.register()
        cc.grepon.relais.embed.EmbeddingGemmaEmbedder.INSTANCE.warmIfProvisioned(applicationContext)
        // Image-gen (#16): register the flavor's RelaisImageGenerator — full = sd.cpp/Vulkan via the
        // process-isolated :imagegen service; degoogled = no-op (endpoint stays 501). Cheap (no load);
        // the route gates on isAvailable (Vulkan + provisioned) and provisions on demand via 503.
        cc.grepon.relais.imagegen.ImageGenRegistration.register(applicationContext)
        // Security C1: plaintext HTTP is loopback-only (in-device app/dev); the LAN is served only
        // over HTTPS, so the bearer key never crosses the network in cleartext.
        httpServer = RelaisHttpServer(applicationContext, port = 8080, bindAddr = "127.0.0.1").also { it.start() }
        httpsServer = RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0").also { it.start() }
        RelaisDiscovery.register(applicationContext) // advertise _relais._tcp for zero-config LAN discovery
        // Periodic TTL prune for the optional session memory (Feature #5). Idempotent + no-ops when
        // session memory is disabled, so scheduling it unconditionally is a true no-op by default.
        cc.grepon.relais.worker.SessionPruneWorker.schedule(applicationContext)
        // Drain any batch jobs left queued by a prior crash/restart (Feature #14); a no-op if empty.
        cc.grepon.relais.worker.BatchWorker.kick(applicationContext)
        updateNotification("Resident engine ready · http 127.0.0.1:8080 · https :8443 (LAN)")
        Log.i(TAG, "Node up: engine resident; http loopback :8080, https LAN :8443")
        // Security H3: never log the API key — it is shown in the Relais Node control screen.
      } catch (e: Exception) {
        Log.e(TAG, "Node init failed", e)
        RelaisEngine.lastInitFailed = true // surfaced as NodeState.ERROR (e.g. QS tile)
        updateNotification("Init failed: ${e.message}")
      } finally {
        RelaisEngine.startupInProgress = false
        RelaisNodeProgress.reset()
        startupDispatchInFlight.set(false) // release the guard — a future retry (fresh START) may dispatch again
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Re-dispatch on every start command, not just onCreate — this is what lets a retry START (or
    // RelaisWatchdog's revive) actually re-init an already-alive-but-failed service. No-ops via
    // shouldDispatchStartup/the CAS guard when already LIVE or already mid-attempt.
    dispatchStartupIfNeeded()
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // The node is headless and must outlive its control-panel task. If the task is removed (user
    // swipe, or a recents sweep), keep running and make sure the watchdog heartbeat is armed. (A
    // true force-stop still can't be recovered in-app — Android disables a stopped app's alarms and
    // boot-receiver until it's explicitly relaunched; that's why the panel is excludeFromRecents.)
    reArmWatchdogIfShouldRun(applicationContext)
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    ThermalGovernor.unregister()
    RelaisDiscovery.unregister()
    httpServer?.stop()
    httpsServer?.stop()
    RelaisEngine.shutdown()
    runCatching { wakeLock?.release() }
    super.onDestroy()
    Log.i(TAG, "Node stopped")
  }

  private fun foregroundType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    else 0

  private fun createChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Relais Node", NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun buildNotification(text: String): Notification =
    Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Relais Node")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setOngoing(true)
      .build()

  private fun updateNotification(text: String) {
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
  }

  companion object {
    fun start(context: Context) {
      RelaisConfig.setShouldRun(context, true)
      val intent = Intent(context, RelaisNodeService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
      else context.startService(intent)
      RelaisWatchdog.schedule(context) // self-heal after crash/OOM (START_STICKY alone insufficient)
    }

    fun stop(context: Context) {
      RelaisConfig.setShouldRun(context, false)
      RelaisWatchdog.cancel(context)
      context.stopService(Intent(context, RelaisNodeService::class.java))
    }

    /**
     * Re-arm the crash/OOM watchdog heartbeat iff the node is meant to be running. Extracted from
     * [onTaskRemoved] — which can't be invoked on a bare Service instance — so the gate is
     * instrumentable. Starting an already-scheduled watchdog is idempotent.
     */
    internal fun reArmWatchdogIfShouldRun(context: Context) {
      if (RelaisConfig.shouldRun(context)) RelaisWatchdog.schedule(context)
    }
  }
}
