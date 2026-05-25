/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.relais

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.json.JSONObject

private const val TAG = "RelaisHttpServer"

/**
 * Dependency-free LAN endpoint for the multimodal node (Gate 3).
 *
 * Binds 0.0.0.0:[port] so other LAN devices (and on-device apps) can call it. Routes every
 * request through the resident [RelaisEngine].
 *
 *   GET  /health   -> {"status":"ok","ready":<bool>}
 *   POST /generate  body {"text":..,"image_b64":..?,"audio_b64":..?}
 *                  -> {"response":..,"backend":..,"decode_tok_s":..}
 *
 * Raw HTTP/1.1 (no framework) to avoid adding a server dependency for the spike.
 */
class RelaisHttpServer(private val context: Context, private val port: Int = 8080) {
  private var serverSocket: ServerSocket? = null
  private val pool = Executors.newCachedThreadPool()
  @Volatile private var running = false

  fun start() {
    if (running) return
    running = true
    Thread(
        {
          try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port)) // bind all interfaces for LAN access
            serverSocket = socket
            Log.i(TAG, "Listening on 0.0.0.0:$port")
            while (running) {
              val client = socket.accept()
              pool.execute { handle(client) }
            }
          } catch (e: Exception) {
            if (running) Log.e(TAG, "Server loop error", e)
          }
        },
        "relais-http",
      )
      .start()
  }

  private fun handle(client: java.net.Socket) {
    client.use { sock ->
      try {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]

        var contentLength = 0
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          if (line.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
          }
        }

        when {
          method == "GET" && path.startsWith("/health") -> {
            respond(sock, 200, JSONObject().put("status", "ok").put("ready", RelaisEngine.isReady))
          }
          method == "POST" && path.startsWith("/generate") -> {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
              val r = reader.read(body, read, contentLength - read)
              if (r < 0) break
              read += r
            }
            val json = JSONObject(String(body, 0, read))
            val request =
              RelaisRequest(
                text = json.optString("text", ""),
                imagePng = json.optString("image_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
                audioWav = json.optString("audio_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
              )
            val result = RelaisEngine.generate(context, request)
            respond(
              sock,
              200,
              JSONObject()
                .put("response", result.text)
                .put("backend", result.backend.name)
                .put("decode_tok_s", result.decodeTokensPerSec),
            )
          }
          else -> respond(sock, 404, JSONObject().put("error", "not found"))
        }
      } catch (e: Exception) {
        Log.e(TAG, "Request handling error", e)
        runCatching { respond(sock, 500, JSONObject().put("error", e.message ?: "error")) }
      }
    }
  }

  private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)

  private fun respond(sock: java.net.Socket, status: Int, body: JSONObject) {
    val payload = body.toString().toByteArray()
    val out = sock.getOutputStream()
    val header =
      "HTTP/1.1 $status ${if (status == 200) "OK" else "ERR"}\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: ${payload.size}\r\n" +
        "Connection: close\r\n\r\n"
    out.write(header.toByteArray())
    out.write(payload)
    out.flush()
  }

  fun stop() {
    running = false
    runCatching { serverSocket?.close() }
    pool.shutdownNow()
    Log.i(TAG, "Stopped")
  }
}
