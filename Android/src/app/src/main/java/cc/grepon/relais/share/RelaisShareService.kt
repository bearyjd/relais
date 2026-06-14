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

package cc.grepon.relais.share

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.grepon.relais.R
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.core.RelaisInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RelaisShareService"
private const val CHANNEL_ID = "relais_share_result"
private const val PROGRESS_NOTIFICATION_ID = 0x52454C53 // "RELS" — foreground/progress slot
private const val RESULT_NOTIFICATION_ID = 0x52454C54 // "RELT" — stable result slot, so results don't stack
private const val EXTRA_PAYLOAD = "cc.grepon.relais.share.PAYLOAD"
private const val RESULT_CAP = 1_000 // bound the notification body (the shade is a public surface)
private const val CLIP_LABEL = "Relais"

/** Concise built-in system prompt used when the operator hasn't set an override (Feature #1). */
const val DEFAULT_SHARE_SYSTEM = "Summarize or answer the shared text clearly and concisely."

/**
 * Short-lived foreground service that runs ONE share-sheet inference off the trampoline's lifecycle,
 * so a 30–120s decode survives [RelaisShareActivity] finishing immediately. It is a CONSUMER of the
 * already-resident engine: it re-asserts [RelaisInference.isReady] (defense in depth) and never
 * cold-starts. On success it posts a DESIGN.md-styled result notification (capped) AND copies the
 * full result to the clipboard; on error it posts an error notification rather than crashing or
 * swallowing. `dataSync` foreground type; a stable result notification id so repeated shares replace
 * rather than stack.
 */
class RelaisShareService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannel(this)
    startForeground(PROGRESS_NOTIFICATION_ID, buildProgress(), foregroundType())

    val payload = intent?.getStringExtra(EXTRA_PAYLOAD)?.takeIf { it.isNotBlank() }
    if (payload == null) {
      // No usable payload reached the service (shouldn't happen — the trampoline gates on it). Stop
      // cleanly without an inference or a misleading result.
      Log.i(TAG, "no payload; stopping")
      stopSelf(startId)
      return START_NOT_STICKY
    }

    // Drop a second concurrent share instead of stacking another decode on the engine lock.
    if (!inFlight.compareAndSet(false, true)) {
      Log.i(TAG, "a share inference is already running; dropping this start")
      postResult(title = "Relais · busy", text = "A share is already being processed.")
      stopSelf(startId)
      return START_NOT_STICKY
    }

    scope.launch {
      try {
        // Re-assert readiness inside the service: the trampoline gated on isReady() at hand-off, but
        // the node could have stopped before this runs — never let the run cold-start the engine.
        if (!RelaisInference.isReady()) {
          Log.i(TAG, "engine not resident at run time; skipping share inference")
          postResult(title = "Relais · node not running", text = "Start the node, then share again.")
          return@launch
        }
        val system = RelaisConfig.shareSystemPrompt(applicationContext) ?: DEFAULT_SHARE_SYSTEM
        val answer = runCatching {
          RelaisInference.completeText(applicationContext, prompt = payload, system = system)
        }.getOrElse { t ->
          if (t is kotlinx.coroutines.CancellationException) throw t // never swallow cancellation
          if (t is RelaisInference.NodeNotReadyException) {
            Log.i(TAG, "node went down mid-run", t)
            postResult(title = "Relais · node not running", text = "Start the node, then share again.")
          } else {
            Log.e(TAG, "share inference failed", t) // never silently swallow
            postResult(title = "Relais · error", text = t.message ?: "Inference failed.")
          }
          return@launch
        }
        copyToClipboard(answer) // full result on the clipboard
        postResult(title = "Relais · result", text = answer.take(RESULT_CAP)) // capped on the shade
      } finally {
        inFlight.set(false) // always release the single-flight latch, incl. on cancellation
        stopSelf(startId)
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    inFlight.set(false) // defensive: never leave the latch stuck if torn down mid-run
    scope.cancel() // cancels the decode if the service is torn down (releases the engine lock)
    super.onDestroy()
  }

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text)) }
      .onFailure { Log.w(TAG, "failed to copy share result to clipboard", it) }
  }

  /** Posts the result; silently no-ops if POST_NOTIFICATIONS isn't granted (runtime perm is API 33+). */
  private fun postResult(title: String, text: String) {
    val ctx = applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "POST_NOTIFICATIONS not granted; share result not shown")
      return
    }
    val notification =
      NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(R.drawable.ic_relais_tile)
        .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // shade content stays off the lock screen
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(ctx).notify(RESULT_NOTIFICATION_ID, notification) }
      .onFailure { Log.w(TAG, "failed to post share result", it) }
  }

  private fun buildProgress(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Relais · working")
      .setContentText("Processing shared text on-device…")
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
        NotificationChannel(CHANNEL_ID, "Relais share results", NotificationManager.IMPORTANCE_DEFAULT)
          .apply { description = "Results from sharing text into the Relais on-device node" }
      )
    }
  }

  companion object {
    // Process-global single-flight: one share decode at a time, so a malicious app looping the
    // exported trampoline can't stack 30-120s decodes on the engine lock (the tile/widget paths get
    // the same coalescing via enqueueUniqueWork(KEEP)).
    private val inFlight = AtomicBoolean(false)

    /** Builds the start intent carrying [payload] for the share run. */
    fun startIntent(context: Context, payload: String): Intent =
      Intent(context, RelaisShareService::class.java).putExtra(EXTRA_PAYLOAD, payload)
  }
}
