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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device probe for the Feature #11 `GET /v1/clientconfig` endpoint. DEFERRED — no device was
 * connected this session, so this is documentation of the intended check rather than a CI gate. It
 * is `assumeTrue`-gated (skips unless RELAIS_PROBE=1 is passed) so it never runs in the JVM unit
 * lane or unattended CI.
 *
 *   adb shell am instrument -w -e class cc.grepon.relais.ClientConfigEndpointProbe \
 *     -e RELAIS_PROBE 1 cc.grepon.relais.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It starts a loopback-only [RelaisHttpServer] (plain HTTP, the same code path as the production
 * loopback listener) and asserts:
 *   - GET /v1/clientconfig WITH the bearer key  -> 200 + a JSON body carrying clients{...}
 *   - GET /v1/clientconfig WITHOUT the key       -> 401 (the shared auth gate rejects it)
 */
@RunWith(AndroidJUnit4::class)
class ClientConfigEndpointProbe {

  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val args = InstrumentationRegistry.getArguments()
  private val port = 18099 // high loopback port unlikely to collide with the real :8080 listener
  private var server: RelaisHttpServer? = null

  @Before
  fun setUp() {
    assumeTrue("Deferred on-device probe; pass -e RELAIS_PROBE 1 to run", args.getString("RELAIS_PROBE") == "1")
    server = RelaisHttpServer(context, port = port, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300) // let the accept loop bind before the first connect
  }

  @After
  fun tearDown() {
    server?.stop()
    server = null
  }

  @Test
  fun clientConfigReturns200WithBearerKeyAnd401Without() {
    val key = RelaisConfig.apiKey(context)

    val authed = request("GET /v1/clientconfig HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\nConnection: close\r\n\r\n")
    assertTrue("authed request must be 200, got status line: ${authed.statusLine}", authed.statusLine.contains(" 200 "))
    assertTrue("authed body must contain the clients block", authed.body.contains("\"clients\""))
    assertTrue("authed body must contain the base_url", authed.body.contains("\"base_url\""))

    val unauthed = request("GET /v1/clientconfig HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
    assertTrue("unauthed request must be 401, got status line: ${unauthed.statusLine}", unauthed.statusLine.contains(" 401 "))
  }

  private data class HttpResult(val statusLine: String, val body: String)

  /** Minimal raw-socket HTTP/1.1 request to the loopback server; reads the full response. */
  private fun request(raw: String): HttpResult {
    Socket().use { sock ->
      sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
      sock.soTimeout = 5000
      sock.getOutputStream().apply { write(raw.toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      // Drain headers, then the rest is the body (Connection: close terminates it).
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
      }
      val body = reader.readText()
      return HttpResult(statusLine, body)
    }
  }
}
