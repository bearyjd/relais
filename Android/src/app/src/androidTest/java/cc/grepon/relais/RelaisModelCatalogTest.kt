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
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Curated-source filter guard: only node-runnable models — LiteRT-LM, LLM-chat, not disabled —
 * survive [RelaisModelCatalog.curatedModelsFrom], and each maps to a self-contained ref. Hermetic:
 * parses a committed fixture; never touches the network (the seam takes an already-fetched
 * allowlist), so it documents the filter independently of the live allowlist contents.
 */
@RunWith(AndroidJUnit4::class)
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
}
