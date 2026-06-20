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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device e2e probe for Feature #14 — the deferred half of [BatchProbe]: the worker actually RUNNING a
 * job through the resident LLM. Submits a batch job, then polls until it reaches a terminal status and
 * asserts a real LLM answer came back.
 *
 * It POSTs to a loopback-bound [RelaisHttpServer] (mirrors [BatchProbe]) — submitting enqueues the job into
 * the shared Room DB and kicks the real WorkManager-driven [worker.BatchWorker], which runs in-process
 * through the warmed engine; the server is just the submit/poll surface, not a stand-in for the worker.
 *
 * Webhook DELIVERY (signed, over-the-wire HTTPS) is exercised by the operator runbook in
 * `.claude/PRPs/plans/ondevice-gates-13-14.plan.md` Task 4 (webhook.site), not here — it needs an external
 * receiver, which an instrumented assertion can't host.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.BatchE2eProbe -e RELAIS_PROBE 1 \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *   # in another shell: adb logcat -s RelaisBatch:* RelaisEngine:*
 */
@RunWith(AndroidJUnit4::class)
class BatchE2eProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun batchJobRunsThroughLlm() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")

    // Warm the engine so the worker's RelaisEngine.generate has a ready model. On a Tensor G5 the resident
    // model must be E2B (E4B first-inference SIGSEGVs — LiteRT-LM #2566).
    RelaisEngine.generate(context, RelaisRequest(text = "hi"))
    val key = RelaisConfig.apiKey(context)

    val server = RelaisHttpServer(context, port = SERVER_PORT, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
    try {
      val submit = post(
        "/v1/batch",
        """{"messages":[{"role":"user","content":"Reply with exactly: batch-ok"}],"temperature":0}""",
        key,
      )
      assertTrue("submit should be 202, got: ${submit.statusLine}", submit.statusLine.contains(" 202 "))
      val jobId = JSONObject(submit.body).getString("job_id")

      // Poll GET /v1/batch/{id} until terminal. Status values are the lowercase strings in
      // data/BatchEntities.kt (BatchStatus): "queued"/"running"/"completed"/"failed".
      var status = ""
      var lastBody = ""
      val deadline = System.currentTimeMillis() + 120_000
      while (System.currentTimeMillis() < deadline) {
        Thread.sleep(3_000)
        val poll = getReq("/v1/batch/$jobId", key)
        assertTrue("status GET should be 200, got: ${poll.statusLine}", poll.statusLine.contains(" 200 "))
        lastBody = poll.body
        status = JSONObject(poll.body).getString("status")
        if (status == STATUS_COMPLETED || status == STATUS_FAILED) break
      }

      assertTrue(
        "batch job should reach '$STATUS_COMPLETED' (got '$status'). A '$STATUS_FAILED' on a Tensor G5 is " +
          "often the thermal-headroom shed truncating the decode — retry on cooler headroom. Body: $lastBody",
        status == STATUS_COMPLETED,
      )

      // Completed result is an OpenAI chat.completion envelope (batch/BatchChat.envelope).
      val content = JSONObject(lastBody)
        .getJSONObject("result")
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
      assertTrue("completed job should carry a real LLM answer, got: \"$content\"", content.isNotBlank())
    } finally {
      server.stop()
    }
  }

  private data class HttpResult(val statusLine: String, val body: String)

  private fun post(path: String, json: String, key: String?): HttpResult = request("POST", path, json, key)

  private fun getReq(path: String, key: String?): HttpResult = request("GET", path, null, key)

  private fun request(method: String, path: String, json: String?, key: String?): HttpResult {
    val head = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\n")
    if (json != null) head.append("Content-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\n")
    head.append("Connection: close\r\n\r\n")
    val raw = if (json != null) head.toString() + json else head.toString()
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", SERVER_PORT), 5000)
      sock.soTimeout = 130000
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
    const val SERVER_PORT = 18096
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
  }
}
