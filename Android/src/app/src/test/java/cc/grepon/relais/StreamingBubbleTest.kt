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

import cc.grepon.relais.chat.shouldShowStreamingBubble
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBubbleTest {

  @Test
  fun `streaming with text and no pending id shows the bubble`() {
    assertTrue(
      shouldShowStreamingBubble(
        streaming = true,
        streamingText = "hello",
        lastTurnId = "t1",
        pendingPersistedTurnId = null,
      )
    )
  }

  @Test
  fun `last turn id matching the pending persisted id hides the bubble (hand-off)`() {
    assertFalse(
      shouldShowStreamingBubble(
        streaming = true,
        streamingText = "hello",
        lastTurnId = "t2",
        pendingPersistedTurnId = "t2",
      )
    )
  }

  @Test
  fun `identical content but different last-turn id still shows the bubble`() {
    // This is the bug the id-based guard fixes: two consecutive assistant turns with the same
    // content must not mis-suppress the bubble for a turn that isn't actually the hand-off target.
    assertTrue(
      shouldShowStreamingBubble(
        streaming = true,
        streamingText = "hello",
        lastTurnId = "t1",
        pendingPersistedTurnId = "t2",
      )
    )
  }

  @Test
  fun `not streaming hides the bubble`() {
    assertFalse(
      shouldShowStreamingBubble(
        streaming = false,
        streamingText = "hello",
        lastTurnId = "t1",
        pendingPersistedTurnId = null,
      )
    )
  }

  @Test
  fun `empty streaming text hides the bubble`() {
    assertFalse(
      shouldShowStreamingBubble(
        streaming = true,
        streamingText = "",
        lastTurnId = "t1",
        pendingPersistedTurnId = null,
      )
    )
  }
}
