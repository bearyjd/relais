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
  fun `empty and single-turn inputs yield empty history`() {
    assertEquals(emptyList<Any>(), historyForRequest(emptyList()))
    assertEquals(emptyList<Any>(), historyForRequest(listOf(ChatTurn("t", "c", "user", "x", null, null, null, null, 1L))))
  }
}
