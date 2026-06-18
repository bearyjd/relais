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
 * On-device probe for Feature #14 batch. Validates (no LLM needed) the real HTTP handler:
 *   - `POST /v1/batch` with a private-IP webhook → 400 (SSRF guard at submit);
 *   - submit (no webhook) → 202 + job_id, `GET /v1/batch/{id}` → 200 with a status.
 * The HMAC signature is unit-tested (WebhookSignerTest) and the SSRF guard adversarially (WebhookGuardTest).
 * Two DEFERRED gates: the worker RUNNING a job through the LLM, and a real over-the-wire webhook delivery
 * (https — Android blocks cleartext http, so a loopback https receiver / public endpoint is needed).
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.BatchProbe -e RELAIS_PROBE 1 \
 *     cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BatchProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()

  @Test
  fun ssrfRejectionAndSubmitStatusRoundTrip() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    val key = RelaisConfig.apiKey(context)
    val server = RelaisHttpServer(context, port = SERVER_PORT, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
    try {
      // SSRF: a private-IP https webhook is rejected at submit.
      val blocked = post("/v1/batch", """{"messages":[{"role":"user","content":"hi"}],"webhook":"https://10.0.0.1/hook"}""", key)
      assertTrue("private webhook should be 400, got: ${blocked.statusLine}", blocked.statusLine.contains(" 400 "))

      // Submit a job (no webhook) → 202 + job_id; status is reachable.
      val sub = post("/v1/batch", """{"messages":[{"role":"user","content":"hi"}]}""", key)
      assertTrue("submit should be 202, got: ${sub.statusLine}", sub.statusLine.contains(" 202 "))
      val jobId = JSONObject(sub.body).getString("job_id")
      val status = getReq("/v1/batch/$jobId", key)
      assertTrue("status should be 200, got: ${status.statusLine}", status.statusLine.contains(" 200 "))
      assertTrue("status field present", JSONObject(status.body).getString("status").isNotEmpty())
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
      sock.soTimeout = 10000
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
    const val SERVER_PORT = 18095
  }
}
