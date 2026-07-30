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

import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Curated-source filter guard: only node-runnable models — LiteRT-LM, LLM-chat, not disabled —
 * survive [RelaisModelCatalog.curatedModelsFrom], and each maps to a self-contained ref. Hermetic:
 * parses a committed fixture; never touches the network (the seam takes an already-fetched
 * allowlist), so it documents the filter independently of the live allowlist contents.
 */
class RelaisModelCatalogTest {

  // Fixture shaped like the real allowlist, covering each filter branch exactly once.
  private val fixtureJson =
    """
    {
      "models": [
        { "name": "Runnable Chat", "modelId": "litert-community/runnable-chat",
          "modelFile": "runnable.litertlm", "commitHash": "aaa111", "description": "",
          "sizeInBytes": 3600000000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" },
        { "name": "Disabled Chat", "modelId": "litert-community/disabled-chat",
          "modelFile": "disabled.litertlm", "commitHash": "bbb222", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm", "disabled": true },
        { "name": "AICore Chat", "modelId": "google/aicore-chat",
          "modelFile": "aicore.bin", "commitHash": "ccc333", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "aicore" },
        { "name": "Inferred Litertlm", "modelId": "someone/inferred-litertlm",
          "modelFile": "inferred.litertlm", "commitHash": "ddd444", "description": "",
          "sizeInBytes": 2900000000, "defaultConfig": {},
          "taskTypes": ["llm_chat"] },
        { "name": "Image Only", "modelId": "litert-community/image-only",
          "modelFile": "image.litertlm", "commitHash": "eee555", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_ask_image"], "runtimeType": "litert_lm" },
        { "name": "Per Soc", "modelId": "litert-community/per-soc",
          "modelFile": "default.litertlm", "commitHash": "fff666", "description": "",
          "sizeInBytes": 1000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm",
          "socToModelFiles": { "some_soc": { "modelFile": "soc.litertlm", "commitHash": "ace777", "sizeInBytes": 2000 } } }
      ]
    }
    """
      .trimIndent()

  @Test
  fun keepsOnlyNodeRunnableModels() {
    val allowlist = Gson().fromJson(fixtureJson, ModelAllowlist::class.java)

    val refs = RelaisModelCatalog.curatedModelsFrom(allowlist)
    val ids = refs.map { it.modelId }

    // LiteRT-LM + llm_chat (explicit), and null-runtimeType inferred from the .litertlm extension.
    assertTrue("explicit litert_lm chat model kept", ids.contains("litert-community/runnable-chat"))
    assertTrue("null-runtimeType .litertlm chat model kept", ids.contains("someone/inferred-litertlm"))
    // Disabled, AICore, non-chat (image-only), and per-SOC entries are all dropped. A per-SOC entry
    // is excluded because a flat ref can't faithfully represent its device-SOC file (see catalog).
    assertTrue("per-SOC entry dropped", !ids.contains("litert-community/per-soc"))
    assertEquals("exactly the two runnable models survive", 2, refs.size)

    // Each survivor is a fully-populated allowlist-sourced ref.
    val runnable = refs.first { it.modelId == "litert-community/runnable-chat" }
    assertEquals("Runnable Chat", runnable.displayName)
    assertEquals("runnable.litertlm", runnable.modelFile)
    assertEquals("aaa111", runnable.commitHash)
    assertEquals(3_600_000_000L, runnable.sizeInBytes)
    assertEquals(RelaisModelRef.SOURCE_ALLOWLIST, runnable.source)
  }

  @Test
  fun emptyAllowlistYieldsEmptyList() {
    assertTrue(RelaisModelCatalog.curatedModelsFrom(ModelAllowlist(emptyList())).isEmpty())
  }

  // --- #220: runtime-compatibility filter ------------------------------------------------------

  /**
   * Entries shaped like the real allowlist, using the actual repo ids from the measured table in
   * #220 — a model that fails engine-create, one that is untested, and one that is license-gated.
   */
  private val compatFixtureJson =
    """
    {
      "models": [
        { "name": "Qwen2.5 1.5B", "modelId": "litert-community/Qwen2.5-1.5B-Instruct",
          "modelFile": "qwen.litertlm", "commitHash": "aaa111", "description": "",
          "sizeInBytes": 1597939712, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" },
        { "name": "DeepSeek R1 Distill", "modelId": "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
          "modelFile": "deepseek.litertlm", "commitHash": "bbb222", "description": "",
          "sizeInBytes": 1830000000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" },
        { "name": "Gemma 3 1B IT", "modelId": "litert-community/Gemma3-1B-IT",
          "modelFile": "gemma3.litertlm", "commitHash": "ccc333", "description": "",
          "sizeInBytes": 580000000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" },
        { "name": "Gemma 4 E2B", "modelId": "litert-community/gemma-4-E2B-it-litert-lm",
          "modelFile": "e2b.litertlm", "commitHash": "ddd444", "description": "",
          "sizeInBytes": 2590000000, "defaultConfig": {},
          "taskTypes": ["llm_chat"], "runtimeType": "litert_lm" }
      ]
    }
    """
      .trimIndent()

  @Test
  fun dropsModelsMeasuredToFailEngineCreate() {
    val allowlist = Gson().fromJson(compatFixtureJson, ModelAllowlist::class.java)

    val ids = RelaisModelCatalog.curatedModelsFrom(allowlist).map { it.modelId }

    assertTrue(
      "#220: Qwen downloads 1.6GB then fails 'parse LlmMetadata' — it must not be offered",
      !ids.contains("litert-community/Qwen2.5-1.5B-Instruct"),
    )
  }

  @Test
  fun keepsUntestedAndGatedModelsSoTheCatalogDoesNotSilentlyShrink() {
    val allowlist = Gson().fromJson(compatFixtureJson, ModelAllowlist::class.java)

    val ids = RelaisModelCatalog.curatedModelsFrom(allowlist).map { it.modelId }

    // Suspected-but-unmeasured: badged "untested" in the selector, still selectable.
    assertTrue(ids.contains("litert-community/DeepSeek-R1-Distill-Qwen-1.5B"))
    // License-gated: badged "token", still selectable once the operator sets one.
    assertTrue(ids.contains("litert-community/Gemma3-1B-IT"))
    assertTrue(ids.contains("litert-community/gemma-4-E2B-it-litert-lm"))
    assertEquals("only the measured failure is withheld", 3, ids.size)
  }

  // --- Pinned G5-TPU refs (spike plan T-4 backlog: Relais-curated, upstream allowlist has none) ---

  @Test
  fun tpuRefsCarryTheAotFilenameMarkerTheEngineLaneKeysOn() {
    assertEquals(2, RelaisModelCatalog.G5_TPU_REFS.size)
    for (ref in RelaisModelCatalog.G5_TPU_REFS) {
      assertTrue(
        "${ref.modelFile} must trip RelaisTpuLane.isTpuCompiledModel or the engine serves it on GPU",
        RelaisTpuLane.isTpuCompiledModel(ref.modelFile),
      )
      assertTrue("pinned commit missing for ${ref.modelId}", ref.commitHash.isNotBlank())
      assertTrue("pinned size missing for ${ref.modelId}", ref.sizeInBytes > 0L)
      assertEquals("TPU refs are HF-sourced", RelaisModelRef.SOURCE_HUGGINGFACE, ref.source)
    }
  }

  @Test
  fun tpuRefTokenBudgetsMatchTheAotKvSizes() {
    val oneB = RelaisModelCatalog.G5_TPU_REFS.first { it.modelId.contains("Gemma3-1B") }
    val e2b = RelaisModelCatalog.G5_TPU_REFS.first { it.modelId.contains("gemma-4-E2B") }
    // 1B is an ekv1280 build; E2B carries no marker and its AOT KV is 4096 = the engine default
    // (verified on-device 2026-07-10).
    assertEquals(1280, RelaisTpuLane.tpuMaxNumTokens(oneB.modelFile, 4096))
    assertEquals(4096, RelaisTpuLane.tpuMaxNumTokens(e2b.modelFile, 4096))
  }
}
