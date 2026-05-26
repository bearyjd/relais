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

package com.google.ai.edge.gallery.relais

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

private const val TAG = "RelaisWatchdog"
private const val INTERVAL_MS = 60_000L

/**
 * Crash/OOM recovery for the node. `START_STICKY` alone did NOT restart the foreground service
 * after an app-process crash (measured: no recovery in 250s). This self-rescheduling alarm fires
 * ~every 60s; if [RelaisConfig.shouldRun] is set it (re)starts the service — so a dead process is
 * resurrected on the next tick. Starting an already-running node is idempotent.
 */
object RelaisWatchdog {
  fun schedule(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val at = SystemClock.elapsedRealtime() + INTERVAL_MS
    val pi = pendingIntent(context)
    // Exact alarms grant a temporary FGS-start exemption when they fire — required because a
    // background receiver otherwise hits ForegroundServiceStartNotAllowed on Android 12+.
    try {
      am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    } catch (e: SecurityException) {
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) // fallback if not permitted
    }
  }

  fun cancel(context: Context) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
  }

  private fun pendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, RelaisWatchdogReceiver::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Receives the watchdog tick (in a fresh process if the app was killed) and revives the node. */
class RelaisWatchdogReceiver : BroadcastReceiver {
  constructor() : super()

  override fun onReceive(context: Context, intent: Intent) {
    if (!RelaisConfig.shouldRun(context)) return
    RelaisWatchdog.schedule(context) // reschedule FIRST so a failed start never stops the heartbeat
    if (!RelaisEngine.isReady) {
      Log.i(TAG, "watchdog tick — node not ready, (re)starting")
      try {
        RelaisNodeService.start(context)
      } catch (e: Exception) {
        Log.e(TAG, "watchdog restart failed: ${e.message}")
      }
    }
  }
}
