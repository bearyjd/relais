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

package cc.grepon.relais

import cc.grepon.relais.share.ShareDecision
import cc.grepon.relais.share.extractSharedText
import cc.grepon.relais.share.shouldRunShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the share-sheet target (#1). [extractSharedText] builds the prompt from an
 * ACTION_SEND / ACTION_SEND_MULTIPLE payload; [shouldRunShare] is the cold-start guard in pure form
 * (RUN requires `ready`, so a share can never cold-start the engine).
 */
class SharePayloadTest {

  private val cap = 16_000

  // ---- extractSharedText ----

  @Test fun `plain text passes through trimmed`() {
    assertEquals("hello world", extractSharedText("  hello world  ", subject = null, extraTexts = null, maxChars = cap))
  }

  @Test fun `subject is prefixed to body`() {
    assertEquals(
      "Re: launch\n\nthe body text",
      extractSharedText("the body text", subject = "Re: launch", extraTexts = null, maxChars = cap),
    )
  }

  @Test fun `blank subject is dropped — no stray prefix`() {
    assertEquals("body only", extractSharedText("body only", subject = "   ", extraTexts = null, maxChars = cap))
  }

  @Test fun `subject equal to body is not duplicated`() {
    // Some apps put the same string in EXTRA_SUBJECT and EXTRA_TEXT; don't echo it twice.
    assertEquals("same line", extractSharedText("same line", subject = "same line", extraTexts = null, maxChars = cap))
  }

  @Test fun `SEND_MULTIPLE joins non-blank entries with blank lines`() {
    assertEquals(
      "first\n\nsecond\n\nthird",
      extractSharedText(text = null, subject = null, extraTexts = listOf("first", "  ", "second", "third"), maxChars = cap),
    )
  }

  @Test fun `SEND_MULTIPLE with subject prefixes the subject once`() {
    assertEquals(
      "Subj\n\nfirst\n\nsecond",
      extractSharedText(text = null, subject = "Subj", extraTexts = listOf("first", "second"), maxChars = cap),
    )
  }

  @Test fun `single text takes precedence over the multiple list`() {
    // ACTION_SEND populates EXTRA_TEXT (a CharSequence); prefer it when both happen to be present.
    assertEquals(
      "single",
      extractSharedText(text = "single", subject = null, extraTexts = listOf("listed"), maxChars = cap),
    )
  }

  @Test fun `null everything returns null`() {
    assertNull(extractSharedText(text = null, subject = null, extraTexts = null, maxChars = cap))
  }

  @Test fun `blank text returns null`() {
    assertNull(extractSharedText(text = "   \n  ", subject = null, extraTexts = null, maxChars = cap))
  }

  @Test fun `all-blank list returns null`() {
    assertNull(extractSharedText(text = null, subject = " ", extraTexts = listOf("", "  ", "\n"), maxChars = cap))
  }

  @Test fun `empty list returns null`() {
    assertNull(extractSharedText(text = null, subject = null, extraTexts = emptyList(), maxChars = cap))
  }

  @Test fun `over-cap text is truncated to maxChars`() {
    val big = "x".repeat(20_000)
    val out = extractSharedText(big, subject = null, extraTexts = null, maxChars = cap)
    assertEquals(cap, out?.length)
    assertEquals("x".repeat(cap), out)
  }

  @Test fun `cap counts the subject prefix too`() {
    val body = "y".repeat(20_000)
    val out = extractSharedText(body, subject = "S", extraTexts = null, maxChars = 10)
    assertEquals(10, out?.length)
    assertEquals("S\n\nyyyyyyy", out) // "S\n\n" (3) + 7 body chars = 10
  }

  @Test fun `non-positive cap returns null`() {
    assertNull(extractSharedText("anything", subject = null, extraTexts = null, maxChars = 0))
    assertNull(extractSharedText("anything", subject = null, extraTexts = null, maxChars = -5))
  }

  // ---- shouldRunShare (cold-start guard in pure form) ----

  @Test fun `RUN only when enabled and ready and non-blank`() {
    assertEquals(ShareDecision.RUN, shouldRunShare(shareEnabled = true, ready = true, payload = "summarize this"))
  }

  @Test fun `DISABLED when the share target is off — even when ready with a payload`() {
    assertEquals(ShareDecision.DISABLED, shouldRunShare(shareEnabled = false, ready = true, payload = "text"))
  }

  @Test fun `NODE_OFF when not ready — the cold-start guard, never RUN`() {
    assertEquals(ShareDecision.NODE_OFF, shouldRunShare(shareEnabled = true, ready = false, payload = "text"))
  }

  @Test fun `EMPTY when enabled and ready but payload is null or blank`() {
    assertEquals(ShareDecision.EMPTY, shouldRunShare(shareEnabled = true, ready = true, payload = null))
    assertEquals(ShareDecision.EMPTY, shouldRunShare(shareEnabled = true, ready = true, payload = "   "))
  }

  @Test fun `DISABLED takes precedence over node state and payload`() {
    // Disabled is the operator's explicit off switch; report it regardless of readiness/payload.
    assertEquals(ShareDecision.DISABLED, shouldRunShare(shareEnabled = false, ready = false, payload = null))
  }

  @Test fun `NODE_OFF takes precedence over an empty payload`() {
    // Node-down is the more actionable message ("start it first") than "nothing to summarize".
    assertEquals(ShareDecision.NODE_OFF, shouldRunShare(shareEnabled = true, ready = false, payload = null))
  }
}
