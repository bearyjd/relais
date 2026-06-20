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

package cc.grepon.relais.triage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import cc.grepon.relais.R

/**
 * Builds and posts the two triage notification surfaces. Both channels are
 * [Notification.VISIBILITY_PRIVATE] so model output (which can echo sensitive notification content)
 * is hidden on the lockscreen. Posting is a no-op when notifications are disabled — a missing
 * POST_NOTIFICATIONS grant degrades the urgent/digest surface, it never crashes the worker.
 */
object TriageNotifications {
  const val CHANNEL_DIGEST = "relais_triage_digest"
  const val CHANNEL_URGENT = "relais_triage_urgent"
  private const val GROUP_KEY = "relais_triage"
  private const val DIGEST_NOTIF_ID = 7301
  private const val URGENT_BASE_ID = 7400

  fun ensureChannels(context: Context) {
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return
    mgr.createNotificationChannel(
      NotificationChannel(CHANNEL_DIGEST, "Triage digest", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Periodic on-device summary of your notifications."
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      }
    )
    mgr.createNotificationChannel(
      NotificationChannel(CHANNEL_URGENT, "Triage — urgent", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Items the on-device model flagged as urgent."
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      }
    )
  }

  private fun canPost(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  fun postDigest(context: Context, digest: TriageDigest) {
    if (!canPost(context)) return
    ensureChannels(context)
    val plural = if (digest.itemCount == 1) "" else "s"
    val notification =
      Notification.Builder(context, CHANNEL_DIGEST)
        .setContentTitle("Relais · ${digest.itemCount} notification$plural")
        .setContentText(digest.summary.lineSequence().firstOrNull().orEmpty())
        .setStyle(Notification.BigTextStyle().bigText(digest.summary))
        .setSmallIcon(R.drawable.relais_icon_foreground)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setGroup(GROUP_KEY)
        .setAutoCancel(true)
        .build()
    runCatching {
      context.getSystemService(NotificationManager::class.java)?.notify(DIGEST_NOTIF_ID, notification)
    }
  }

  fun postUrgent(context: Context, records: List<TriageRecord>) {
    if (!canPost(context)) return
    ensureChannels(context)
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return
    records.forEach { r ->
      val title = r.title.ifBlank { r.pkg }
      val notification =
        Notification.Builder(context, CHANNEL_URGENT)
          .setContentTitle("Urgent · $title")
          .setContentText(r.text)
          .setStyle(Notification.BigTextStyle().bigText(r.text))
          .setSmallIcon(R.drawable.relais_icon_foreground)
          .setVisibility(Notification.VISIBILITY_PRIVATE)
          .setGroup(GROUP_KEY)
          .setAutoCancel(true)
          .build()
      // Per-key id so distinct urgent items don't overwrite each other (collisions are benign).
      runCatching { mgr.notify(URGENT_BASE_ID + (r.key.hashCode() and 0xFFFF), notification) }
    }
  }
}
