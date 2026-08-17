/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais.chat

import android.content.Context
import cc.grepon.relais.data.ContentReport
import cc.grepon.relais.data.RelaisDatabase

/**
 * The single write path for AI-content reports (#258), shared by the two chat stacks: the Relais
 * chat (via `ChatViewModel`) and the inherited Gallery chat (via `ChatPanel`, which has no view
 * model of its own). One function so the two surfaces cannot drift on what a report stores.
 *
 * That second caller was missing for the whole of this feature's first review pass — this KDoc
 * described it, `ReportSurface.GALLERY_CHAT` existed for it, and the commit extracting "the single
 * write path" was written for it, but only the Relais surface ever called here. Output from the
 * Gallery/agent chat was simply unreportable, which is the one thing Play's AI-Generated Content
 * policy asks for. **If a third surface can render model output, it calls this too.**
 *
 * Returns true when the row was written. Callers must surface a false — a report that silently
 * fails to save leaves the operator believing a flag was recorded when it wasn't.
 *
 * This function never touches the network, by design — it stays the single, always-runs local write
 * regardless of whether the operator also opts in to sending. See [ContentReportDelivery] for that
 * separate, explicit, per-report step (#258 gate 1), and `docs/store-submission.md` gate 1 for why it
 * changes the Data Safety declaration from "collects nothing" to optional collection.
 */
suspend fun persistContentReport(
  context: Context,
  draft: ContentReportDraft,
  surface: String,
  nowMs: Long,
): Boolean =
  runCatching {
      RelaisDatabase.get(context)
        .reportDao()
        .insert(
          ContentReport(
            reasonId = draft.reasonId,
            excerpt = draft.excerpt,
            note = draft.note,
            modelId = draft.modelId,
            backend = draft.backend,
            surface = surface,
            createdAt = nowMs,
          )
        )
    }
    .isSuccess
