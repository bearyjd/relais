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

import cc.grepon.relais.chat.parseSseContentDelta
import cc.grepon.relais.chat.parseSseFinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parses one SSE line of an OpenAI-compatible `/v1/chat/completions` stream. */
class ChatSseTest {
  @Test
  fun extractsContentDelta() {
    assertEquals(
      "Hi",
      parseSseContentDelta("""data: {"choices":[{"delta":{"content":"Hi"}}]}"""))
  }

  @Test
  fun doneSentinelIsNull() {
    assertNull(parseSseContentDelta("data: [DONE]"))
  }

  @Test
  fun reasoningDeltaIsNull() {
    assertNull(
      parseSseContentDelta("""data: {"choices":[{"delta":{"reasoning_content":"x"}}]}"""))
  }

  @Test
  fun nonDataLineIsNull() {
    assertNull(parseSseContentDelta(": comment"))
  }

  @Test
  fun extractsFinishReason() {
    assertEquals(
      "stop",
      parseSseFinishReason(
        """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
  }

  @Test
  fun finishReasonDoneSentinelIsNull() {
    assertNull(parseSseFinishReason("data: [DONE]"))
  }

  @Test
  fun finishReasonContentOnlyDeltaIsNull() {
    assertNull(
      parseSseFinishReason(
        """data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}"""))
  }

  @Test
  fun finishReasonNonDataLineIsNull() {
    assertNull(parseSseFinishReason(": comment"))
  }
}
