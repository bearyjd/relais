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
import kotlin.concurrent.thread

private const val TAG = "RelaisNodeService"
private const val CHANNEL_ID = "relais_node"
private const val NOTIFICATION_ID = 4242

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

    // Provision the model (download if missing) then initialize the resident engine off the main
    // thread; start the endpoint when ready.
    thread(name = "relais-init") {
      RelaisEngine.startupInProgress = true // tell the watchdog "coming up", not "dead" (slow downloads)
      try {
        updateNotification("Provisioning model…")
        val modelPath =
          RelaisModelProvisioner.ensureModel(applicationContext) { pct ->
            updateNotification("Downloading model $pct%…")
          }
        RelaisEngine.ensureInitialized(applicationContext, modelPath)
        // Security C1: plaintext HTTP is loopback-only (in-device app/dev); the LAN is served only
        // over HTTPS, so the bearer key never crosses the network in cleartext.
        httpServer = RelaisHttpServer(applicationContext, port = 8080, bindAddr = "127.0.0.1").also { it.start() }
        httpsServer = RelaisHttpServer(applicationContext, port = 8443, tls = true, bindAddr = "0.0.0.0").also { it.start() }
        RelaisDiscovery.register(applicationContext) // advertise _relais._tcp for zero-config LAN discovery
        updateNotification("Resident engine ready · http 127.0.0.1:8080 · https :8443 (LAN)")
        Log.i(TAG, "Node up: engine resident; http loopback :8080, https LAN :8443")
        // Security H3: never log the API key — it is shown in the Relais Node control screen.
      } catch (e: Exception) {
        Log.e(TAG, "Node init failed", e)
        updateNotification("Init failed: ${e.message}")
      } finally {
        RelaisEngine.startupInProgress = false
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
  }
}
