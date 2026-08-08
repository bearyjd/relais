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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure shaping/validation for an AI-content report (#258). Device-free by construction — the report
 * is built here and only then handed to Room, so the rules are testable without a Context.
 */
class ContentReportShapingTest {

  private fun draftOf(result: ReportDraftResult): ContentReportDraft {
    assertTrue("expected a Valid draft, got $result", result is ReportDraftResult.Valid)
    return (result as ReportDraftResult.Valid).draft
  }

  @Test
  fun `blank content is rejected — there is nothing to report`() {
    val result = buildContentReportDraft(ReportReason.HARMFUL, "   \n\t ", null, null, null)
    assertEquals(
      ReportDraftResult.Rejected(ReportRejection.EMPTY_CONTENT),
      result,
    )
  }

  @Test
  fun `an over-long note is rejected rather than silently truncated`() {
    val note = "x".repeat(MAX_REPORT_NOTE_CHARS + 1)
    val result = buildContentReportDraft(ReportReason.OTHER, "output", note, null, null)
    assertEquals(
      ReportDraftResult.Rejected(ReportRejection.NOTE_TOO_LONG),
      result,
    )
  }

  @Test
  fun `a note of exactly the maximum length is accepted`() {
    val note = "x".repeat(MAX_REPORT_NOTE_CHARS)
    val draft = draftOf(buildContentReportDraft(ReportReason.OTHER, "output", note, null, null))
    assertEquals(note, draft.note)
  }

  @Test
  fun `a blank note normalizes to null, so empty and absent are one state`() {
    val draft = draftOf(buildContentReportDraft(ReportReason.HATE, "output", "  ", null, null))
    assertNull(draft.note)
  }

  @Test
  fun `a note is trimmed`() {
    val draft = draftOf(buildContentReportDraft(ReportReason.HATE, "output", "  bad  ", null, null))
    assertEquals("bad", draft.note)
  }

  @Test
  fun `an over-long excerpt is truncated to the cap, ellipsis included`() {
    val content = "y".repeat(MAX_REPORT_EXCERPT_CHARS + 500)
    val draft = draftOf(buildContentReportDraft(ReportReason.VIOLENT, content, null, null, null))
    assertEquals(MAX_REPORT_EXCERPT_CHARS, draft.excerpt.length)
    assertTrue("truncated excerpt should be marked", draft.excerpt.endsWith("…"))
  }

  @Test
  fun `an excerpt at exactly the cap is kept verbatim, unmarked`() {
    val content = "y".repeat(MAX_REPORT_EXCERPT_CHARS)
    val draft = draftOf(buildContentReportDraft(ReportReason.VIOLENT, content, null, null, null))
    assertEquals(content, draft.excerpt)
  }

  @Test
  fun `content is preserved verbatim — leading whitespace is not stripped from the evidence`() {
    val draft = draftOf(buildContentReportDraft(ReportReason.SEXUAL, "  spaced  ", null, null, null))
    assertEquals("  spaced  ", draft.excerpt)
  }

  @Test
  fun `provenance is carried so a report identifies which model produced the output`() {
    val draft =
      draftOf(
        buildContentReportDraft(
          ReportReason.MISINFORMATION,
          "output",
          null,
          modelId = "gemma-4-E2B",
          backend = "litertlm",
        )
      )
    assertEquals("misinformation", draft.reasonId)
    assertEquals("gemma-4-E2B", draft.modelId)
    assertEquals("litertlm", draft.backend)
  }

  @Test
  fun `every reason has a stable, distinct id — ids are persisted, so they cannot collide`() {
    val ids = ReportReason.entries.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    assertTrue("ids are persisted verbatim", ids.none { it.isBlank() })
  }

  // The receiver rejects a blank or over-200 modelId/backend outright (`isBoundedString` in
  // report-worker/src/index.ts requires length > 0). Anything this builder emits has to survive that,
  // or the report saves on-device and 400s the moment a delivery path exists.
  private fun draftWith(modelId: String?, backend: String?) =
    draftOf(buildContentReportDraft(ReportReason.OTHER, "output", null, modelId, backend))

  @Test
  fun `an over-long model id is dropped, not truncated — a cut identifier is a wrong one`() {
    val draft = draftWith("m".repeat(MAX_REPORT_IDENT_CHARS + 1), "GPU")
    assertNull(draft.modelId)
    assertEquals("GPU", draft.backend)
  }

  @Test
  fun `an identifier at exactly the cap is kept`() {
    val exact = "m".repeat(MAX_REPORT_IDENT_CHARS)
    assertEquals(exact, draftWith(exact, null).modelId)
  }

  @Test
  fun `blank identifiers normalize to null — the receiver rejects empty strings too`() {
    val draft = draftWith("", "   ")
    assertNull(draft.modelId)
    assertNull(draft.backend)
  }

  @Test
  fun `surrounding whitespace is trimmed off identifiers rather than sent verbatim`() {
    assertEquals("gemma-4-E2B", draftWith("  gemma-4-E2B  ", null).modelId)
  }

  @Test
  fun `an unusable identifier never rejects the report — the flag matters, the label does not`() {
    val result =
      buildContentReportDraft(ReportReason.OTHER, "output", null, "x".repeat(9_999), "")
    assertTrue("report must survive bad provenance", result is ReportDraftResult.Valid)
  }
}
