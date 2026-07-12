/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais.chat

import android.content.Context
import android.util.Log
import cc.grepon.relais.RelaisBackend
import cc.grepon.relais.RelaisConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject

private const val TAG = "RelaisHttpChatTransport"
private const val CHAT_URL = "http://127.0.0.1:8080/v1/chat/completions"
private const val HEALTH_URL = "http://127.0.0.1:8080/health"
private const val SSE_DONE_SENTINEL = "[DONE]"

/**
 * Streams one chat turn over the loopback OpenAI-compatible HTTP server
 * (`/v1/chat/completions`), matching the same path any external LAN client uses. Selected when the
 * server is [healthReachable] and the resident node is ready (see [chooseTransport]).
 *
 * [buildChatRequestJson] serializes `history` as text-only turns; the final user turn becomes an
 * OpenAI content-parts array (`text` + `image_url`/`input_audio`) whenever
 * [ChatStreamRequest.imagePng]/[ChatStreamRequest.audioWav] are present, so multimodal requests are
 * dogfooded over this HTTP path rather than forced to [InProcessChatTransport].
 */
class HttpChatTransport(private val context: Context, private val client: HttpClient) : ChatTransport {
  override suspend fun stream(
    request: ChatStreamRequest,
    onToken: (String) -> Unit,
    onReasoning: (String) -> Unit,
    shouldCancel: () -> Boolean,
  ): ChatStreamResult {
    val assembled = StringBuilder()
    val body =
      buildChatRequestJson(
        history = request.history,
        userText = request.userText,
        imagePng = request.imagePng,
        audioWav = request.audioWav,
        stream = true,
      )
    // Wall-clock token-rate proxy: one "token" per content delta received, timed from the first
    // delta to the last. Approximate (a delta may carry more than one model token), but real —
    // far better than a hardcoded value.
    var deltaCount = 0
    var firstDeltaNanos = 0L
    var lastDeltaNanos = 0L
    var finishReason: String? = null
    client.sse(
      urlString = CHAT_URL,
      request = {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer ${RelaisConfig.apiKey(context)}")
        setBody(body)
      },
    ) {
      // Stop the SSE flow the way Ktor expects: complete it via `takeWhile` on the [DONE] sentinel /
      // a cooperative cancel. Do NOT throw out of `collect` to break early — the SSE session wraps
      // any exception thrown mid-collection into an SSEClientException, so a thrown sentinel turns a
      // perfectly good stream into a spurious error turn (the [DONE] terminator would fail EVERY
      // turn). `takeWhile` completes the flow normally, and a real network error still propagates.
      incoming
        .takeWhile { event -> event.data != SSE_DONE_SENTINEL && !shouldCancel() }
        .collect { event ->
          val raw = event.data ?: return@collect
          val line = "data: $raw"
          parseSseContentDelta(line)?.let { delta ->
            val now = System.nanoTime()
            if (deltaCount == 0) firstDeltaNanos = now
            lastDeltaNanos = now
            deltaCount++
            onToken(delta)
            assembled.append(delta)
          }
          parseSseFinishReason(line)?.let { finishReason = it }
        }
    }
    val elapsedSeconds = (lastDeltaNanos - firstDeltaNanos) / 1_000_000_000.0
    // Rate over the (n-1) inter-delta intervals, not the raw count — delta #1 anchors t=0, so
    // dividing the whole count by the first→last window would inflate the rate on short streams.
    val tokensPerSec = if (deltaCount > 1 && elapsedSeconds > 0.0) (deltaCount - 1) / elapsedSeconds else 0.0
    return ChatStreamResult(
      text = assembled.toString(),
      // The loopback HTTP path has no way to learn which accelerator served the request — neither
      // the SSE stream nor `/health` expose a backend field — so report UNKNOWN rather than
      // mislabeling as GPU_LITERTLM. (The in-process transport still reports the real backend.)
      backend = RelaisBackend.UNKNOWN,
      tokensPerSec = tokensPerSec,
      finishReason = finishReason ?: "stop",
      modelId = RelaisConfig.modelId(context),
    )
  }

  /** GETs `/health` and returns true iff the loopback server answers 200 with `ready: true`. */
  suspend fun healthReachable(): Boolean =
    try {
      val response = client.get(HEALTH_URL)
      if (response.status != HttpStatusCode.OK) {
        false
      } else {
        JSONObject(response.bodyAsText()).optBoolean("ready", false)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.d(TAG, "health probe failed: ${e.message}")
      false
    }
}
