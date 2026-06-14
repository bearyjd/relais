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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RelaisReasoning]: the reasoning_effort -> on/off mapping and the per-callback
 * reasoning/visible split (the count + ordering invariants the streaming engine relies on).
 */
class RelaisReasoningTest {

  @Test fun `absent (null) effort disables thinking`() {
    assertFalse(RelaisReasoning.thinkingEnabled(null))
  }

  @Test fun `empty and blank effort disables thinking`() {
    assertFalse(RelaisReasoning.thinkingEnabled(""))
    assertFalse(RelaisReasoning.thinkingEnabled("   "))
  }

  @Test fun `literal none disables thinking (case and space insensitive)`() {
    assertFalse(RelaisReasoning.thinkingEnabled("none"))
    assertFalse(RelaisReasoning.thinkingEnabled("None"))
    assertFalse(RelaisReasoning.thinkingEnabled("  NONE  "))
  }

  @Test fun `low medium high enable thinking`() {
    assertTrue(RelaisReasoning.thinkingEnabled("low"))
    assertTrue(RelaisReasoning.thinkingEnabled("medium"))
    assertTrue(RelaisReasoning.thinkingEnabled("high"))
  }

  @Test fun `any other non-none value enables thinking`() {
    assertTrue(RelaisReasoning.thinkingEnabled("minimal"))
    assertTrue(RelaisReasoning.thinkingEnabled("HIGH"))
    assertTrue(RelaisReasoning.thinkingEnabled("  low  "))
  }

  @Test fun `enableThinking defaults off on RelaisRequest`() {
    assertFalse(RelaisRequest(text = "x").enableThinking)
  }

  // --- classifyStreamDelta: per-callback reasoning/visible split ---

  @Test fun `thinking off always emits the visible delta verbatim (no reasoning)`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = false, visibleDelta = "hi", thoughtDelta = "x")
    assertNull("reasoning must be suppressed when thinking is off", step.reasoningToEmit)
    assertEquals("hi", step.visibleToEmit)
  }

  @Test fun `thinking off still emits an empty visible delta (byte-for-byte prior behavior)`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = false, visibleDelta = "", thoughtDelta = null)
    assertNull(step.reasoningToEmit)
    assertEquals("", step.visibleToEmit)
  }

  @Test fun `reasoning-only callback yields no visible token (no completion-token inflation)`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = true, visibleDelta = "", thoughtDelta = " think")
    assertEquals(" think", step.reasoningToEmit)
    assertNull("a reasoning-only callback must not count as a visible token", step.visibleToEmit)
  }

  @Test fun `combined thought and visible deltas emit both (visible counted once)`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = true, visibleDelta = "42", thoughtDelta = " think")
    assertEquals(" think", step.reasoningToEmit)
    assertEquals("42", step.visibleToEmit)
  }

  @Test fun `thinking on with no thought emits visible only`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = true, visibleDelta = "answer", thoughtDelta = null)
    assertNull(step.reasoningToEmit)
    assertEquals("answer", step.visibleToEmit)
  }

  @Test fun `thinking on with empty thought is treated as no reasoning`() {
    val step = RelaisReasoning.classifyStreamDelta(enableThinking = true, visibleDelta = "", thoughtDelta = "")
    assertNull(step.reasoningToEmit)
    assertEquals("", step.visibleToEmit)
  }
}
