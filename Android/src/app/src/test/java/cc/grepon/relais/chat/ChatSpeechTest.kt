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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the chat speech-playback state→label matrix (#211). */
class ChatSpeechTest {

  private val a = "turn-a"
  private val b = "turn-b"

  // ---- turnId() ----

  @Test fun `idle names no turn`() {
    assertNull(SpeechState.Idle.turnId())
  }

  @Test fun `every active state names its turn`() {
    assertEquals(a, SpeechState.Fetching(a).turnId())
    assertEquals(a, SpeechState.Preparing(a).turnId())
    assertEquals(a, SpeechState.Speaking(a).turnId())
    assertEquals(a, SpeechState.Failed(a, "x").turnId())
  }

  // ---- labels ----

  @Test fun `idle reads SPEAK`() {
    assertEquals("SPEAK", speechActionLabel(SpeechState.Idle, a))
  }

  @Test fun `the active turn reflects its own state`() {
    assertEquals("FETCHING VOICE", speechActionLabel(SpeechState.Fetching(a), a))
    assertEquals("SYNTHESIZING", speechActionLabel(SpeechState.Preparing(a), a))
    assertEquals("STOP", speechActionLabel(SpeechState.Speaking(a), a))
    assertEquals("SPEECH FAILED", speechActionLabel(SpeechState.Failed(a, "boom"), a))
  }

  @Test fun `an inactive turn always reads SPEAK even while another turn speaks`() {
    // Tapping it is legal — it supersedes the current playback.
    assertEquals("SPEAK", speechActionLabel(SpeechState.Speaking(a), b))
    assertEquals("SPEAK", speechActionLabel(SpeechState.Preparing(a), b))
    assertEquals("SPEAK", speechActionLabel(SpeechState.Failed(a, "boom"), b))
  }

  // ---- stop vs start ----

  @Test fun `only the actively-speaking turn stops`() {
    assertTrue(speechActionStops(SpeechState.Speaking(a), a))
    assertFalse(speechActionStops(SpeechState.Speaking(a), b))
    assertFalse(speechActionStops(SpeechState.Preparing(a), a))
    assertFalse(speechActionStops(SpeechState.Idle, a))
  }

  // ---- enablement ----

  @Test fun `in-flight synthesis and provisioning are status, not buttons`() {
    assertFalse(speechActionEnabled(SpeechState.Preparing(a), a))
    assertFalse(speechActionEnabled(SpeechState.Fetching(a), a))
  }

  @Test fun `speaking is tappable so it can be stopped`() {
    assertTrue(speechActionEnabled(SpeechState.Speaking(a), a))
  }

  @Test fun `a failed turn is tappable so it can be retried`() {
    assertTrue(speechActionEnabled(SpeechState.Failed(a, "boom"), a))
  }

  @Test fun `other turns stay tappable while one is busy`() {
    assertTrue(speechActionEnabled(SpeechState.Preparing(a), b))
    assertTrue(speechActionEnabled(SpeechState.Fetching(a), b))
  }

  // ---- speakability ----

  @Test fun `error turns are not speakable`() {
    assertFalse(turnIsSpeakable(ERROR_BACKEND, "[error] connection refused"))
  }

  @Test fun `blank turns are not speakable`() {
    assertFalse(turnIsSpeakable("TPU_LITERTLM", "   "))
  }

  @Test fun `normal turns are speakable including when the backend is unknown`() {
    assertTrue(turnIsSpeakable("TPU_LITERTLM", "The node is live."))
    assertTrue(turnIsSpeakable(null, "The node is live."))
  }
}
