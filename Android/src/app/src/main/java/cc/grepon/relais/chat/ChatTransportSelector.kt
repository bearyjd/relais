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
import io.ktor.client.plugins.sse.SSE

/**
 * Picks and constructs the [ChatTransport] the in-app chat UI should stream a turn over, health
 * -probing the loopback HTTP server on each call (see [chooseTransport]).
 */
class ChatTransportSelector(private val context: Context) {
  private val httpClient: HttpClient by lazy { HttpClient(Android) { install(SSE) } }

  suspend fun select(): ChatTransport {
    val httpTransport = HttpChatTransport(context, httpClient)
    // healthReachable() already checks `ready: true`, so both chooseTransport args collapse to it.
    val reachable = httpTransport.healthReachable()
    return when (chooseTransport(healthReachable = reachable, nodeReady = reachable)) {
      TransportKind.HTTP -> httpTransport
      TransportKind.IN_PROCESS -> InProcessChatTransport(context)
    }
  }
}
