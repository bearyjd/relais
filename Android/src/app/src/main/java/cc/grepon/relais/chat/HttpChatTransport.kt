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
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RelaisHttpChatTransport"
private const val CHAT_URL = "http://127.0.0.1:8080/v1/chat/completions"
private const val HEALTH_URL = "http://127.0.0.1:8080/health"
private const val SSE_DONE_SENTINEL = "[DONE]"

/**
 * Marker thrown to unwind out of an in-progress SSE `collect` once the stream is done ([DONE]) or
 * the caller asked to cancel — [collect] has no early-return primitive of its own.
 */
private class ChatStreamStop : RuntimeException()

/**
 * Streams one chat turn over the loopback OpenAI-compatible HTTP server
 * (`/v1/chat/completions`), matching the same path any external LAN client uses. Selected when the
 * server is [healthReachable] and the resident node is ready (see [chooseTransport]).
 *
 * [buildChatRequestBody] serializes `history` as text-only turns; the final user turn becomes an
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
    val body = buildChatRequestBody(request)
    try {
      client.sse(
        urlString = CHAT_URL,
        request = {
          method = HttpMethod.Post
          contentType(ContentType.Application.Json)
          header(HttpHeaders.Authorization, "Bearer ${RelaisConfig.apiKey(context)}")
          setBody(body.toString())
        },
      ) {
        incoming.collect { event ->
          if (shouldCancel()) throw ChatStreamStop()
          val raw = event.data ?: return@collect
          if (raw == SSE_DONE_SENTINEL) throw ChatStreamStop()
          parseSseContentDelta("data: $raw")?.let { delta ->
            onToken(delta)
            assembled.append(delta)
          }
        }
      }
    } catch (e: ChatStreamStop) {
      // Expected early exit: [DONE] sentinel or a cooperative cancel.
    }
    return ChatStreamResult(
      text = assembled.toString(),
      backend = RelaisBackend.GPU_LITERTLM,
      tokensPerSec = 0.0,
      finishReason = "stop",
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

/**
 * Builds the OpenAI `/v1/chat/completions` request body: prior [ChatStreamRequest.history] as
 * text-only turns, plus the new user turn. When the user turn carries
 * [ChatStreamRequest.imagePng]/[ChatStreamRequest.audioWav], its `content` is emitted as an array
 * of OpenAI content-parts (`text`, `image_url`, `input_audio`) per [RelaisOpenAiParser.extractParts];
 * otherwise `content` stays a plain string, matching the server's text-only fast path.
 */
private fun buildChatRequestBody(request: ChatStreamRequest): JSONObject {
  val messages = JSONArray()
  request.history.forEach { turn ->
    messages.put(JSONObject().put("role", turn.role).put("content", turn.text))
  }
  val hasMedia = request.imagePng != null || request.audioWav != null
  val userContent: Any =
    if (!hasMedia) {
      request.userText
    } else {
      val parts = JSONArray()
      parts.put(JSONObject().put("type", "text").put("text", request.userText))
      request.imagePng?.let { png ->
        val dataUri = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        parts.put(
          JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", dataUri)),
        )
      }
      request.audioWav?.let { wav ->
        parts.put(
          JSONObject()
            .put("type", "input_audio")
            .put(
              "input_audio",
              JSONObject()
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                .put("format", "wav"),
            ),
        )
      }
      parts
    }
  messages.put(JSONObject().put("role", "user").put("content", userContent))
  return JSONObject().put("messages", messages).put("stream", true)
}
