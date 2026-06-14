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

/**
 * Pure logic for OpenAI-style reasoning ("thinking") support. Kept free of Android/engine types so it
 * is unit-testable on the JVM (see `RelaisReasoningTest`).
 *
 * Relais exposes the model's reasoning via the LiteRT-LM `message.channels["thought"]` side-channel
 * (proven on Gemma-4 E2B: the channel is populated only when `extraContext["enable_thinking"]="true"`
 * is passed, and the visible answer stays clean). The node has no graded "effort" — the native API
 * exposes only a boolean — so any non-"none" `reasoning_effort` simply turns thinking on.
 */
object RelaisReasoning {

  /**
   * Maps the OpenAI `reasoning_effort` request field to a thinking on/off decision.
   *
   * Off (false) when: absent (null), empty/blank, or the literal "none" (case-insensitive).
   * On (true) for any other value (e.g. "low", "medium", "high", "minimal"). The granularity is not
   * modeled — the engine flag is boolean — so all non-"none" values behave identically.
   */
  fun thinkingEnabled(reasoningEffort: String?): Boolean {
    val normalized = reasoningEffort?.trim()?.lowercase()
    return !normalized.isNullOrEmpty() && normalized != "none"
  }

  /**
   * What to do with one streaming callback's deltas — the per-token split between the reasoning
   * side-channel and the visible answer. [reasoningToEmit] (when non-null) is appended to the
   * reasoning buffer and streamed as `delta.reasoning_content`; [visibleToEmit] (when non-null) is
   * counted as one completion token, appended to the answer, and streamed as `delta.content`.
   */
  data class StreamStep(val reasoningToEmit: String?, val visibleToEmit: String?)

  /**
   * Classifies one callback's deltas. Pure (no engine/Android types) so the count + ordering
   * invariants are JVM-testable; [RelaisEngine.generate] feeds each callback through this.
   *
   * Invariants:
   *  - Reasoning is surfaced only when [enableThinking] and the model emitted a non-empty thought.
   *  - A reasoning-ONLY callback (a thought delta with an empty visible delta) yields no visible
   *    token — so reasoning tokens never inflate `completion_tokens`.
   *  - A callback with BOTH a thought and a visible delta still emits the visible token (counted once).
   *  - When no reasoning is surfaced, the visible delta is always emitted verbatim — including the
   *    empty string — so the thinking-OFF path is byte-for-byte the prior behavior.
   */
  fun classifyStreamDelta(enableThinking: Boolean, visibleDelta: String, thoughtDelta: String?): StreamStep {
    val reasoning = if (enableThinking && !thoughtDelta.isNullOrEmpty()) thoughtDelta else null
    val visible = if (reasoning != null && visibleDelta.isEmpty()) null else visibleDelta
    return StreamStep(reasoningToEmit = reasoning, visibleToEmit = visible)
  }
}
