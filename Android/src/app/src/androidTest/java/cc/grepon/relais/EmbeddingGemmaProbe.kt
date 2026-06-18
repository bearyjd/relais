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
import cc.grepon.relais.embed.EmbeddingModelProvisioner
import cc.grepon.relais.embed.EmbeddingTask
import cc.grepon.relais.embed.RelaisEmbedderProvider
import cc.grepon.relais.embed.cosineSimilarity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the Feature #6 EmbeddingGemma embedder. DEFERRED / not a CI gate — gated behind
 * `RELAIS_PROBE=1` AND a runtime `hf_token` arg, so the gated-model download token is NEVER committed
 * and the probe never runs in the JVM unit lane or unattended CI.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.EmbeddingGemmaProbe \
 *     -e RELAIS_PROBE 1 -e hf_token hf_XXXX \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It (1) sets the HF token, (2) provisions the GENERIC seq512 `.tflite` + `sentencepiece.model`
 * (downloads ~180 MB on first run; reused after), (3) loads the bundled-LiteRT graph — which
 * LOGS the introspected I/O signature under tag "RelaisEmbedder" (the authoritative shape check), and
 * (4) asserts the embeddings are well-formed and retrieval-sane:
 *   - dim == 768, finite, unit-L2-norm;
 *   - cosine(query, relevant document) > cosine(query, irrelevant document).
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingGemmaProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun embedsAndRanksRelevantDocAboveIrrelevant() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val token = args.getString("hf_token")
    assumeTrue("Pass -e hf_token <token> for the gated EmbeddingGemma download", !token.isNullOrBlank())

    RelaisConfig.setHfToken(context, token)

    // 1. Provision (download-or-reuse). Logs coarse progress so a multi-minute download is visible.
    val assets = EmbeddingModelProvisioner.ensure(context) { pct ->
      if (pct % 10 == 0) Log.i(TAG, "provision $pct%")
    }
    Log.i(TAG, "assets: model=${assets.modelFile.length()}B tokenizer=${assets.tokenizerFile.length()}B")
    assertTrue("model file should be on disk", assets.modelFile.length() > 0)

    // 2. Embed — the first call lazily loads + introspects (LOGS the signature under tag RelaisEmbedder).
    val embedder = EmbeddingGemmaEmbedder()
    assertEquals("native dim", 768, embedder.dim)

    val query = "What is the capital of France?"
    val relevant = "Paris is the capital and most populous city of France."
    val irrelevant = "Mitochondria are the powerhouse of the cell in eukaryotic biology."

    val qv = embedder.embed(context, listOf(query), EmbeddingTask.QUERY).single()
    val docs = embedder.embed(context, listOf(relevant, irrelevant), EmbeddingTask.DOCUMENT)
    val rv = docs[0]
    val iv = docs[1]

    for ((label, v) in listOf("query" to qv, "relevant" to rv, "irrelevant" to iv)) {
      assertEquals("$label dim", 768, v.size)
      assertTrue("$label must be finite", v.all { it.isFinite() })
      val norm = sqrt(v.fold(0.0) { acc, x -> acc + x.toDouble() * x }).toFloat()
      assertTrue("$label must be unit-norm (was $norm)", abs(norm - 1f) < 1e-2f)
    }

    val simRelevant = cosineSimilarity(qv, rv)
    val simIrrelevant = cosineSimilarity(qv, iv)
    Log.i(TAG, "cosine(query, relevant)=$simRelevant cosine(query, irrelevant)=$simIrrelevant")
    assertTrue(
      "relevant doc must rank above irrelevant ($simRelevant !> $simIrrelevant)",
      simRelevant > simIrrelevant,
    )

    // Performance (bundled CPU/XNNPACK runtime): time a single embed so we know the de-Googled path
    // is fast enough for RAG — embeddings are a single forward pass, not autoregressive generation.
    val reps = 5
    val t0 = System.nanoTime()
    repeat(reps) { embedder.embed(context, listOf(relevant), EmbeddingTask.DOCUMENT) }
    val perEmbedMs = (System.nanoTime() - t0) / 1e6 / reps
    Log.i(TAG, "embed latency (bundled CPU): ${(perEmbedMs * 10).toLong() / 10.0} ms/text over $reps reps")
    assertTrue("embed latency ${perEmbedMs}ms is too high for RAG", perEmbedMs < 2000.0)
  }

  /**
   * Exercises the real PRODUCTION `/v1/embeddings` path end-to-end with NO manual provisioning (the
   * prior version manually called ensure()/prepare(), which masked a wiring bug where the endpoint
   * could never reach 200 because nothing triggered the download). Simulates a fresh node: deletes any
   * on-disk model, registers an unloaded embedder, and asserts the demand-driven flow:
   *   first request → 503 (background provision kicked) → ... → 200 with a 768-length embedding;
   *   an unknown `embedding_task` → 400.
   */
  @Test
  fun embeddingsHttpEndpointProvisionsOnDemandThenReturns200() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val token = args.getString("hf_token")
    assumeTrue("Pass -e hf_token <token> for the gated EmbeddingGemma download", !token.isNullOrBlank())
    RelaisConfig.setHfToken(context, token)

    // FRESH-node simulation: no on-disk model, a fresh (unloaded) embedder registered as startup does.
    EmbeddingModelProvisioner.embedDir(context).deleteRecursively()
    val embedder = EmbeddingGemmaEmbedder()
    RelaisEmbedderProvider.register(embedder)
    assertTrue("a fresh embedder must NOT be available before provisioning", !embedder.isAvailable(context))

    val key = RelaisConfig.apiKey(context)
    val server = RelaisHttpServer(context, port = PORT, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
    try {
      // First request: not loaded → handler kicks a background provision and returns 503-retry.
      val first = post("{\"input\":[\"Paris is the capital of France.\"]}", key)
      assertTrue("first request should be 503 (provisioning), got: ${first.statusLine}", first.statusLine.contains(" 503 "))

      // Poll the same production path until the background download+load finishes and serves 200.
      var ok: HttpResult? = null
      val deadline = System.currentTimeMillis() + 180_000
      while (System.currentTimeMillis() < deadline) {
        Thread.sleep(2000)
        val r = post("{\"input\":[\"Paris is the capital of France.\"]}", key)
        if (r.statusLine.contains(" 200 ")) { ok = r; break }
        assertTrue("while provisioning expect 503, got: ${r.statusLine}", r.statusLine.contains(" 503 "))
      }
      assertTrue("endpoint never reached 200 within the timeout", ok != null)
      assertTrue("body must be an embeddings list", ok!!.body.contains("\"object\":\"list\"") && ok.body.contains("\"embedding\""))
      assertEquals("vector length", 768, ok.body.substringAfter("\"embedding\":[").substringBefore("]").split(",").size)

      val bad = post("{\"input\":[\"x\"],\"embedding_task\":\"not-a-task\"}", key)
      assertTrue("unknown task must be 400, got: ${bad.statusLine}", bad.statusLine.contains(" 400 "))
    } finally {
      server.stop()
    }
  }

  private data class HttpResult(val statusLine: String, val body: String)

  private fun post(json: String, key: String?): HttpResult {
    val raw = "POST /v1/embeddings HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      "Authorization: Bearer $key\r\nContent-Type: application/json\r\n" +
      "Content-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n$json"
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", PORT), 5000)
      sock.soTimeout = 30000
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
    const val TAG = "RelaisEmbedderProbe"
    const val PORT = 18097
  }
}
