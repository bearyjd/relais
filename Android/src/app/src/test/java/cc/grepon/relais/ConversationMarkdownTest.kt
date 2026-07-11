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

import cc.grepon.relais.chat.conversationToMarkdown
import cc.grepon.relais.data.ChatTurn
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [conversationToMarkdown] — the chat-transcript share/export serializer. */
class ConversationMarkdownTest {
  @Test
  fun rendersTitleRolesAndAttachmentPlaceholders() {
    val md =
      conversationToMarkdown(
        "My chat",
        listOf(
          ChatTurn("t1", "c", "user", "hello", null, null, null, null, 1L),
          ChatTurn("t2", "c", "assistant", "hi\n\n```kt\nval x=1\n```", null, null, "m", "TPU_LITERTLM", 2L),
          ChatTurn("t3", "c", "user", "see this", "image", "/x.png", null, null, 3L),
        ),
      )
    assertTrue(md.startsWith("# My chat"))
    assertTrue(md.contains("**User:** hello"))
    assertTrue(md.contains("**Assistant:** hi"))
    assertTrue(md.contains("```kt")) // code fence preserved
    assertTrue(md.contains("[image]")) // attachment placeholder
  }
}
