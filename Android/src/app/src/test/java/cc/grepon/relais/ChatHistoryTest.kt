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

import cc.grepon.relais.chat.ERROR_BACKEND
import cc.grepon.relais.chat.historyForRequest
import cc.grepon.relais.data.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {

  @Test
  fun `history excludes the final live turn`() {
    val turns =
      listOf(
        ChatTurn("t1", "c", "user", "hi", null, null, null, null, 1L),
        ChatTurn("t2", "c", "assistant", "hello", null, null, "m", "GPU_LITERTLM", 2L),
        ChatTurn("t3", "c", "user", "how are you", null, null, null, null, 3L), // live turn
      )
    val h = historyForRequest(turns)
    assertEquals(listOf("hi", "hello"), h.map { it.text })
    assertEquals(listOf("user", "assistant"), h.map { it.role })
  }

  @Test
  fun `history drops synthetic error assistant turns`() {
    val turns =
      listOf(
        ChatTurn("t1", "c", "user", "hi", null, null, null, null, 1L),
        ChatTurn("t2", "c", "assistant", "[error] boom", null, null, "m", ERROR_BACKEND, 2L),
        ChatTurn("t3", "c", "user", "retry", null, null, null, null, 3L),
        ChatTurn("t4", "c", "assistant", "real answer", null, null, "m", "GPU_LITERTLM", 4L),
        ChatTurn("t5", "c", "user", "next", null, null, null, null, 5L), // live turn
      )
    val h = historyForRequest(turns)
    // The error turn is filtered; the live turn is dropped; the rest survive in order.
    assertEquals(listOf("hi", "retry", "real answer"), h.map { it.text })
    assertEquals(listOf("user", "user", "assistant"), h.map { it.role })
  }

  @Test
  fun `a user turn whose content starts with error bracket is kept`() {
    // Only assistant turns tagged ERROR_BACKEND are dropped — user text is never filtered on content.
    val turns =
      listOf(
        ChatTurn("t1", "c", "user", "[error] is a valid thing to type", null, null, null, null, 1L),
        ChatTurn("t2", "c", "assistant", "ok", null, null, "m", "GPU_LITERTLM", 2L),
        ChatTurn("t3", "c", "user", "live", null, null, null, null, 3L),
      )
    val h = historyForRequest(turns)
    assertEquals(listOf("[error] is a valid thing to type", "ok"), h.map { it.text })
  }

  @Test
  fun `empty and single-turn inputs yield empty history`() {
    assertEquals(emptyList<Any>(), historyForRequest(emptyList()))
    assertEquals(emptyList<Any>(), historyForRequest(listOf(ChatTurn("t", "c", "user", "x", null, null, null, null, 1L))))
  }
}
