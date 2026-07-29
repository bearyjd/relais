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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the provisioned-model registry (#180) — the inventory a per-request model swap
 * resolves against, and the thing that keeps a client-named model from ever triggering a download.
 */
class RelaisModelRegistryTest {

  private fun m(id: String, path: String = "/data/$id.litertlm", name: String = id) =
    ProvisionedModel(id, path, name)

  // ---- upsert ----

  @Test fun `a new model is appended`() {
    val out = upsertProvisioned(listOf(m("a")), m("b"))
    assertEquals(listOf("a", "b"), out.map { it.modelId })
  }

  @Test fun `re-provisioning the same id REPLACES rather than duplicating`() {
    // A re-download lands at a new commit-hash path. A stale duplicate would let a swap resolve to a
    // file that no longer exists.
    val out = upsertProvisioned(listOf(m("a", "/old/a"), m("b")), m("a", "/new/a"))
    assertEquals(1, out.count { it.modelId == "a" })
    assertEquals("/new/a", out.first { it.modelId == "a" }.path)
    assertTrue(out.any { it.modelId == "b" })
  }

  @Test fun `upsert on an empty registry works`() {
    assertEquals(listOf("a"), upsertProvisioned(emptyList(), m("a")).map { it.modelId })
  }

  // ---- prune ----

  @Test fun `entries whose file vanished are pruned`() {
    // Without this, a swap to a deleted model fails deep inside engine init, long after the request
    // was accepted.
    val entries = listOf(m("a", "/gone/a"), m("b", "/here/b"))
    val out = pruneMissingProvisioned(entries) { it == "/here/b" }
    assertEquals(listOf("b"), out.map { it.modelId })
  }

  @Test fun `pruning everything yields an empty registry, not an error`() {
    assertEquals(emptyList<ProvisionedModel>(), pruneMissingProvisioned(listOf(m("a"))) { false })
  }

  // ---- lookup ----

  @Test fun `ids are exposed as a set for the swap decision`() {
    assertEquals(setOf("a", "b"), provisionedIds(listOf(m("a"), m("b"))))
    assertEquals(emptySet<String>(), provisionedIds(emptyList()))
  }

  // ---- round trip ----

  @Test fun `encode then decode round-trips every field`() {
    val entries = listOf(m("org/one", "/p/1", "One"), m("org/two", "/p/2", "Two"))
    assertEquals(entries, decodeProvisioned(encodeProvisioned(entries)))
  }

  @Test fun `an empty registry round-trips`() {
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned(encodeProvisioned(emptyList())))
  }

  // ---- defensive decode: a damaged preference must not brick the node ----

  @Test fun `null or blank json decodes to empty`() {
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned(null))
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned(""))
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned("   "))
  }

  @Test fun `corrupt json decodes to empty rather than throwing`() {
    // The node must still boot and serve its resident model if this preference is damaged — same
    // defensive posture as RelaisModelRef.fromJson.
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned("{not json"))
    assertEquals(emptyList<ProvisionedModel>(), decodeProvisioned("{\"an\":\"object\"}"))
  }

  @Test fun `members missing required fields are dropped, valid siblings survive`() {
    val json = """[{"model_id":"good","path":"/p"},{"path":"/orphan"},{"model_id":"no-path"}]"""
    val out = decodeProvisioned(json)
    assertEquals(listOf("good"), out.map { it.modelId })
  }

  // ---- swap targeting: the invariant that broke when eligibility widened ----

  @Test fun `the swap target is the REQUESTED model, not the configured one`() {
    // The bug this guards: ensureModelSwapInBackground loaded RelaisConfig.modelId. That was correct
    // while the guard forced requested == configured, but once ANY provisioned model became
    // eligible it meant 503 "swapping" -> reload the SAME model -> 503 the retry, forever.
    val registry = listOf(m("configured", "/p/configured"), m("requested", "/p/requested"))
    val target = swapTargetFor("requested", registry)
    assertEquals("requested", target?.modelId)
    assertEquals("/p/requested", target?.path)
  }

  @Test fun `an unrecorded target yields null so the engine uses its configured-model fallback`() {
    // The operator's just-selected model may not be recorded yet; null is the correct signal.
    assertEquals(null, swapTargetFor("not-yet-recorded", listOf(m("a"))))
    assertEquals(null, swapTargetFor("anything", emptyList()))
  }

  @Test fun `display name falls back to the model id when absent`() {
    assertEquals("good", decodeProvisioned("""[{"model_id":"good","path":"/p"}]""").single().displayName)
  }
}
