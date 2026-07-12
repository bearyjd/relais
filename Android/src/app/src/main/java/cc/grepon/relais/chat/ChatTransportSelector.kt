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
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import java.io.Closeable

/**
 * Picks and constructs the [ChatTransport] the in-app chat UI should stream a turn over, health
 * -probing the loopback HTTP server on each call (see [chooseTransport]).
 *
 * Owns a single [HttpClient] for the lifetime of the selector (created lazily on first use); the
 * owner MUST [close] it — one per `send()` would leak a client (and its thread pool) per turn.
 */
class ChatTransportSelector(private val context: Context) : Closeable {
  private val httpClientLazy =
    lazy {
      HttpClient(Android) {
        install(SSE)
        // A wedged loopback server (accepts the socket, never answers) would otherwise hang the
        // chat forever. connectTimeout catches a dead listener; socketTimeout caps the gap between
        // reads so a stalled stream fails into an error turn. requestTimeout is left infinite —
        // a healthy on-device stream can legitimately run for minutes.
        //
        // The server flushes the SSE headers on admission but emits no body bytes during prefill,
        // so socketTimeout also bounds time-to-first-token. It's set generously (5 min) so a slow
        // on-device prefill (large model / long context / thermal throttling) is never mistaken for
        // a wedged server — the point is only to turn an *infinite* hang into a bounded failure.
        install(HttpTimeout) {
          connectTimeoutMillis = CONNECT_TIMEOUT_MS
          socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
      }
    }
  private val httpClient: HttpClient by httpClientLazy

  /**
   * Picks the transport for one chat turn. [HttpChatTransport] now serializes image/audio
   * attachments as OpenAI content-parts, so every request (text-only or multimodal) prefers the
   * HTTP path when the loopback node is live, else falls back to in-process.
   */
  suspend fun select(): ChatTransport {
    val httpTransport = HttpChatTransport(context, httpClient)
    // healthReachable() already checks `ready: true`, so both chooseTransport args collapse to it.
    val reachable = httpTransport.healthReachable()
    return when (chooseTransport(healthReachable = reachable, nodeReady = reachable)) {
      TransportKind.HTTP -> httpTransport
      TransportKind.IN_PROCESS -> InProcessChatTransport(context)
    }
  }

  /** Releases the shared [HttpClient] if it was ever created. Idempotent. */
  override fun close() {
    if (httpClientLazy.isInitialized()) httpClient.close()
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 5_000L
    const val SOCKET_TIMEOUT_MS = 300_000L
  }
}
