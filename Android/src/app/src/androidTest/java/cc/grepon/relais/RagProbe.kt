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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.embed.EmbeddingGemmaEmbedder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for Feature #4 RAG. DEFERRED / not a CI gate — gated behind `RELAIS_PROBE=1` AND a
 * runtime `hf_token` (the RAG corpus is embedded with the gated EmbeddingGemma model). Drives the real
 * `/v1/rag` HTTP surface through a loopback [RelaisHttpServer]:
 *   - ingest two documents (France capital; photosynthesis);
 *   - query "capital of France" → the France chunk ranks ABOVE the photosynthesis chunk;
 *   - list shows both documents; delete removes one.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.RagProbe \
 *     -e RELAIS_PROBE 1 -e hf_token hf_XXXX \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RagProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun ingestThenQueryRanksRelevantDocumentFirst() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val token = args.getString("hf_token")
    assumeTrue("Pass -e hf_token <token> for the gated EmbeddingGemma model", !token.isNullOrBlank())
    RelaisConfig.setHfToken(context, token)
    EmbeddingGemmaEmbedder.register()
    EmbeddingGemmaEmbedder.INSTANCE.warmIfProvisioned(context) // model is on disk from the #6 probe

    val key = RelaisConfig.apiKey(context)
    val server = RelaisHttpServer(context, port = PORT, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
    try {
      val franceId = ingestWhenReady(
        "{\"title\":\"france\",\"text\":\"Paris is the capital of France and the most populous city in the country.\"}",
        key,
      )
      ingestWhenReady(
        "{\"title\":\"bio\",\"text\":\"Photosynthesis is the process by which plants convert sunlight into chemical energy.\"}",
        key,
      )

      // Query → the France chunk must rank above the photosynthesis chunk.
      val q = post("POST", "/v1/rag/query", "{\"query\":\"What is the capital of France?\",\"top_k\":2}", key)
      assertTrue("query expected 200, got: ${q.statusLine}", q.statusLine.contains(" 200 "))
      val data = JSONObject(q.body).getJSONArray("data")
      assertTrue("expected at least 2 retrieved chunks", data.length() >= 2)
      val top = data.getJSONObject(0)
      assertTrue("top retrieved chunk should be the France doc, was: ${top.getString("text")}", top.getString("text").contains("France"))
      assertTrue("top score must beat the second", top.getDouble("score") >= data.getJSONObject(1).getDouble("score"))

      // List shows both documents.
      val list = post("GET", "/v1/rag/documents", null, key)
      assertTrue("list expected 200", list.statusLine.contains(" 200 "))
      assertTrue("list should report >= 2 documents", JSONObject(list.body).getInt("document_count") >= 2)

      // Delete the France document.
      val del = post("DELETE", "/v1/rag/documents", "{\"document_id\":$franceId}", key)
      assertEquals("delete echoes the id", franceId, JSONObject(del.body).getLong("document_id"))
      assertTrue("delete expected 200", del.statusLine.contains(" 200 "))
    } finally {
      server.stop()
    }
  }

  /** Ingests, retrying past the 503 provisioning window until the embedder loads; returns the document id. */
  private fun ingestWhenReady(json: String, key: String?): Long {
    val deadline = System.currentTimeMillis() + 180_000
    while (true) {
      val r = post("POST", "/v1/rag/documents", json, key)
      if (r.statusLine.contains(" 200 ")) return JSONObject(r.body).getLong("document_id")
      assertTrue("ingest expected 200/503, got: ${r.statusLine}", r.statusLine.contains(" 503 "))
      assertTrue("embedder never became ready within the timeout", System.currentTimeMillis() < deadline)
      Thread.sleep(2000)
    }
  }

  private data class HttpResult(val statusLine: String, val body: String)

  private fun post(method: String, path: String, json: String?, key: String?): HttpResult {
    val head = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\n")
    if (json != null) {
      head.append("Content-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\n")
    }
    head.append("Connection: close\r\n\r\n")
    val raw = if (json != null) head.toString() + json else head.toString()
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", PORT), 5000)
      sock.soTimeout = 60000
      sock.getOutputStream().apply { write(raw.toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
      }
      return HttpResult(statusLine, reader.readText())
    }
  }

  private companion object {
    const val PORT = 18096
  }
}
