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

package cc.grepon.relais

import android.content.Context
import android.util.Base64
import android.util.Log
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.templates.PromptTemplateStore
import cc.grepon.relais.templates.parseTemplateId
import cc.grepon.relais.templates.parseTemplateMode
import cc.grepon.relais.templates.resolveSystemPrompt
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
import java.util.concurrent.Semaphore
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
// Structured output (response_format): at most MAX+1 inference calls per request. Latency-bounded;
// constrained decoding is not a hard guarantee so we validate + repair + retry. (feature-05)
private const val MAX_STRUCTURED_OUTPUT_RETRIES = 2

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
 *   GET  /v1/models               OpenAI-compatible model list                   (auth)
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
  // Fair semaphore: FIFO among admitted threads; tryAcquire() is the runtime embodiment of admit().
  private val admissionGate = Semaphore(QUEUE_CAPACITY, /* fair = */ true)

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

          method == "GET" && path == "/" -> {
            // Auth-gated (bearer required — same gate as /metrics; /health is the only open route).
            // Reads only already-collected metrics; no state change. Scriptless + escaped; strict
            // security headers added via extraHeaders (CSP no script-src, nosniff, X-Frame DENY).
            RelaisMetrics.recordRequest(endpoint, 200)
            val metricsJson = RelaisMetrics.renderJson(context)
            val dashStatus = assembleDashboardStatus(
              engineReady = RelaisEngine.isReady,
              startupInProgress = RelaisEngine.startupInProgress,
              thermalStatus = ThermalGovernor.statusValue,
              decodeTokensPerSec = metricsJson.optDouble("decode_tokens_per_second", 0.0),
              currentModelId = RelaisConfig.modelId(context),
              uptimeSeconds = metricsJson.optDouble("uptime_seconds", 0.0),
              queueDepth = RelaisMetrics.queueDepth(),
              errorsTotal = metricsJson.optLong("errors_total", 0L),
              shedTotal = metricsJson.optLong("shed_total", 0L),
              recentRequests = RelaisMetrics.recentRequests(),
            )
            val html = renderDashboardHtml(dashStatus)
            respondText(
              sock, 200, html, "text/html; charset=utf-8",
              listOf(
                // Scriptless page — no script-src at all; default-src 'none' blocks everything else.
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
                "X-Content-Type-Options: nosniff",
                "X-Frame-Options: DENY",
                "Referrer-Policy: no-referrer",
              ),
            )
          }

          method == "GET" && path.startsWith("/metrics") -> {
            RelaisMetrics.recordRequest(endpoint, 200)
            if (accept?.contains("application/json") == true) {
              respond(sock, 200, RelaisMetrics.renderJson(context))
            } else {
              respondText(sock, 200, RelaisMetrics.renderProm(context), "text/plain; version=0.0.4")
            }
          }

          method == "POST" && path.startsWith("/generate") -> {
            if (shedIfHot(::reply)) return         // thermal 503 wins first
            if (rejectIfQueueFull(::reply)) return  // admission 429 second
            // Permit acquired — release in finally so a crash or timeout never leaks a slot.
            // Per-endpoint latency (Feature #10): time the whole inference branch so EVERY outcome
            // (success, timeout, error) lands in the /generate-labeled histogram. The global tail
            // guarantee stays in the engine's finally (recordLatency) — un-double-counted.
            val inferStartNs = System.nanoTime()
            try {
              val json = JSONObject(readBody(reader, contentLength))
              if (PromptTemplateStore.isUnknown(context, parseTemplateId(json))) {
                reply(400, JSONObject().put("error", "unknown template").put("code", "unknown_template"))
                return
              }
              val request =
                RelaisRequest(
                  text = json.optString("text", ""),
                  imagePng = json.optString("image_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
                  audioWav = json.optString("audio_b64").takeIf { it.isNotEmpty() }?.let { decode(it) },
                  // Optional `system` + `template`/`x_relais_template` selector (default-off: both
                  // absent → null → engine default, unchanged behavior).
                  systemPrompt = resolveSystemPrompt(
                    explicitSystem = json.optString("system").takeIf { it.isNotBlank() },
                    template = PromptTemplateStore.resolve(context, parseTemplateId(json)),
                    mode = parseTemplateMode(json),
                  ),
                )
              val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
              reply(
                200,
                JSONObject()
                  .put("response", result.text)
                  .put("backend", result.backend.name)
                  .put("decode_tok_s", result.decodeTokensPerSec),
              )
            } finally {
              RelaisMetrics.recordEndpointLatency("/generate", (System.nanoTime() - inferStartNs) / 1e9)
              admissionGate.release()
            }
          }

          method == "GET" && path.startsWith("/v1/models") -> {
            val refs = RelaisModelCatalog.curatedModels()
            val fallback = RelaisConfig.modelId(context)
            reply(200, buildModelsResponse(refs, fallback))
          }

          method == "POST" && path.startsWith("/v1/chat/completions") -> {
            if (shedIfHot(::reply)) return         // thermal 503 wins first
            if (rejectIfQueueFull(::reply)) return  // admission 429 second (before SSE 200 header)
            // Permit acquired — release in finally; handleOpenAi may commit the SSE 200 header
            // before returning, so post-commit errors are handled inside handleOpenAi itself.
            // Per-endpoint latency (Feature #10): time the whole chat-completions branch, mirroring
            // /generate, so its p95 separates from /generate's in the labeled histogram.
            val inferStartNs = System.nanoTime()
            try {
              handleOpenAi(sock, JSONObject(readBody(reader, contentLength)))
            } finally {
              RelaisMetrics.recordEndpointLatency("/v1/chat/completions", (System.nanoTime() - inferStartNs) / 1e9)
              admissionGate.release()
            }
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

  /**
   * If the admission queue is full, answer 429 + Retry-After, record the counter, and return true.
   * Must be called AFTER [shedIfHot] — thermal 503 takes precedence over queue 429.
   */
  private fun rejectIfQueueFull(reply: (Int, JSONObject, List<String>) -> Unit): Boolean {
    if (admissionGate.tryAcquire()) return false // admitted — caller must release in a finally
    // Derive depth from the gate's own state: availablePermits()==0 at saturation, so
    // QUEUE_CAPACITY - 0 == QUEUE_CAPACITY, giving admit(QUEUE_CAPACITY, QUEUE_CAPACITY) -> the
    // load-scaled Retry-After. Using RelaisMetrics.queueDepth() instead would be decoupled from
    // the gate and could read below capacity, collapsing to the MIN_RETRY_AFTER floor (bug fix).
    val depth = QUEUE_CAPACITY - admissionGate.availablePermits()
    val decision = admit(depth, QUEUE_CAPACITY)
    val retryAfter = if (decision is AdmissionDecision.Reject) decision.retryAfterSeconds
                     else MIN_RETRY_AFTER
    RelaisMetrics.recordQueueReject()
    reply(
      429,
      JSONObject()
        .put("error", "admission queue full; retry later")
        .put("code", "queue_full")
        .put("retry_after_seconds", retryAfter),
      listOf("Retry-After: $retryAfter"),
    )
    return true
  }

  // --- OpenAI-compatible chat completions ---

  private fun handleOpenAi(sock: java.net.Socket, body: JSONObject) {
    val model = body.optString("model", DEFAULT_MODEL)
    val stream = body.optBoolean("stream", false)
    // Reject an unknown template id up front (all sub-paths) so a client never silently gets the
    // default persona when it asked for a specific one.
    if (PromptTemplateStore.isUnknown(context, parseTemplateId(body))) {
      RelaisMetrics.recordRequest("/v1/chat/completions", 400)
      respond(sock, 400, JSONObject().put("error", "unknown template").put("code", "unknown_template"))
      return
    }
    val request = parseOpenAiRequest(body)
    val id = "chatcmpl-" + System.currentTimeMillis()

    // Tool path: a request advertising tools OR replying with tool results uses the dedicated
    // BLOCKING tool completion (native LiteRT-LM tool API), not the streaming/text paths.
    if (request.tools.isNotEmpty() || request.toolResults.isNotEmpty()) {
      handleToolCompletion(sock, request, model, id, stream)
      return
    }

    // Structured output (response_format): json_object / json_schema run a validate + repair + retry
    // loop (json_schema reuses the native tool path — schema as a tool, args become content).
    val format = RelaisStructuredOutput.parseResponseFormat(body)
    if (format == null) {
      RelaisMetrics.recordRequest("/v1/chat/completions", 400)
      respond(sock, 400, JSONObject().put("error", "unsupported response_format"))
      return
    }
    if (format !is RelaisStructuredOutput.ResponseFormat.Text) {
      handleStructuredCompletion(sock, request, model, id, stream, format)
      return
    }

    if (!stream) {
      val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
      // reasoning_content (OpenAI/DeepSeek convention) carries the model's thinking channel; present
      // only when the client opted in via reasoning_effort AND the model emitted reasoning.
      val assistantMessage = JSONObject().put("role", "assistant").put("content", result.text)
      result.reasoning?.let { assistantMessage.put("reasoning_content", it) }
      val resp =
        JSONObject()
          .put("id", id)
          .put("object", "chat.completion")
          .put("created", System.currentTimeMillis() / 1000)
          .put("model", model)
          .put("choices", JSONArray().put(
            JSONObject()
              .put("index", 0)
              .put("message", assistantMessage)
              .put("finish_reason", "stop")))
          .put("usage", buildUsageObject(request.text, result.completionTokens))
          .put("x_relais_usage_note", "prompt_tokens_estimated")
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
    fun emitDelta(delta: JSONObject, finish: String?) {
      val choice = JSONObject().put("index", 0)
        .put("delta", delta)
        .put("finish_reason", finish ?: JSONObject.NULL)
      val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
        .put("model", model).put("choices", JSONArray().put(choice))
      out.write("data: $chunk\n\n".toByteArray()); out.flush()
    }
    fun sendChunk(delta: String?, finish: String?) =
      emitDelta(if (delta != null) JSONObject().put("content", delta) else JSONObject(), finish)
    try {
      // onToken throwing (broken pipe) and thermal-truncate both cooperatively stop the decode.
      // Capture result so completionTokens is available for the final usage chunk. When the client
      // opted into thinking, reasoning deltas stream first as delta.reasoning_content (OpenAI/DeepSeek
      // convention) ahead of the visible content deltas.
      val result = RelaisEngine.generate(
        context,
        request,
        onToken = { delta -> sendChunk(delta, null) },
        shouldCancel = { ThermalGovernor.shouldTruncate() },
        onReasoning = { r -> emitDelta(JSONObject().put("reasoning_content", r), null) },
      )
      // Final chunk: finish_reason="stop" + usage block (always included for client compatibility).
      // OpenAI spec allows usage on the terminal chunk; we always emit it regardless of
      // stream_options.include_usage — intermediate chunks carry no usage field.
      val finalChunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1000)
        .put("model", model)
        .put("choices", JSONArray().put(
          JSONObject().put("index", 0)
            .put("delta", JSONObject())
            .put("finish_reason", "stop")))
        .put("usage", buildUsageObject(request.text, result.completionTokens))
        .put("x_relais_usage_note", "prompt_tokens_estimated")
      out.write("data: $finalChunk\n\n".toByteArray()); out.flush()
      out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    } catch (e: Exception) {
      Log.e(TAG, "stream error after headers committed", e)
      runCatching { out.write("data: {\"error\":\"stream aborted\"}\n\n".toByteArray()); out.flush() }
    }
  }

  /**
   * Blocking OpenAI tool-completion path (native LiteRT-LM tool API). Handles both the first turn
   * (tools advertised -> model may emit `tool_calls`, finish_reason="tool_calls") and the round-trip
   * (tool results supplied -> model produces a final answer, finish_reason="stop"). No SSE deltas:
   * even in stream mode the body is a single chunk, because the blocking sendMessage returns the
   * whole reply at once. Both stream and non-stream commit a 200 before producing the body, so
   * post-header errors in the stream path become an SSE error event (mirrors [handleOpenAi]).
   */
  private fun handleToolCompletion(
    sock: java.net.Socket,
    request: RelaisRequest,
    model: String,
    id: String,
    stream: Boolean,
  ) {
    if (!stream) {
      val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
      val (message, finishReason) = buildToolAssistantMessage(result, streaming = false)
      val resp =
        JSONObject()
          .put("id", id)
          .put("object", "chat.completion")
          .put("created", System.currentTimeMillis() / 1000)
          .put("model", model)
          .put("choices", JSONArray().put(
            JSONObject()
              .put("index", 0)
              .put("message", message)
              .put("finish_reason", finishReason)))
          .put("usage", buildUsageObject(request.text, result.completionTokens))
          .put("x_relais_usage_note", "prompt_tokens_estimated")
      RelaisMetrics.recordRequest("/v1/chat/completions", 200)
      respond(sock, 200, resp)
      return
    }

    // Streaming: one chunk only. Commit the 200 SSE header, then emit a single delta carrying the
    // full assistant message (with tool_calls when present), then [DONE]. Post-header errors become
    // an SSE error event (the outer catch would double-count + double-write a status otherwise).
    RelaisMetrics.recordRequest("/v1/chat/completions", 200)
    val out = sock.getOutputStream()
    out.write(
      ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
          "Connection: close\r\n\r\n").toByteArray()
    )
    out.flush()
    try {
      val result = RelaisEngine.generate(context, request, shouldCancel = { ThermalGovernor.shouldTruncate() })
      val (message, finishReason) = buildToolAssistantMessage(result, streaming = true)
      val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
        .put("created", System.currentTimeMillis() / 1000)
        .put("model", model)
        .put("choices", JSONArray().put(
          JSONObject().put("index", 0)
            .put("delta", message)
            .put("finish_reason", finishReason)))
      out.write("data: $chunk\n\n".toByteArray()); out.flush()
      out.write("data: [DONE]\n\n".toByteArray()); out.flush()
    } catch (e: Exception) {
      Log.e(TAG, "tool stream error after headers committed", e)
      runCatching { out.write("data: {\"error\":\"stream aborted\"}\n\n".toByteArray()); out.flush() }
    }
  }

  /**
   * Structured-output (`response_format`) completion. Non-streaming only (v1; stream+format -> 400).
   *  - json_schema: advertise the caller's schema as a single native tool; the model's tool-call
   *    arguments become the response content (the native path emits clean schema-conforming JSON).
   *  - json_object: prompt the model for JSON only.
   * Both validate the candidate; on failure attempt a prose/fence repair, then retry with a
   * correction nudge up to [MAX_STRUCTURED_OUTPUT_RETRIES]. Exhaustion -> HTTP 422.
   */
  private fun handleStructuredCompletion(
    sock: java.net.Socket,
    request: RelaisRequest,
    model: String,
    id: String,
    stream: Boolean,
    format: RelaisStructuredOutput.ResponseFormat,
  ) {
    if (stream) {
      RelaisMetrics.recordRequest("/v1/chat/completions", 400)
      respond(sock, 400, JSONObject().put("error", "stream and response_format cannot be combined"))
      return
    }
    val isSchema = format is RelaisStructuredOutput.ResponseFormat.JsonSchema
    var lastCandidate: String? = null
    var attempt = 0
    while (true) {
      // On retry, show the model its own prior output so it can correct (small models reproduce the
      // same error if just told "try again"). lastCandidate holds the previous attempt's raw output.
      val nudge =
        if (attempt == 0) ""
        else "\n\nYour previous reply was:\n${lastCandidate ?: "(no output)"}\n\nThat was not valid for the " +
          "requested format. Reply with ONLY a single valid JSON value, no prose, no code fences."
      val req =
        if (format is RelaisStructuredOutput.ResponseFormat.JsonSchema) {
          request.copy(
            tools = listOf(ToolSpec(format.name, RelaisStructuredOutput.schemaToFunctionJson(format.name, format.schema))),
            toolChoice = ToolChoice.Required,
            toolResults = emptyList(),
            text = request.text + "\n\n(Respond by calling the `${format.name}` function with the structured result.)" + nudge,
          )
        } else {
          request.copy(
            systemPrompt = (request.systemPrompt ?: "") + "\nRespond with ONLY valid JSON. No prose, no markdown fences.",
            text = request.text + nudge,
          )
        }
      val result = RelaisEngine.generate(context, req, shouldCancel = { ThermalGovernor.shouldTruncate() })
      val candidate = if (isSchema) result.toolCalls.firstOrNull()?.argumentsJson else result.text
      lastCandidate = candidate
      val repairedCandidate = candidate?.let { RelaisStructuredOutput.repairOutput(it) }

      val resolved: Pair<String, Boolean>? = when { // (content, wasRepaired)
        candidate != null && RelaisStructuredOutput.isValidOutput(candidate, format) -> candidate to false
        repairedCandidate != null && RelaisStructuredOutput.isValidOutput(repairedCandidate, format) -> repairedCandidate to true
        else -> null
      }

      if (resolved != null) {
        val choice = JSONObject()
          .put("index", 0)
          .put("message", JSONObject().put("role", "assistant").put("content", resolved.first))
          .put("finish_reason", "stop")
        if (resolved.second) choice.put("x_relais_structured_output_repaired", true)
        val resp = JSONObject()
          .put("id", id)
          .put("object", "chat.completion")
          .put("created", System.currentTimeMillis() / 1000)
          .put("model", model)
          .put("choices", JSONArray().put(choice))
          .put("usage", buildUsageObject(request.text, result.completionTokens))
          .put("x_relais_usage_note", "prompt_tokens_estimated")
        RelaisMetrics.recordRequest("/v1/chat/completions", 200)
        respond(sock, 200, resp)
        return
      }

      if (!RelaisStructuredOutput.shouldRetry(attempt, MAX_STRUCTURED_OUTPUT_RETRIES, repairedCandidate, format)) {
        RelaisMetrics.recordRequest("/v1/chat/completions", 422)
        respond(
          sock,
          422,
          JSONObject()
            .put("error", "model failed to produce valid JSON after ${MAX_STRUCTURED_OUTPUT_RETRIES + 1} attempts")
            .put("last_output", lastCandidate ?: ""),
        )
        return
      }
      attempt++
    }
  }

  /**
   * Extracts the full conversation from the OpenAI messages[] array.
   * Delegates to [buildPromptParts] (pure function, tested in OpenAiRequestParserTest) and
   * maps the result to a [RelaisRequest] with system prompt + history + final user turn.
   *
   * The [request.text] field is always the final user message text so that
   * [buildUsageObject](request.text, ...) in [handleOpenAi] keeps working correctly.
   */
  private fun parseOpenAiRequest(body: JSONObject): RelaisRequest {
    val messages = body.optJSONArray("messages") ?: JSONArray()
    // Pass android.util.Base64-backed lambdas explicitly — the default lambdas in buildPromptParts
    // use java.util.Base64 (for JVM-testability); production always runs on Android so we override.
    val parsed = buildPromptParts(
      messages = messages,
      dataUriBytes = { url -> dataUriBytes(url) },
      decode = { b64 -> decode(b64) },
    )
    val toolChoice = parseToolChoice(body)
    // tool_choice:"none" forbids tool use -> don't advertise tools at all.
    val tools = if (toolChoice == ToolChoice.None) emptyList() else parseTools(body)
    return RelaisRequest(
      text = parsed.lastUserText,
      imagePng = parsed.lastUserImage,
      audioWav = parsed.lastUserAudio,
      // Fold a selected prompt template into the system prompt (default-off: absent template +
      // absent system message → null → engine default, i.e. unchanged behavior). Unknown ids are
      // rejected with 400 in handleOpenAi before we reach here.
      systemPrompt = resolveSystemPrompt(
        explicitSystem = parsed.systemPrompt,
        template = PromptTemplateStore.resolve(context, parseTemplateId(body)),
        mode = parseTemplateMode(body),
      ),
      history = parsed.history,
      tools = tools,
      toolChoice = toolChoice,
      toolResults = parsed.liveToolResults,
      temperature = optDoubleOrNull(body, "temperature"),
      topP = optDoubleOrNull(body, "top_p"),
      seed = if (body.has("seed") && !body.isNull("seed")) body.optInt("seed") else null,
      enableThinking = RelaisReasoning.thinkingEnabled(optStringOrNull(body, "reasoning_effort")),
    )
  }

  /** A JSON string field as a nullable String — null when absent/null (so defaults apply). */
  private fun optStringOrNull(body: JSONObject, key: String): String? =
    if (body.has(key) && !body.isNull(key)) body.optString(key) else null

  /** A JSON number field as a nullable Double — null when absent/null (so engine defaults apply). */
  private fun optDoubleOrNull(body: JSONObject, key: String): Double? =
    if (body.has(key) && !body.isNull(key)) body.optDouble(key).takeUnless { it.isNaN() } else null

  private fun dataUriBytes(url: String): ByteArray? =
    runCatching { decode(if (url.startsWith("data:")) url.substringAfter(",") else url) }.getOrNull()

  // --- helpers ---

  private fun endpointLabel(path: String): String =
    when {
      path.startsWith("/health") -> "/health"
      path == "/" -> "/"
      path.startsWith("/metrics") -> "/metrics"
      path.startsWith("/generate") -> "/generate"
      path.startsWith("/v1/chat/completions") -> "/v1/chat/completions"
      path.startsWith("/v1/models") -> "/v1/models"
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

// ---------------------------------------------------------------------------
// OpenAI usage-block helpers (Feature 02)
// ---------------------------------------------------------------------------

/**
 * Estimates the prompt token count from [text] using a word-boundary heuristic
 * (trim + split on whitespace runs). LiteRT-LM does not expose a detached prompt tokenizer
 * via sendMessageAsync (SPIKE-FINDINGS.md / Q1); this is a best-effort approximation only.
 *
 * IMPORTANT: [text] is the last user message only — [parseOpenAiRequest] discards system
 * messages and prior conversation turns, so multi-turn or system-prompt-heavy requests will
 * under-count until the multi-turn parser is extended to concatenate all context text.
 * Do not treat this value as exact. The top-level `x_relais_usage_note` field on the response
 * documents this approximation to callers.
 */
internal fun estimatePromptTokens(text: String): Int =
  if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size

/**
 * Builds the assistant message JSON + finish_reason for a tool completion. When the model emitted
 * tool calls, content is null and `tool_calls[]` is populated (finish_reason="tool_calls");
 * otherwise it is a plain text answer (finish_reason="stop").
 *
 * [streaming] controls whether each tool_call object also carries an `index` field (0-based) — the
 * OpenAI streaming contract requires clients to accumulate streamed delta fragments by `index`; that
 * field must NOT appear in the non-streaming `message.tool_calls[]` form.
 *
 * Pure function (no Context, no Android types) — unit-testable on the JVM ([ToolResponseShapingTest]).
 */
internal fun buildToolAssistantMessage(result: RelaisResult, streaming: Boolean = false): Pair<JSONObject, String> =
  if (result.toolCalls.isNotEmpty()) {
    val calls = JSONArray()
    result.toolCalls.forEachIndexed { i, call ->
      val entry = JSONObject()
        .put("id", call.id)
        .put("type", "function")
        .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson))
      if (streaming) entry.put("index", i)
      calls.put(entry)
    }
    val message = JSONObject()
      .put("role", "assistant")
      .put("content", JSONObject.NULL)
      .put("tool_calls", calls)
    message to "tool_calls"
  } else {
    val message = JSONObject().put("role", "assistant").put("content", result.text)
    message to "stop"
  }

/**
 * Assembles the OpenAI-schema-clean `usage` object for a completed inference.
 *
 * Returns an object with exactly the three standard OpenAI fields:
 * - `prompt_tokens`: word-boundary ESTIMATE — see [estimatePromptTokens].
 * - `completion_tokens`: exact engine counter (onMessage callback count). Zero for AICore.
 * - `total_tokens`: always exactly `prompt_tokens + completion_tokens`.
 *
 * The estimation signal is intentionally NOT placed inside this object to avoid tripping
 * strict OpenAI-schema validators. Callers must attach `x_relais_usage_note` as a top-level
 * extension field on the enclosing response or chunk object.
 *
 * Pure function (no Context, no Android types) — unit-testable on the JVM.
 */
internal fun buildUsageObject(promptText: String, completionTokens: Int): org.json.JSONObject {
  val promptTokens = estimatePromptTokens(promptText)
  return org.json.JSONObject()
    .put("prompt_tokens", promptTokens)
    .put("completion_tokens", completionTokens)
    .put("total_tokens", promptTokens + completionTokens)
}

// Stable epoch for the "created" field required by strict OpenAI clients (e.g. older openai-python).
// A fixed constant keeps buildModelsResponse pure and deterministic — no System.currentTimeMillis().
private const val MODEL_CREATED_EPOCH = 0L

/**
 * Pure mapping function (no Context dependency) — shapes the OpenAI-compatible GET /v1/models
 * response from the curated catalog refs and a fallback model id. When [refs] is empty (offline),
 * returns a single-entry list containing only the currently-provisioned [fallbackId] so the
 * response is always non-empty. Internal so the unit test can call it directly on the JVM.
 *
 * Each entry includes a stable `created` epoch so strict OpenAI clients (older openai-python) that
 * require the field do not reject the response.
 */
internal fun buildModelsResponse(refs: List<RelaisModelRef>, fallbackId: String): JSONObject {
  val data = JSONArray()
  if (refs.isEmpty()) {
    data.put(
      JSONObject()
        .put("id", fallbackId)
        .put("object", "model")
        .put("owned_by", "node")
        .put("created", MODEL_CREATED_EPOCH)
    )
  } else {
    refs.forEach { ref ->
      data.put(
        JSONObject()
          .put("id", ref.modelId)
          .put("object", "model")
          .put("owned_by", ref.source)
          .put("created", MODEL_CREATED_EPOCH)
      )
    }
  }
  return JSONObject().put("object", "list").put("data", data)
}
