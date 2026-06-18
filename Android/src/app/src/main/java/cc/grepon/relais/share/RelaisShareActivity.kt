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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
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
private const val NOTIF_PERM_REQUEST = 0x53 // 'S'

/**
 * Transparent trampoline (#1, #13): registered for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of
 * `text/plain` AND any image type. Requests POST_NOTIFICATIONS on API 33+, then:
 *  - **text** → [extractSharedText] → [shouldRunShare] → start [RelaisShareService] with the payload.
 *  - **image** (#13 OCR) → collect the `content://` image URIs → [shouldStartImageShare] → start the
 *    service with the URIs (granting it read access); the service OCRs them off this lifecycle, since
 *    OCR is async and the trampoline finishes immediately.
 * For both, RUN starts the foreground service (the long work runs there); DISABLED / NODE_OFF / EMPTY
 * post a short status notification and never start the node or inference (the cold-start guard).
 * Either way it [finish]es immediately — no window is ever shown.
 */
class RelaisShareActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Ask for notification permission up front (API 33+). Fire-and-forget: we finish immediately, so
    // the grant lands for the NEXT share; the service/status posts already no-op without it.
    requestNotificationPermissionIfNeeded()

    if (intent?.type?.startsWith("image/") == true) handleImageShare() else handleTextShare()
    finish()
  }

  private fun handleTextShare() {
    val payload =
      extractSharedText(
        text = readText(),
        subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
        extraTexts = readTextList(),
        maxChars = MAX_SHARE_CHARS,
      )
    when (shouldRunShare(RelaisConfig.shareEnabled(this), RelaisInference.isReady(), payload)) {
      // payload is non-blank here (shouldRunShare guarantees it for RUN); fall back defensively to "".
      ShareDecision.RUN -> launchShareService(RelaisShareService.startIntent(this, payload.orEmpty()))
      ShareDecision.DISABLED -> postStatus("Share target disabled", "Enable it in the Relais control panel.")
      ShareDecision.NODE_OFF -> postStatus("Relais node not running", "Start the node first, then share again.")
      ShareDecision.EMPTY -> postStatus("Nothing to summarize", "The share had no usable text.")
    }
  }

  /** #13 OCR: an image was shared. Hand the URIs to the service (which OCRs + infers off this lifecycle). */
  private fun handleImageShare() {
    val uris = readImageUris()
    val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
    // Some apps attach a caption (EXTRA_TEXT) alongside the image — carry it so it prefixes the OCR text.
    val caption = readText()
    when (shouldStartImageShare(RelaisConfig.shareEnabled(this), RelaisInference.isReady(), uris.isNotEmpty())) {
      ShareDecision.RUN -> launchShareService(RelaisShareService.imageIntent(this, uris, subject, caption))
      ShareDecision.DISABLED -> postStatus("Share target disabled", "Enable it in the Relais control panel.")
      ShareDecision.NODE_OFF -> postStatus("Relais node not running", "Start the node first, then share again.")
      ShareDecision.EMPTY -> postStatus("Nothing to read", "The share had no image.")
    }
  }

  private fun readText(): String? = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

  private fun readTextList(): List<String>? =
    intent?.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }

  /**
   * The shared image URIs, restricted to `content://` (never a `file://` path — a hostile sharer could
   * point that at an arbitrary readable file; only a content provider URI carries a real grant anyway).
   */
  private fun readImageUris(): List<Uri> {
    val i = intent ?: return emptyList()
    // runCatching: this activity is exported, so a hostile app can send a malformed EXTRA_STREAM whose
    // unmarshalling throws BadParcelableException — never crash the trampoline on bad input.
    val raw =
      runCatching {
        when (i.action) {
          Intent.ACTION_SEND -> listOfNotNull(parcelable(i, Intent.EXTRA_STREAM))
          Intent.ACTION_SEND_MULTIPLE -> parcelableList(i, Intent.EXTRA_STREAM)
          else -> emptyList()
        }
      }.getOrDefault(emptyList())
    // content:// only (never a file:// path a hostile sharer could aim at an arbitrary readable file),
    // and capped so a huge SEND_MULTIPLE list can't drive unbounded OCR/grant work.
    return raw.filter { it.scheme == "content" }.take(MAX_SHARE_IMAGES)
  }

  @Suppress("DEPRECATION")
  private fun parcelable(i: Intent, key: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i.getParcelableExtra(key, Uri::class.java)
    else i.getParcelableExtra(key) as? Uri

  @Suppress("DEPRECATION")
  private fun parcelableList(i: Intent, key: String): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      i.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
    } else {
      i.getParcelableArrayListExtra<Parcelable>(key).orEmpty().filterIsInstance<Uri>()
    }

  private fun launchShareService(svc: Intent) {
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
