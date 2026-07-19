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

import android.content.Context
import cc.grepon.relais.embed.RelaisEmbedder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the `POST /v1/embeddings` shaping + validation helpers. The HTTP route is a thin
 * shell over these (mirroring how [buildModelsResponse]/[buildUsageObject] back GET /v1/models and the
 * chat usage block); testing the pure functions exercises every response path without a socket.
 *
 * Contract under test (honest 501): the route returns 200 only when an embedder is registered AND
 * available; provider-null → 501; oversized/malformed input → 400. Until feature #6's follow-up lands
 * a real EmbeddingGemma impl, no embedder is registered, so the live endpoint stays 501 — a
 * test-only [FakeEmbedder] drives the success path here.
 */
class RelaisEmbeddingsEndpointTest {

  /**
   * Test-only embedder: deterministic, never registered into [RelaisEmbedderProvider]. The pure
   * helpers under test take already-computed vectors, so [embed] is exercised here with a null
   * [Context] (the fake ignores it) to keep this a pure JVM test with no Android runtime.
   */
  private class FakeEmbedder(override val dim: Int = 4) : RelaisEmbedder {
    override fun isAvailable(context: Context): Boolean = true
    override fun embed(context: Context, texts: List<String>): List<FloatArray> =
      texts.mapIndexed { i, _ -> FloatArray(dim) { (i + it).toFloat() } }
    override fun countTokens(texts: List<String>): Int = texts.sumOf { it.trim().split(" ").size }
  }

  // --- parseEmbeddingInputs ---

  @Test fun `parse accepts a single string input`() {
    val inputs = parseEmbeddingInputs(JSONObject().put("input", "hello world"))
    assertEquals(listOf("hello world"), inputs)
  }

  @Test fun `parse accepts a string array input`() {
    val body = JSONObject(/* language=JSON */ """{"input":["a","b","c"]}""")
    assertEquals(listOf("a", "b", "c"), parseEmbeddingInputs(body))
  }

  @Test fun `parse returns null when input is missing`() {
    assertNull(parseEmbeddingInputs(JSONObject().put("model", "x")))
  }

  @Test fun `parse returns null for an empty array`() {
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":[]}""")))
  }

  @Test fun `parse returns null when an array element is not a string`() {
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":[1,2]}""")))
  }

  @Test fun `parse returns null for a blank single string`() {
    assertNull(parseEmbeddingInputs(JSONObject().put("input", "   ")))
  }

  @Test fun `parse returns null for a blank array element`() {
    // OpenAI 400s an empty-string member; an empty token sequence yields a degenerate vector.
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":["a",""]}""")))
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":["a","   "]}""")))
  }

  @Test fun `parse returns null for a non-string scalar input`() {
    // body.optString would coerce a bare number/bool ({"input":42} -> "42"); OpenAI 400s it.
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":42}""")))
    assertNull(parseEmbeddingInputs(JSONObject(/* language=JSON */ """{"input":true}""")))
  }

  // --- validateEmbeddingInputs ---

  @Test fun `validate accepts inputs within bounds`() {
    val r = validateEmbeddingInputs(listOf("a", "b"), maxCount = 8, maxItemLength = 100)
    assertEquals(EmbeddingValidation.Ok, r)
  }

  @Test fun `validate rejects too many inputs`() {
    val r = validateEmbeddingInputs(List(9) { "x" }, maxCount = 8, maxItemLength = 100)
    assertEquals(EmbeddingValidation.TooMany, r)
  }

  @Test fun `validate rejects an oversized item`() {
    val r = validateEmbeddingInputs(listOf("ok", "x".repeat(101)), maxCount = 8, maxItemLength = 100)
    assertEquals(EmbeddingValidation.TooLong, r)
  }

  // --- buildEmbeddingsResponse (success shape) ---

  @Test fun `response shapes N vectors with correct indices and usage`() {
    val embedder = FakeEmbedder(dim = 3)
    val inputs = listOf("first", "second")
    // Build the per-input vectors directly (the fake's embed() ignores Context); this keeps the test
    // pure JVM. The route calls embedder.embed(context, inputs) — same List<FloatArray> contract.
    val vectors = inputs.mapIndexed { i, _ -> FloatArray(embedder.dim) { (i + it).toFloat() } }
    val promptTokens = embedder.countTokens(inputs)

    val resp = buildEmbeddingsResponse(vectors, model = "embeddinggemma-300m", promptTokens = promptTokens)

    assertEquals("list", resp.getString("object"))
    assertEquals("embeddinggemma-300m", resp.getString("model"))

    val data = resp.getJSONArray("data")
    assertEquals(2, data.length())
    for (i in 0 until data.length()) {
      val item = data.getJSONObject(i)
      assertEquals("embedding", item.getString("object"))
      assertEquals(i, item.getInt("index"))
      assertEquals(3, item.getJSONArray("embedding").length())
    }

    val usage = resp.getJSONObject("usage")
    assertEquals(promptTokens, usage.getInt("prompt_tokens"))
    assertEquals(promptTokens, usage.getInt("total_tokens"))
    assertFalse("usage has no completion_tokens for embeddings", usage.has("completion_tokens"))
  }

  @Test fun `response embedding values round-trip the float vector`() {
    val vectors = listOf(floatArrayOf(0.5f, -1.25f, 2.0f))
    val resp = buildEmbeddingsResponse(vectors, model = "m", promptTokens = 1)
    val arr = resp.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
    assertEquals(0.5, arr.getDouble(0), 1e-6)
    assertEquals(-1.25, arr.getDouble(1), 1e-6)
    assertEquals(2.0, arr.getDouble(2), 1e-6)
  }

  @Test fun `error envelope uses the OpenAI error shape`() {
    val err = buildEmbeddingsError("embeddings model not provisioned", "not_implemented")
    val error = err.getJSONObject("error")
    assertEquals("embeddings model not provisioned", error.getString("message"))
    assertEquals("not_implemented", error.getString("type"))
  }

  // resolveEmbeddingModel (issue #190/#192): echo the client's requested model (OpenAI/Cohere both
  // echo it) when present; fall back to the embedder's own id — never the resident LLM — otherwise.

  @Test fun `resolveEmbeddingModel echoes a client-requested model`() {
    assertEquals(
      "text-embedding-3-small",
      resolveEmbeddingModel("text-embedding-3-small", "litert-community/embeddinggemma-300m"),
    )
  }

  @Test fun `resolveEmbeddingModel falls back to the embedder id when the request omits model`() {
    assertEquals(
      "litert-community/embeddinggemma-300m",
      resolveEmbeddingModel(null, "litert-community/embeddinggemma-300m"),
    )
  }

  @Test fun `resolveEmbeddingModel falls back to the embedder id for a blank model`() {
    assertEquals(
      "litert-community/embeddinggemma-300m",
      resolveEmbeddingModel("", "litert-community/embeddinggemma-300m"),
    )
  }
}
