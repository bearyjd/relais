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

package cc.grepon.relais.chat

import cc.grepon.relais.data.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the share/export Markdown payload (#146's share-and-export item, chat depth
 * Task 8). This function shipped untested; the acceptance criteria in #146 name the exact properties
 * asserted below — title, roles, preserved code fences, and `[image]`/`[audio]` placeholders.
 *
 * The SAF/file-picker and system-share halves of that item are genuinely tap-gated and are NOT
 * covered here — only the payload those flows carry.
 */
class ConversationMarkdownTest {

  private fun turn(
    role: String,
    content: String,
    attachmentType: String? = null,
    id: String = "t-$role-${content.hashCode()}",
  ) =
    ChatTurn(
      id = id,
      conversationId = "conv",
      role = role,
      content = content,
      attachmentType = attachmentType,
      attachmentPath = if (attachmentType != null) "/data/x.bin" else null,
      answeredByModelId = if (role == "assistant") "gemma-4-E2B" else null,
      answeredByBackend = if (role == "assistant") "TPU_LITERTLM" else null,
      createdAt = 1L,
    )

  @Test fun `title becomes a top-level heading`() {
    val md = conversationToMarkdown("My chat", emptyList())
    assertTrue(md, md.startsWith("# My chat"))
  }

  @Test fun `roles are labelled and separated by a blank line`() {
    val md =
      conversationToMarkdown(
        "T",
        listOf(turn("user", "hello"), turn("assistant", "hi there")),
      )
    assertEquals("# T\n\n**User:** hello\n\n**Assistant:** hi there", md)
  }

  @Test fun `an empty conversation still renders a usable document`() {
    // Export of a brand-new conversation must not produce a dangling body or crash.
    assertEquals("# Empty\n\n", conversationToMarkdown("Empty", emptyList()))
  }

  @Test fun `code fences are preserved verbatim`() {
    val content = "Run:\n\n```bash\ncurl -s localhost:8080/health\n```"
    val md = conversationToMarkdown("T", listOf(turn("assistant", content)))
    assertTrue(md, md.contains("```bash\ncurl -s localhost:8080/health\n```"))
  }

  @Test fun `image attachments render a placeholder`() {
    val md = conversationToMarkdown("T", listOf(turn("user", "what is this", "image")))
    assertEquals("# T\n\n**User:** what is this [image]", md)
  }

  @Test fun `audio attachments render a placeholder`() {
    val md = conversationToMarkdown("T", listOf(turn("user", "transcribe", "audio")))
    assertEquals("# T\n\n**User:** transcribe [audio]", md)
  }

  @Test fun `turns without attachments get no placeholder`() {
    val md = conversationToMarkdown("T", listOf(turn("user", "plain")))
    assertFalse(md, md.contains("["))
  }

  @Test fun `engine metadata is deliberately excluded from the export`() {
    // The on-screen per-turn readout shows model/backend; the shared document should not — it is a
    // transcript, not a benchmark artifact.
    val md = conversationToMarkdown("T", listOf(turn("assistant", "reply")))
    assertFalse(md, md.contains("TPU_LITERTLM"))
    assertFalse(md, md.contains("gemma-4-E2B"))
  }

  @Test fun `any non-assistant role renders as User`() {
    // Only "assistant" is special-cased; a system/tool turn must not silently vanish.
    val md = conversationToMarkdown("T", listOf(turn("system", "be terse")))
    assertTrue(md, md.contains("**User:** be terse"))
  }

  @Test fun `turn order is preserved`() {
    val md =
      conversationToMarkdown(
        "T",
        listOf(turn("user", "first"), turn("assistant", "second"), turn("user", "third")),
      )
    assertTrue(md.indexOf("first") < md.indexOf("second"))
    assertTrue(md.indexOf("second") < md.indexOf("third"))
  }
}
