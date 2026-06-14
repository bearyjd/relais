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
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.grepon.relais.R
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.core.RelaisInference

private const val TAG = "RelaisShareActivity"
private const val CHANNEL_ID = "relais_share_status"
private const val STATUS_NOTIFICATION_ID = 0x52454C55 // "RELU" — share-status slot, separate from results
private const val MAX_SHARE_CHARS = 16_000 // cap the prompt; on-device context windows are finite
private const val NOTIF_PERM_REQUEST = 0x53 // 'S'

/**
 * Transparent trampoline (#1): registered for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` `text/plain`.
 * Extracts the shared text via [extractSharedText], requests POST_NOTIFICATIONS on API 33+, then
 * decides via [shouldRunShare]:
 *  - RUN → start [RelaisShareService] (foreground) with the payload — the long decode runs there.
 *  - DISABLED / NODE_OFF / EMPTY → post a short status notification explaining why; never starts the
 *    node or inference (the cold-start guard: a share when the node is off NEVER cold-starts it).
 * Either way it [finish]es immediately — it's a trampoline, no window is ever shown.
 */
class RelaisShareActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val payload =
      extractSharedText(
        text = readText(),
        subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
        extraTexts = readTextList(),
        maxChars = MAX_SHARE_CHARS,
      )

    // Ask for notification permission up front (API 33+). Fire-and-forget: we finish immediately, so
    // the grant lands for the NEXT share; the service/status posts already no-op without it.
    requestNotificationPermissionIfNeeded()

    when (shouldRunShare(RelaisConfig.shareEnabled(this), RelaisInference.isReady(), payload)) {
      // payload is non-blank here (shouldRunShare guarantees it for RUN); fall back defensively to "".
      ShareDecision.RUN -> startShareService(payload.orEmpty())
      ShareDecision.DISABLED -> postStatus("Share target disabled", "Enable it in the Relais control panel.")
      ShareDecision.NODE_OFF -> postStatus("Relais node not running", "Start the node first, then share again.")
      ShareDecision.EMPTY -> postStatus("Nothing to summarize", "The share had no usable text.")
    }
    finish()
  }

  private fun readText(): String? = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

  private fun readTextList(): List<String>? =
    intent?.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }

  private fun startShareService(payload: String) {
    val svc = RelaisShareService.startIntent(this, payload)
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }.onFailure {
      // Android 12+ can reject a background FGS start (ForegroundServiceStartNotAllowedException);
      // never crash the trampoline — fall back to a status notification.
      Log.w(TAG, "failed to start share service", it)
      postStatus("Couldn't start", "Try sharing again with the Relais app open.")
    }
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) return
    runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERM_REQUEST) }
      .onFailure { Log.w(TAG, "failed to request POST_NOTIFICATIONS", it) }
  }

  /** Short status note for the non-RUN outcomes; no-ops if POST_NOTIFICATIONS isn't granted (API 33+). */
  private fun postStatus(title: String, text: String) {
    val ctx = applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "POST_NOTIFICATIONS not granted; share status not shown: $title")
      return
    }
    ensureChannel(ctx)
    val notification =
      NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle("Relais · $title")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_relais_tile)
        .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(ctx).notify(STATUS_NOTIFICATION_ID, notification) }
      .onFailure { Log.w(TAG, "failed to post share status", it) }
  }

  private fun ensureChannel(ctx: Context) {
    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Relais share status", NotificationManager.IMPORTANCE_DEFAULT)
          .apply { description = "Status when a share into the Relais node can't run" }
      )
    }
  }
}
