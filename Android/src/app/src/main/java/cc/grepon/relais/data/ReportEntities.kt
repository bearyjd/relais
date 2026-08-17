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

import androidx.room.ColumnInfo
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
 * renaming the enum cannot silently reinterpret existing rows. [sendState] follows the same rule for
 * the same reason.
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
  /**
   * Where this row's opt-in delivery stands (#273) — one of [ReportSendState].
   *
   * Defaults to [ReportSendState.NONE] both here and in the v6->v7 migration's `ADD COLUMN`, so every
   * row written before #273 reads back as "the operator never asked for this to be sent" rather than
   * as an undelivered backlog the retry worker would then try to flush to the Worker.
   */
  @ColumnInfo(defaultValue = ReportSendState.NONE) val sendState: String = ReportSendState.NONE,
  /**
   * How many *counted* delivery attempts this row has spent (see `dispositionFor` — a 429 is not
   * counted). Bounded by `MAX_SEND_ATTEMPTS` for automatic retries only; a manual SEND ignores it.
   */
  @ColumnInfo(defaultValue = "0") val sendAttempts: Int = 0,
  /** When the last delivery attempt resolved, for the review screen. Null until one has. */
  val lastAttemptAt: Long? = null,
)

/**
 * Where a report's opt-in delivery stands (stored as the `sendState` TEXT column, #273).
 *
 * Stored as these string constants rather than an enum ordinal so a future reordering cannot
 * reinterpret existing rows — the same rule `reasonId` follows.
 */
object ReportSendState {
  /** The operator did not opt in to sending. Terminal, and the default for every pre-#273 row. */
  const val NONE = "none"

  /** Opted in and not yet delivered — either mid-flight or waiting on a scheduled retry. */
  const val PENDING = "pending"

  /** Delivered; the Worker answered 2xx. Terminal. */
  const val SENT = "sent"

  /**
   * Automatic delivery gave up — a permanent rejection, or `MAX_SEND_ATTEMPTS` exhausted. Terminal
   * for the retry worker, but still manually re-sendable from `CONFIGURE › REPORTED OUTPUT`, which
   * is the whole point of #273: no send is unrecoverable.
   */
  const val FAILED = "failed"
}

/** Which in-app surface the reported output was displayed on (stored as the `surface` TEXT column). */
object ReportSurface {
  /** The Relais in-app chat (`chat/ChatMessageList`). */
  const val CHAT = "chat"

  /** The inherited Gallery chat stack (`ui/llmchat/LlmChatScreen`, incl. agent chat). */
  const val GALLERY_CHAT = "gallery_chat"
}
