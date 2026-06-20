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
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System notification listener for on-device triage. The system bind grant (Notification Access) is
 * the broad permission; this service applies the privacy gate on top of it — it only ever buffers a
 * notification that passes [TriageGate] (triage enabled AND the source package explicitly
 * allowlisted), and it never runs inference inline.
 *
 * Captured content lives only in the in-memory [NotificationTriageBuffer]; it is never persisted and
 * never sent over the LAN endpoint. When the user revokes Notification Access the system unbinds this
 * service — the authoritative kill switch.
 */
class RelaisNotificationListenerService : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    val ctx = applicationContext
    if (TriageConfig.enabled(ctx)) {
      TriageNotifications.ensureChannels(ctx)
      TriageDigestWorker.ensureScheduled(ctx)
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    val notif = sbn?.notification ?: return
    val ctx = applicationContext
    if (!TriageConfig.enabled(ctx)) return

    val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
    val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
    if (
      !TriageGate.shouldProcess(
        enabled = true,
        allowlist = TriageConfig.allowlist(ctx),
        ownPackage = packageName,
        pkg = sbn.packageName,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
      )
    ) {
      return
    }

    val extras = notif.extras ?: return
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    if (title.isBlank() && text.isBlank()) return

    NotificationTriageBuffer.offer(
      TriageRecord(
        key = sbn.key,
        pkg = sbn.packageName,
        title = TriagePromptBuilder.cap(title, TriagePromptBuilder.MAX_TITLE),
        text = TriagePromptBuilder.cap(text, TriagePromptBuilder.MAX_TEXT),
        postedAt = sbn.postTime,
      )
    )

    TriageDigestWorker.ensureScheduled(ctx)
    if (TriageConfig.urgentEnabled(ctx)) TriageUrgentWorker.kickThrottled(ctx)
  }
}
