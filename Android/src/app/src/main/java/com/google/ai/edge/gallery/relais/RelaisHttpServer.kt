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
private const val DEFAULT_MODEL = "gemma-4-e4b-it"
private const val RATE_LIMIT = 30 // requests
private const val RATE_WINDOW_MS = 60_000L // per 60s, per client IP

/** Fixed-window per-IP rate limiter. */
private class RateLimiter(private val limit: Int, private val windowMs: Long) {
  private val hits = HashMap<String, ArrayDeque<Long>>()

  @Synchronized
  fun allow(ip: String): Boolean {
    val now = System.currentTimeMillis()
    val q = hits.getOrPut(ip) { ArrayDeque() }
    while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
    if (q.size >= limit) return false
    q.addLast(now)
    return true
  }
}

/**
 * Dependency-free LAN endpoint for the multimodal node (Gate 3 + app-usable API).
 *
 * Binds 0.0.0.0:[port]. Routes through the resident [RelaisEngine].
 *   GET  /health                 -> {"status":"ok","ready":<bool>}            (no auth)
 *   POST /generate                {"text","image_b64?","audio_b64?"}          (auth)
 *   POST /v1/chat/completions     OpenAI-compatible, text+image, stream param (auth)
 *
 * Auth: `Authorization: Bearer <key>` where key = [RelaisConfig.apiKey]. Bodies capped at
 * [MAX_BODY_BYTES]. Streaming uses SSE delimited by connection-close (no chunked framing).
 */
class RelaisHttpServer(private val context: Context, private val port: Int = 8080, private val tls: Boolean = false) {
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
            val socket = buildServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", port)) }
            serverSocket = socket
            Log.i(TAG, "Listening on ${if (tls) "https" else "http"} 0.0.0.0:$port")
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
      try {
        sock.soTimeout = SOCKET_TIMEOUT_MS // don't let an idle/slow client hold a worker thread
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]

        var contentLength = 0
        var authorization: String? = null
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          val lower = line.lowercase()
          when {
            lower.startsWith("content-length:") -> contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            lower.startsWith("authorization:") -> authorization = line.substringAfter(":").trim()
          }
        }

        // Health is open; everything else needs the API key + is rate-limited per client IP.
        if (!(method == "GET" && path.startsWith("/health"))) {
          if (!authorized(authorization)) {
            respond(sock, 401, JSONObject().put("error", "unauthorized"))
            return
          }
          val ip = (sock.inetAddress?.hostAddress) ?: "unknown"
          if (!rateLimiter.allow(ip)) {
            respond(sock, 429, JSONObject().put("error", "rate limit exceeded ($RATE_LIMIT/${RATE_WINDOW_MS / 1000}s)"))
            return
          }
          if (contentLength > MAX_BODY_BYTES) {
            respond(sock, 413, JSONObject().put("error", "request too large"))
            return
          }
        }

        when {
          method == "GET" && path.startsWith("/health") ->
            respond(sock, 200, JSONObject().put("status", "ok").put("ready", RelaisEngine.isReady))

          method == "POST" && path.startsWith("/generate") -> {
            val json = JSONObject(readBody(reader, contentLength))
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

          method == "POST" && path.startsWith("/v1/chat/completions") ->
            handleOpenAi(sock, JSONObject(readBody(reader, contentLength)))

          else -> respond(sock, 404, JSONObject().put("error", "not found"))
        }
      } catch (e: Exception) {
        Log.e(TAG, "Request handling error", e)
        runCatching { respond(sock, 500, JSONObject().put("error", e.message ?: "error")) }
      }
    }
  }

  // --- OpenAI-compatible chat completions ---

  private fun handleOpenAi(sock: java.net.Socket, body: JSONObject) {
    val model = body.optString("model", DEFAULT_MODEL)
    val stream = body.optBoolean("stream", false)
    val request = parseOpenAiRequest(body)
    val id = "chatcmpl-" + System.currentTimeMillis()

    if (!stream) {
      val result = RelaisEngine.generate(context, request)
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
      respond(sock, 200, resp)
      return
    }

    // Streaming: SSE delimited by connection close.
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
    RelaisEngine.generate(context, request) { delta -> sendChunk(delta, null) }
    sendChunk(null, "stop")
    out.write("data: [DONE]\n\n".toByteArray()); out.flush()
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

  private fun authorized(header: String?): Boolean {
    val token = header?.removePrefix("Bearer ")?.trim() ?: return false
    // Constant-time compare to avoid leaking the key via response-timing differences.
    return MessageDigest.isEqual(token.toByteArray(), apiKey.toByteArray())
  }

  private fun readBody(reader: BufferedReader, length: Int): String {
    val buf = CharArray(length)
    var read = 0
    while (read < length) {
      val r = reader.read(buf, read, length - read)
      if (r < 0) break
      read += r
    }
    return String(buf, 0, read)
  }

  private fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)

  private fun respond(sock: java.net.Socket, status: Int, body: JSONObject) {
    val payload = body.toString().toByteArray()
    val out: OutputStream = sock.getOutputStream()
    out.write(
      ("HTTP/1.1 $status ${if (status == 200) "OK" else "ERR"}\r\nContent-Type: application/json\r\n" +
          "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray()
    )
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
