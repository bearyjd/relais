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

/**
 * Pure shaping + validation for an AI-content report (#258).
 *
 * Google Play's AI-Generated Content policy requires an in-app way to report or flag offensive AI
 * output "without needing to exit the app". Relais has **no developer server**, so a report is
 * recorded on-device and reviewed by the operator — nothing is transmitted, which is what keeps the
 * Data Safety declaration ("collects nothing") true. See `docs/store-submission.md` gate 1.
 *
 * Everything here is device-free by construction: the draft is built and validated first, and only a
 * valid draft is handed to Room. That keeps the rules unit-testable without a Context, matching the
 * repo's pure-logic test convention.
 */

/**
 * Why the operator flagged a piece of output. [id] is **persisted verbatim** in the database, so it
 * must stay stable across releases even if [label] is reworded; the labels are what the picker shows,
 * in `DESIGN.md`'s monospace caps idiom.
 */
enum class ReportReason(val id: String, val label: String) {
  HARMFUL("harmful", "HARMFUL / DANGEROUS"),
  SEXUAL("sexual", "SEXUAL"),
  HATE("hate", "HATE / HARASSMENT"),
  VIOLENT("violent", "VIOLENT"),
  MISINFORMATION("misinformation", "MISLEADING"),
  OTHER("other", "OTHER"),
}

/** Cap on the operator's free-text note. Over-long notes are rejected, not silently truncated. */
const val MAX_REPORT_NOTE_CHARS = 500

/**
 * Cap on the stored excerpt of the reported output. A model turn can be arbitrarily long and the
 * report only needs to be enough to identify what went wrong — this bounds the row rather than
 * copying an unbounded blob into the database.
 */
const val MAX_REPORT_EXCERPT_CHARS = 2000

/** Marks an excerpt that was cut at [MAX_REPORT_EXCERPT_CHARS], so a reviewer knows it is partial. */
private const val TRUNCATION_MARK = "…"

/** A validated report, ready to persist. */
data class ContentReportDraft(
  val reasonId: String,
  val excerpt: String,
  val note: String?,
  val modelId: String?,
  val backend: String?,
)

/** Why a draft was refused. Each maps to a message the picker shows inline. */
enum class ReportRejection {
  EMPTY_CONTENT,
  NOTE_TOO_LONG,
}

/** Outcome of [buildContentReportDraft]. */
sealed interface ReportDraftResult {
  data class Valid(val draft: ContentReportDraft) : ReportDraftResult

  data class Rejected(val error: ReportRejection) : ReportDraftResult
}

/**
 * Validates and shapes one report.
 *
 * [content] is the model output being flagged and is preserved **verbatim** up to the cap — it is
 * evidence, so leading/trailing whitespace is not stripped the way it is from [note]. [modelId] and
 * [backend] carry provenance so a review answers "which model produced this", and are nullable
 * because a turn that failed before dispatch has neither.
 */
fun buildContentReportDraft(
  reason: ReportReason,
  content: String,
  note: String?,
  modelId: String?,
  backend: String?,
): ReportDraftResult {
  if (content.isBlank()) return ReportDraftResult.Rejected(ReportRejection.EMPTY_CONTENT)

  val trimmedNote = note?.trim().orEmpty()
  if (trimmedNote.length > MAX_REPORT_NOTE_CHARS) {
    return ReportDraftResult.Rejected(ReportRejection.NOTE_TOO_LONG)
  }

  val excerpt =
    if (content.length > MAX_REPORT_EXCERPT_CHARS) {
      content.take(MAX_REPORT_EXCERPT_CHARS - TRUNCATION_MARK.length) + TRUNCATION_MARK
    } else {
      content
    }

  return ReportDraftResult.Valid(
    ContentReportDraft(
      reasonId = reason.id,
      excerpt = excerpt,
      note = trimmedNote.ifEmpty { null },
      modelId = modelId,
      backend = backend,
    )
  )
}
