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

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.embed.EmbeddingGemmaEmbedder
import cc.grepon.relais.nodetools.CalculatorTool
import cc.grepon.relais.nodetools.RagSearchTool
import cc.grepon.relais.rag.RagStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the Feature #9 node-side tools. Validates the DEVICE-dependent built-in
 * (`rag_search`, which runs the on-device embedder over the RAG corpus) plus a `calculator` sanity,
 * directly through the [cc.grepon.relais.nodetools] interfaces — deterministic, no LLM needed.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.NodeToolsProbe \
 *     -e RELAIS_PROBE 1 -e hf_token hf_XXXX \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * NOTE: the full LLM round-trip (the model deciding to call a built-in → the node executing it → the
 * grounded re-generation via /v1/chat/completions with `node_tools:true`) is a DEFERRED gate — it
 * depends on the resident LLM emitting a tool call, which is model-behaviour-dependent on Gemma-4 E2B.
 */
@RunWith(AndroidJUnit4::class)
class NodeToolsProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun ragSearchAndCalculatorBuiltinsWorkOnDevice() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val token = args.getString("hf_token")
    assumeTrue("Pass -e hf_token <token> for the gated EmbeddingGemma model", !token.isNullOrBlank())
    RelaisConfig.setHfToken(context, token)

    // calculator is pure — confirm it also runs through the tool interface on-device.
    assertEquals("6*7 = 42", runBlocking { CalculatorTool.execute(context, JSONObject("""{"expression":"6*7"}""")) })

    // rag_search: warm the (already-downloaded) embedder, ingest a doc, then retrieve via the tool.
    EmbeddingGemmaEmbedder.register()
    val embedder = EmbeddingGemmaEmbedder.INSTANCE
    embedder.warmIfProvisioned(context)
    val deadline = System.currentTimeMillis() + 120_000
    while (!embedder.isAvailable(context) && System.currentTimeMillis() < deadline) Thread.sleep(1000)
    assumeTrue("embedder did not become ready (model not provisioned?)", embedder.isAvailable(context))

    runBlocking { RagStore.ingest(context, "probe-nt", "The Eiffel Tower is a wrought-iron tower in Paris, France.", embedder) }
    val out = runBlocking { RagSearchTool.execute(context, JSONObject("""{"query":"Where is the Eiffel Tower?","top_k":3}""")) }
    Log.i(TAG, "rag_search -> $out")
    assertTrue("rag_search should surface the ingested passage, got: $out", out.contains("Eiffel") || out.contains("Paris"))
  }

  private companion object {
    const val TAG = "RelaisNodeToolsProbe"
  }
}
