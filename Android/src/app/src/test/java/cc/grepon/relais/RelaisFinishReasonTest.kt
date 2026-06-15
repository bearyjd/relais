/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RelaisFinishReason]: the thermal-truncation -> OpenAI `finish_reason` mapping
 * AND the cancel-cause fold ([RelaisFinishReason.applyCancel]) that is the heart of issue #22 — a
 * THERMAL cancel must mark truncation ("length"); a BROKEN_PIPE abort (client gone) must not.
 */
class RelaisFinishReasonTest {

  // ---- finish_reason mapping ----

  @Test fun `natural completion maps to stop`() {
    assertEquals("stop", RelaisFinishReason.forCompletion(truncated = false))
  }

  @Test fun `thermal truncation maps to length`() {
    assertEquals("length", RelaisFinishReason.forCompletion(truncated = true))
  }

  @Test fun `constants are the OpenAI spec strings`() {
    // These are wire values clients match on — pin them so a rename can't silently change the API.
    assertEquals("stop", RelaisFinishReason.STOP)
    assertEquals("length", RelaisFinishReason.LENGTH)
    assertEquals("tool_calls", RelaisFinishReason.TOOL_CALLS)
  }

  @Test fun `forCompletion only ever returns stop or length`() {
    // forCompletion is the text/structured-output rule; it must never emit tool_calls.
    assertEquals(RelaisFinishReason.STOP, RelaisFinishReason.forCompletion(false))
    assertEquals(RelaisFinishReason.LENGTH, RelaisFinishReason.forCompletion(true))
  }

  // ---- cancel-cause fold (the issue #22 distinction) ----

  @Test fun `fresh cancel state is neither canceled nor truncated`() {
    val s = DecodeCancelState()
    assertFalse(s.canceled)
    assertFalse(s.truncated)
  }

  @Test fun `thermal cancel marks both canceled and truncated`() {
    val s = RelaisFinishReason.applyCancel(DecodeCancelState(), DecodeCancelCause.THERMAL)
    assertTrue(s.canceled)
    assertTrue(s.truncated)
    assertEquals("length", RelaisFinishReason.forCompletion(s.truncated))
  }

  @Test fun `broken pipe marks canceled only - never truncated`() {
    val s = RelaisFinishReason.applyCancel(DecodeCancelState(), DecodeCancelCause.BROKEN_PIPE)
    assertTrue(s.canceled)
    assertFalse(s.truncated)
    assertEquals("stop", RelaisFinishReason.forCompletion(s.truncated))
  }

  @Test fun `broken pipe after thermal preserves the truncation`() {
    // A late client disconnect must NOT downgrade an already-thermally-truncated decode to "stop".
    val thermal = RelaisFinishReason.applyCancel(DecodeCancelState(), DecodeCancelCause.THERMAL)
    val then = RelaisFinishReason.applyCancel(thermal, DecodeCancelCause.BROKEN_PIPE)
    assertTrue(then.canceled)
    assertTrue(then.truncated)
  }

  @Test fun `thermal after broken pipe upgrades to truncated`() {
    val pipe = RelaisFinishReason.applyCancel(DecodeCancelState(), DecodeCancelCause.BROKEN_PIPE)
    val then = RelaisFinishReason.applyCancel(pipe, DecodeCancelCause.THERMAL)
    assertTrue(then.canceled)
    assertTrue(then.truncated)
  }

  @Test fun `applyCancel is idempotent for repeated thermal cancels`() {
    val once = RelaisFinishReason.applyCancel(DecodeCancelState(), DecodeCancelCause.THERMAL)
    val twice = RelaisFinishReason.applyCancel(once, DecodeCancelCause.THERMAL)
    assertEquals(once, twice)
  }
}
