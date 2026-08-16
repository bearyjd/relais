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

package cc.grepon.relais.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One operator report of offensive AI-generated output (#258).
 *
 * Play's AI-Generated Content policy requires an in-app way to flag AI output and requires that
 * reports inform moderation. A report always **stays on this device** first: it is written here and
 * reviewed in the control panel. Whether it also reaches the developer is a separate, later,
 * per-report opt-in ([cc.grepon.relais.chat.ContentReportDelivery], #258 gate 1) — see
 * `docs/store-submission.md` gate 1 for the resulting Data Safety declaration.
 *
 * `reasonId` stores `ReportReason.id` verbatim rather than the enum ordinal, so reordering or
 * renaming the enum cannot silently reinterpret existing rows.
 */
@Entity(tableName = "content_reports", indices = [Index(value = ["createdAt"])])
data class ContentReport(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val reasonId: String,
  val excerpt: String,
  val note: String?,
  val modelId: String?,
  val backend: String?,
  val surface: String,
  val createdAt: Long,
)

/** Which in-app surface the reported output was displayed on (stored as the `surface` TEXT column). */
object ReportSurface {
  /** The Relais in-app chat (`chat/ChatMessageList`). */
  const val CHAT = "chat"

  /** The inherited Gallery chat stack (`ui/llmchat/LlmChatScreen`, incl. agent chat). */
  const val GALLERY_CHAT = "gallery_chat"
}
