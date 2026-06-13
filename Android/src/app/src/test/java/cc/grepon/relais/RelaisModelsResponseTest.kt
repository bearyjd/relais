/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import cc.grepon.relais.data.RelaisModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [buildModelsResponse] — the pure mapping function that shapes the
 * GET /v1/models OpenAI-compatible response. Hermetic: no network, no Android context.
 */
class RelaisModelsResponseTest {

  private fun makeRef(modelId: String, source: String) =
    RelaisModelRef(
      modelId = modelId,
      modelFile = "model.litertlm",
      commitHash = "abc123",
      sizeInBytes = 1_000_000L,
      displayName = "Test Model",
      source = source,
    )

  // Test 1 — curated list maps correctly
  @Test
  fun `curated list maps all refs and sets correct fields`() {
    val ref0 = makeRef("litert-community/model-a", RelaisModelRef.SOURCE_ALLOWLIST)
    val ref1 = makeRef("litert-community/model-b", RelaisModelRef.SOURCE_HUGGINGFACE)
    val fallback = "litert-community/fallback-should-not-appear"

    val response = buildModelsResponse(listOf(ref0, ref1), fallback)

    assertEquals("list", response.getString("object"))
    val data = response.getJSONArray("data")
    assertEquals(2, data.length())

    val item0 = data.getJSONObject(0)
    assertEquals(ref0.modelId, item0.getString("id"))
    assertEquals("model", item0.getString("object"))
    assertEquals("allowlist", item0.getString("owned_by"))
    assertEquals(0L, item0.getLong("created"))

    val item1 = data.getJSONObject(1)
    assertEquals(ref1.modelId, item1.getString("id"))
    assertEquals("model", item1.getString("object"))
    assertEquals("huggingface", item1.getString("owned_by"))
    assertEquals(0L, item1.getLong("created"))

    // fallback must NOT appear when the real list is non-empty
    val ids = (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    assertFalse("fallback id must not appear in curated response", ids.contains(fallback))
  }

  // Test 2 — offline fallback (empty refs)
  @Test
  fun `empty refs returns single fallback entry with owned_by node`() {
    val fallbackId = RelaisConfig.DEFAULT_MODEL_ID

    val response = buildModelsResponse(emptyList<RelaisModelRef>(), fallbackId)

    assertEquals("list", response.getString("object"))
    val data = response.getJSONArray("data")
    assertEquals(1, data.length())

    val item = data.getJSONObject(0)
    assertEquals(fallbackId, item.getString("id"))
    assertEquals("model", item.getString("object"))
    assertEquals("node", item.getString("owned_by"))
    assertEquals(0L, item.getLong("created"))
  }

  // Test 3 — ID round-trip: ids from /v1/models are accepted verbatim by /v1/chat/completions.
  // Uses both the default id and an arbitrary non-default id to guard the general invariant
  // (not just the constant).
  @Test
  fun `ids in response survive round-trip for default and non-default model ids`() {
    val defaultRef = makeRef(RelaisConfig.DEFAULT_MODEL_ID, RelaisModelRef.SOURCE_ALLOWLIST)
    val otherRef = makeRef("litert-community/Qwen3-0.6B", RelaisModelRef.SOURCE_HUGGINGFACE)

    val response = buildModelsResponse(listOf(defaultRef, otherRef), "ignored-fallback")

    val data = response.getJSONArray("data")
    assertEquals(2, data.length())
    assertEquals(RelaisConfig.DEFAULT_MODEL_ID, data.getJSONObject(0).getString("id"))
    assertEquals("litert-community/Qwen3-0.6B", data.getJSONObject(1).getString("id"))
    // Both entries must carry the created field for strict client compat.
    assertEquals(0L, data.getJSONObject(0).getLong("created"))
    assertEquals(0L, data.getJSONObject(1).getLong("created"))
  }
}
