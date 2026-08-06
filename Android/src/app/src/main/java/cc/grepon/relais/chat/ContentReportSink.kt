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
 * Returns true when the row was written. Callers must surface a false — a report that silently
 * fails to save leaves the operator believing a flag was recorded when it wasn't.
 *
 * There is no network path here, by design. See `docs/store-submission.md` gate 1: the opt-in
 * delivery to the maintainer is a separate, user-initiated step that has yet to be built, and
 * adding it will change the Data Safety declaration.
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
