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

package cc.grepon.relais.rerank

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the `/v1/rerank` parsing, ordering, score mapping, and response shape (#177). */
class RelaisRerankEndpointTest {

  private fun body(json: String) = JSONObject(json)
  private fun valid(json: String) = parseRerankRequest(body(json)) as RerankRequestResult.Valid

  // ---- parsing ----

  @Test fun `valid string-document request parses`() {
    val r = valid("""{"query":"q","documents":["a","b","c"]}""")
    assertEquals("q", r.request.query)
    assertEquals(listOf("a", "b", "c"), r.request.documents)
    assertEquals(null, r.request.topN)
    assertEquals(false, r.request.returnDocuments)
  }

  @Test fun `cohere object-form documents are accepted`() {
    val r = valid("""{"query":"q","documents":[{"text":"a"},{"text":"b"}]}""")
    assertEquals(listOf("a", "b"), r.request.documents)
  }

  @Test fun `top_n and return_documents parse`() {
    val r = valid("""{"query":"q","documents":["a","b"],"top_n":1,"return_documents":true}""")
    assertEquals(1, r.request.topN)
    assertTrue(r.request.returnDocuments)
  }

  @Test fun `missing or blank query is rejected`() {
    assertTrue(parseRerankRequest(body("""{"documents":["a"]}""")) is RerankRequestResult.Invalid)
    assertTrue(parseRerankRequest(body("""{"query":"  ","documents":["a"]}""")) is RerankRequestResult.Invalid)
  }

  @Test fun `missing or empty documents is rejected`() {
    assertTrue(parseRerankRequest(body("""{"query":"q"}""")) is RerankRequestResult.Invalid)
    assertTrue(parseRerankRequest(body("""{"query":"q","documents":[]}""")) is RerankRequestResult.Invalid)
  }

  @Test fun `too many documents is rejected`() {
    val docs = (0..RERANK_LIMITS.maxDocuments).joinToString(",") { "\"d$it\"" }
    assertTrue(parseRerankRequest(body("""{"query":"q","documents":[$docs]}""")) is RerankRequestResult.Invalid)
  }

  @Test fun `an empty document entry is rejected`() {
    assertTrue(parseRerankRequest(body("""{"query":"q","documents":["a","",""]}""")) is RerankRequestResult.Invalid)
  }

  // ---- score mapping ----

  @Test fun `cosine maps to the zero to one relevance band`() {
    assertEquals(1.0f, cosineToRelevance(1f), 0.0001f)
    assertEquals(0.0f, cosineToRelevance(-1f), 0.0001f)
    assertEquals(0.5f, cosineToRelevance(0f), 0.0001f)
    // out-of-range cosine is clamped
    assertEquals(1.0f, cosineToRelevance(2f), 0.0001f)
    assertEquals(0.0f, cosineToRelevance(-2f), 0.0001f)
  }

  // ---- ordering ----

  @Test fun `rerankOrder sorts by score descending, ties by original index`() {
    val scores = floatArrayOf(0.1f, 0.9f, 0.5f, 0.9f)
    // 0.9 at index 1 and 3 (tie -> 1 before 3), then 0.5 (index 2), then 0.1 (index 0).
    assertEquals(listOf(1, 3, 2, 0), rerankOrder(scores, null))
  }

  @Test fun `rerankOrder honors top_n and clamps it to size`() {
    val scores = floatArrayOf(0.1f, 0.9f, 0.5f)
    assertEquals(listOf(1, 2), rerankOrder(scores, 2))
    assertEquals(listOf(1, 2, 0), rerankOrder(scores, 99)) // clamped to 3
  }

  // ---- response shape ----

  @Test fun `response is cohere-shaped with index and relevance_score`() {
    val docs = listOf("a", "b", "c")
    val scores = floatArrayOf(0.2f, 0.8f, 0.5f)
    val order = rerankOrder(scores, null)
    val resp = buildRerankResponse(order, scores, docs, returnDocuments = false, model = "m")
    assertEquals("list", resp.getString("object"))
    assertEquals("m", resp.getString("model"))
    val results = resp.getJSONArray("results")
    assertEquals(3, results.length())
    val first = results.getJSONObject(0)
    assertEquals(1, first.getInt("index")) // highest score (0.8) is index 1
    assertEquals(0.8, first.getDouble("relevance_score"), 0.0001)
    assertTrue("document must be omitted unless requested", !first.has("document"))
  }

  @Test fun `return_documents nests the doc text`() {
    val docs = listOf("alpha", "beta")
    val scores = floatArrayOf(0.9f, 0.1f)
    val resp = buildRerankResponse(rerankOrder(scores, null), scores, docs, returnDocuments = true, model = "m")
    val first = resp.getJSONArray("results").getJSONObject(0)
    assertEquals("alpha", first.getJSONObject("document").getString("text"))
  }
}
