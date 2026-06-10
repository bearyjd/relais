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

package cc.grepon.relais.relais

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RelaisHttpServer"
private const val TLS_KEY_ALIAS = "relais-tls"
private const val TLS_KEYSTORE_FILE = "relais_tls.p12"
private const val SOCKET_TIMEOUT_MS = 15_000 // read timeout: bounds slow/idle clients (slowloris)
private const val MAX_CONNECTIONS = 16 // cap worker threads (single-engine node serializes anyway)
private const val MAX_BODY_BYTES = 32 * 1024 * 1024 // 32 MB cap (base64 image/audio)
private const val MAX_HEADER_LINES = 64 // bound header parsing (slowloris / header-flood)
private const val MAX_HEADER_BYTES = 16 * 1024
private const val DEFAULT_MODEL = "gemma-4-e4b-it"
private const val RATE_LIMIT = 30 // requests
private const val RATE_WINDOW_MS = 60_000L // per 60s, per client IP
private const val MAX_TRACKED_IPS = 4096 // bound the rate-limiter map (memory-exhaustion DoS, H4)

/** Fixed-window per-IP rate limiter with stale-entry eviction (bounds the tracking map). */
private class RateLimiter(private val limit: Int, private val windowMs: Long) {
  private val hits = HashMap<String, ArrayDeque<Long>>()

  @Synchronized
  fun allow(ip: String): Boolean {
    val now = System.currentTimeMillis()
    // Evict stale/empty buckets so a source-address sweep (trivial over IPv6) can't grow the map
    // without bound -> OOM -> watchdog restart loop (security H4). Threshold-triggered amortized sweep.
    if (hits.size > MAX_TRACKED_IPS) {
      val it = hits.entries.iterator()
      while (it.hasNext()) {
        val q = it.next().value
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.isEmpty()) it.remove()
      }
    }
    val q = hits.getOrPut(ip) { ArrayDeque() }
    while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
    RateLimiterStats.trackedIps = hits.size
    if (q.size >= limit) return false
    q.addLast(now)
    return true
  }
}

/**
 * Dependency-free LAN endpoint for the multimodal node (Gate 3 + app-usable API).
 *
 * Binds [bindAddr]:[port]. Routes through the resident [RelaisEngine].
 *   GET  /health                 -> {"status","ready","thermal_state"}          (no auth)
 *   GET  /metrics                 Prometheus text (or JSON via Accept)           (auth)
 *   POST /generate                {"text","image_b64?","audio_b64?"}            (auth)
 *   POST /v1/chat/completions     OpenAI-compatible, text+image, stream param   (auth)
 *
 * Auth: `Authorization: Bearer <key>` where key = [RelaisConfig.apiKey]. Bodies capped at
 * [MAX_BODY_BYTES]. Streaming uses SSE delimited by connection-close (no chunked framing).
 * Under thermal backpressure ([ThermalGovernor.shouldShed]), inference endpoints return 503 +
 * Retry-After rather than cooking the device.
 *
 * Network posture (security C1): the node runs HTTP on loopback (local app/dev only) and HTTPS on
 * the LAN, so the bearer key is never sent in cleartext across the network. See [RelaisNodeService].
 */
class RelaisHttpServer(
  private val context: Context,
  private val port: Int = 8080,
  private val tls: Boolean = false,
  private val bindAddr: String = "127.0.0.1", // safe default; callers opt into 0.0.0.0 for TLS (C1)
) {
  private var serverSocket: ServerSocket? = null
  private val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS)
  @Volatile private var running = false
  private val apiKey by lazy { RelaisConfig.apiKey(context) }
  private val rateLimiter = RateLimiter(RATE_LIMIT, RATE_WINDOW_MS)

  fun start() {
    if (running) return
    running = true
    Thread(
        {
          try {
            val socket = buildServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddr, port)) }
            serverSocket = socket
            Log.i(TAG, "Listening on ${if (tls) "https" else "http"} $bindAddr:$port")
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

  /**
   * Plain or TLS server socket. The TLS cert is a self-signed LAN cert (clients use `curl -k`),
   * generated once at first use into an app-private PKCS12 ([TLS_KEYSTORE_FILE]) protected by a
   * random per-install password ([RelaisConfig.tlsKeystorePassword]). No key material or password
   * is bundled in the APK or committed to the repo.
   *
   * A **software** RSA key is used deliberately: AndroidKeyStore keys (RSA and EC) cannot sign the
   * TLS server handshake through conscrypt's native upcall on-device, so the key is generated in
   * software and stored in the app's private files dir.
   */
  private fun buildServerSocket(): ServerSocket {
    if (!tls) return ServerSocket()
    val pass = RelaisConfig.tlsKeystorePassword(context).toCharArray()
    val ks = loadOrCreateKeystore(pass)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
    val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    return ctx.serverSocketFactory.createServerSocket()
  }

  /** Loads the app-private TLS keystore, generating a fresh self-signed cert on first use. */
  private fun loadOrCreateKeystore(pass: CharArray): KeyStore {
    val file = File(context.filesDir, TLS_KEYSTORE_FILE)
    val ks = KeyStore.getInstance("PKCS12")
    if (file.exists()) {
      file.inputStream().use { ks.load(it, pass) }
      return ks
    }
    ks.load(null, pass)
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    ks.setKeyEntry(TLS_KEY_ALIAS, keyPair.private, pass, arrayOf(selfSignedCert(keyPair)))
    file.outputStream().use { ks.store(it, pass) }
    Log.i(TAG, "Generated self-signed TLS keystore at ${file.path}")
    return ks
  }

  /** Builds a 30-year self-signed `CN=relais-node` X509 cert for [keyPair] (SHA256withRSA). */
  private fun selfSignedCert(keyPair: KeyPair): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - 60_000) // small backdate for client clock skew
    val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
    val name = X500Name("CN=relais-node")
    val builder =
      JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), notBefore, notAfter, name, keyPair.public)
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  private fun handle(client: java.net.Socket) {
    client.use { sock ->
      var endpoint = "other"
      try {
        sock.soTimeout = SOCKET_TIMEOUT_MS // don't let an idle/slow client hold a worker thread
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]
        endpoint = endpointLabel(path)

        // Records the request metric alongside the response (security M6: labels are normalized).
        fun reply(status: Int, body: JSONObject, headers: List<String> = emptyList()) {
          RelaisMetrics.recordRequest(endpoint, status)
          respond(sock, status, body, headers)
        }

        var contentLength = 0
        var authorization: String? = null
        var accept: String? = null
        var headerLines = 0
        var headerBytes = 0
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          headerLines++
          headerBytes += line.length
          if (headerLines > MAX_HEADER_LINES || headerBytes > MAX_HEADER_BYTES) {
            reply(431, JSONObject().put("error", "header too large"))
            return
          }
          val lower = line.lowercase()
          when {
            lower.startsWith("content-length:") -> contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            lower.startsWith("authorization:") -> authorization = line.substringAfter(":").trim()
            lower.startsWith("accept:") -> accept = lower.substringAfter(":").trim()
          }
        }

        // Health is open; everything else needs the API key + is rate-limited per client IP.
        if (!(method == "GET" && path.startsWith("/health"))) {
          if (!authorized(authorization)) {
            reply(401, JSONObject().put("error", "unauthorized"))
            return
          }
          val ip = (sock.inetAddress?.hostAddress) ?: "unknown"
          if (!rateLimiter.allow(ip)) {
            reply(429, JSONObject().put("error", "rate limit exceeded ($RATE_LIMIT/${RATE_WINDOW_MS / 1000}s)"))
            return
          }
          if (contentLength > MAX_BODY_BYTES) {
            reply(413, JSONObject().put("error", "request too large"))
            return
          }
        }

        when {
          method == "GET" && path.startsWith("/health") ->
            reply(
              200,
              JSONObject()
                .put("status", "ok")
                .put("ready", RelaisEngine.isReady)
                .put("thermal_state", ThermalGovernor.statusValue),
            )

          method == "GET" && path.startsWith("/metrics") -> {
            RelaisMetrics.recordRequest(endpoint, 200)
            if (accept?.contains("application/json") == true) {
              respond(sock, 200, RelaisMetrics.renderJson(context))
            } else {
              respondText(sock, 200, RelaisMetrics.renderProm(context), "text/plain; version=0.0.4")
            }
          }

          method == "POST" && path.startsWith("/generate") -> {
            if (shedIfHot(::reply)) return
            val json = JSONObject(readBody(reader, contentLength))
            val request =
              RelaisRequest(
                text = json.optString("text", ""),
                imagePng = json.optString("image_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
                audioWav = json.optString("audio_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
              )
            val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
            reply(
              200,
              JSONObject()
                .put("response", result.text)
                .put("backend", result.backend.name)
                .put("decode_tok_s", result.decodeTokensPerSec),
            )
          }

          method == "POST" && path.startsWith("/v1/chat/completions") -> {
            if (shedIfHot(::reply)) return
            handleOpenAi(sock, JSONObject(readBody(reader, contentLength)))
          }

          else -> reply(404, JSONObject().put("error", "not found"))
        }
      } catch (e: Exception) {
        Log.e(TAG, "Request handling error", e)
        RelaisMetrics.recordRequest(endpoint, 500)
        // Generic client message; detail stays in logcat (don't leak internals to the caller).
        runCatching { respond(sock, 500, JSONObject().put("error", "internal error")) }
      }
    }
  }

  /** If the device is thermally shedding, answer 503 + Retry-After and return true. */
  private fun shedIfHot(reply: (Int, JSONObject, List<String>) -> Unit): Boolean {
    if (!ThermalGovernor.shouldShed()) return false
    RelaisMetrics.recordShed()
    val retry = ThermalGovernor.retryAfterSeconds() + (0..4).random() // jitter avoids retry stampede
    reply(
      503,
      JSONObject().put("error", "thermal backpressure; retry later").put("retry_after_seconds", retry),
      listOf("Retry-After: $retry"),
    )
    return true
  }

  // --- OpenAI-compatible chat completions ---

  private fun handleOpenAi(sock: java.net.Socket, body: JSONObject) {
    val model = body.optString("model", DEFAULT_MODEL)
    val stream = body.optBoolean("stream", false)
    val request = parseOpenAiRequest(body)
    val id = "chatcmpl-" + System.currentTimeMillis()

    if (!stream) {
      val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
      val resp =
        JSONObject()
          .put("id", id)
          .put("object", "chat.completion")
          .put("model", model)
          .put("choices", JSONArray().put(
            JSONObject()
              .put("index", 0)
              .put("message", JSONObject().put("role", "assistant").put("content", result.text))
              .put("finish_reason", "stop")))
      RelaisMetrics.recordRequest("/v1/chat/completions", 200)
      respond(sock, 200, resp)
      return
    }

    // Streaming: SSE delimited by connection close. Once the 200 header is committed, an inference
    // failure must NOT unwind to the outer catch (that would write a second HTTP status into the
    // stream and double-count the request) — post-commit errors become an SSE error event (HIGH-1).
    RelaisMetrics.recordRequest("/v1/chat/completions", 200)
    val out = sock.getOutputStream()
    out.write(
      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
          "Connection: close\r\n\r\n").toByteArray()
    )
    out.flush()
    fun sendChunk(delta: String?, finish: String?) {
      val choice = JSONObject().put("index", 0)
        .put("delta", if (delta != null) JSONObject().put("content", delta) else JSONObject())
        .put("finish_reason", finish ?: JSONObject.NULL)
      val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
        .put("model", model).put("choices", JSONArray().put(choice))
      out.write("data: $chunk\n\n".toByteArray()); out.flush()
    }
    try {
      // onToken throwing (broken pipe) and thermal-truncate both cooperatively stop the decode.
      RelaisEngine.generate(
        context,
        request,
        onToken = { delta -> sendChunk(delta, null) },
        shouldCancel = { ThermalGovernor.shouldTruncate() },
      )
      sendChunk(null, "stop")
      out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    } catch (e: Exception) {
      Log.e(TAG, "stream error after headers committed", e)
      runCatching { out.write("data: {\"error\":\"stream aborted\"}\n\n".toByteArray()); out.flush() }
    }
  }

  /** Extracts text + first image + first audio from the last user message (string or parts array). */
  private fun parseOpenAiRequest(body: JSONObject): RelaisRequest {
    val messages = body.optJSONArray("messages") ?: JSONArray()
    var text = ""
    var image: ByteArray? = null
    var audio: ByteArray? = null
    for (i in messages.length() - 1 downTo 0) {
      val msg = messages.optJSONObject(i) ?: continue
      if (msg.optString("role") != "user") continue
      when (val content = msg.opt("content")) {
        is String -> text = content
        is JSONArray ->
          for (j in 0 until content.length()) {
            val part = content.optJSONObject(j) ?: continue
            when (part.optString("type")) {
              "text" -> text = part.optString("text")
              "image_url" ->
                image = part.optJSONObject("image_url")?.optString("url")?.let { dataUriBytes(it) } ?: image
              "input_audio" ->
                audio = part.optJSONObject("input_audio")?.optString("data")?.let { decode(it) } ?: audio
            }
          }
      }
      break
    }
    return RelaisRequest(text = text, imagePng = image, audioWav = audio)
  }

  private fun dataUriBytes(url: String): ByteArray? =
    runCatching { decode(if (url.startsWith("data:")) url.substringAfter(",") else url) }.getOrNull()

  // --- helpers ---

  private fun endpointLabel(path: String): String =
    when {
      path.startsWith("/health") -> "/health"
      path.startsWith("/metrics") -> "/metrics"
      path.startsWith("/generate") -> "/generate"
      path.startsWith("/v1/chat/completions") -> "/v1/chat/completions"
      else -> "other"
    }

  private fun authorized(header: String?): Boolean {
    val token = header?.removePrefix("Bearer ")?.trim() ?: return false
    // Constant-time compare to avoid leaking the key via response-timing differences.
    return MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())
  }

  /**
   * Reads up to [length] (capped) chars of body without pre-allocating the client-claimed size:
   * a malicious large Content-Length no longer forces a multi-MB allocation per worker (security M1).
   */
  private fun readBody(reader: BufferedReader, length: Int): String {
    if (length <= 0) return ""
    val cap = minOf(length, MAX_BODY_BYTES)
    val sb = StringBuilder(minOf(cap, 64 * 1024))
    val chunk = CharArray(8192)
    var remaining = cap
    while (remaining > 0) {
      val r = reader.read(chunk, 0, minOf(chunk.size, remaining))
      if (r < 0) break
      sb.append(chunk, 0, r)
      remaining -= r
    }
    return sb.toString()
  }

  private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)

  private fun reason(status: Int): String =
    when (status) {
      200 -> "OK"
      401 -> "Unauthorized"
      404 -> "Not Found"
      413 -> "Payload Too Large"
      429 -> "Too Many Requests"
      431 -> "Request Header Fields Too Large"
      500 -> "Internal Server Error"
      503 -> "Service Unavailable"
      else -> "ERR"
    }

  private fun respond(sock: java.net.Socket, status: Int, body: JSONObject, extraHeaders: List<String> = emptyList()) {
    respondText(sock, status, body.toString(), "application/json", extraHeaders)
  }

  private fun respondText(
    sock: java.net.Socket,
    status: Int,
    body: String,
    contentType: String,
    extraHeaders: List<String> = emptyList(),
  ) {
    val payload = body.toByteArray()
    val out: OutputStream = sock.getOutputStream()
    val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: $contentType\r\n")
    head.append("Content-Length: ${payload.size}\r\nConnection: close\r\n")
    extraHeaders.forEach { head.append(it).append("\r\n") }
    head.append("\r\n")
    out.write(head.toString().toByteArray())
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
