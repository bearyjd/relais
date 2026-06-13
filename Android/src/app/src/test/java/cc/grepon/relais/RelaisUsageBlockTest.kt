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
 * Pure JVM tests for the OpenAI usage-block helpers (Feature 02).
 *
 * All tests call [buildUsageObject] / [estimatePromptTokens] directly — no socket, no Android
 * Context, no Robolectric. The functions live in RelaisHttpServer.kt as internal top-level fns.
 *
 * completion_tokens = exact engine counter (onMessage callback count from RelaisResult).
 * prompt_tokens     = word-boundary ESTIMATE (LiteRT-LM exposes no detached prompt tokenizer
 *                     via sendMessageAsync; see SPIKE-FINDINGS.md / Q1 comment in RelaisEngine).
 * total_tokens      = prompt_tokens + completion_tokens (invariant, always exact).
 *
 * The `usage` object returned by [buildUsageObject] is OpenAI-schema-clean: it contains ONLY
 * the three standard keys. The estimation signal is surfaced as `x_relais_usage_note` at the
 * top level of the enclosing response/chunk — NOT inside the `usage` sub-object.
 */
class RelaisUsageBlockTest {

  // ---------------------------------------------------------------------------
  // Test 1 — usage sub-object is schema-clean (exactly 3 standard keys)
  // ---------------------------------------------------------------------------

  @Test
  fun `buildUsageObject returns exactly the three standard OpenAI keys`() {
    val usage = buildUsageObject("hello world", 42)

    assertEquals(
      "completion_tokens must equal the engine counter",
      42,
      usage.getInt("completion_tokens"),
    )
    assertEquals(
      "total_tokens must equal prompt_tokens + completion_tokens",
      usage.getInt("prompt_tokens") + 42,
      usage.getInt("total_tokens"),
    )
    // usage_note must NOT be inside the usage object — it would trip strict OpenAI validators.
    assertFalse(
      "usage_note must NOT appear inside the usage sub-object",
      usage.has("usage_note"),
    )
    // x_relais_usage_note belongs on the enclosing response, not here.
    assertFalse(
      "x_relais_usage_note must NOT appear inside the usage sub-object",
      usage.has("x_relais_usage_note"),
    )
    assertEquals(
      "usage object must have exactly 3 keys (prompt_tokens, completion_tokens, total_tokens)",
      3,
      usage.length(),
    )
  }

  // ---------------------------------------------------------------------------
  // Test 2 — prompt token estimator, basic cases
  // ---------------------------------------------------------------------------

  @Test
  fun `estimatePromptTokens splits on whitespace runs correctly`() {
    assertEquals("two words", 2, estimatePromptTokens("hello world"))
    assertEquals("four words", 4, estimatePromptTokens("one two three four"))
    assertEquals("leading/trailing spaces collapse to one token", 1, estimatePromptTokens("  spaces  "))
    assertEquals("empty string yields zero", 0, estimatePromptTokens(""))
    assertEquals("all-whitespace string yields zero", 0, estimatePromptTokens("   "))
  }

  // ---------------------------------------------------------------------------
  // Test 3 — total_tokens invariant: always equals prompt + completion
  // ---------------------------------------------------------------------------

  @Test
  fun `total_tokens invariant holds for arbitrary inputs`() {
    for ((text, n) in listOf(
      "short" to 0,
      "one two three" to 17,
      "  spaces everywhere  " to 100,
      "" to 999,
    )) {
      val usage = buildUsageObject(text, n)
      val p = usage.getInt("prompt_tokens")
      val c = usage.getInt("completion_tokens")
      val t = usage.getInt("total_tokens")
      assertEquals(
        "total_tokens must equal prompt_tokens + completion_tokens for text='$text' n=$n",
        p + c,
        t,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Test 4 — zero completion tokens (AICore / unknown path)
  // ---------------------------------------------------------------------------

  @Test
  fun `zero completionTokens (AICore path) yields total equal to prompt estimate`() {
    val usage = buildUsageObject("some prompt", 0)
    assertEquals("completion_tokens must be zero", 0, usage.getInt("completion_tokens"))
    assertEquals(
      "total_tokens must equal prompt estimate when completion is zero",
      usage.getInt("prompt_tokens"),
      usage.getInt("total_tokens"),
    )
    // Schema-clean: no estimation signal inside usage.
    assertFalse(
      "usage_note must not be inside usage even for the zero-completion (AICore) case",
      usage.has("usage_note"),
    )
  }

  // ---------------------------------------------------------------------------
  // Test 5 — x_relais_usage_note belongs on the enclosing object, not inside usage
  // ---------------------------------------------------------------------------

  @Test
  fun `x_relais_usage_note is absent from the usage sub-object returned by buildUsageObject`() {
    // The extension field is the caller's responsibility to attach to the response/chunk envelope.
    // This test pins that buildUsageObject itself never adds it — so callers can't accidentally
    // double-emit it or put it in the wrong place.
    val usage = buildUsageObject("any text here", 10)
    assertFalse(
      "x_relais_usage_note must never appear inside the usage sub-object",
      usage.has("x_relais_usage_note"),
    )
    assertTrue(
      "usage sub-object must still have prompt_tokens",
      usage.has("prompt_tokens"),
    )
    assertTrue(
      "usage sub-object must still have completion_tokens",
      usage.has("completion_tokens"),
    )
    assertTrue(
      "usage sub-object must still have total_tokens",
      usage.has("total_tokens"),
    )
  }
}
