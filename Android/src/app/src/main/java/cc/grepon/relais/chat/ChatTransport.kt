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

import cc.grepon.relais.ParsedTurn
import cc.grepon.relais.RelaisBackend

/** Which channel the chat UI streams a turn over. See [chooseTransport]. */
enum class TransportKind {
  /** Stream via the loopback OpenAI-compatible HTTP server (`/v1/chat/completions`). */
  HTTP,
  /** Call the resident engine in-process, bypassing the HTTP server. */
  IN_PROCESS,
}

/**
 * Picks how the in-app chat UI should stream a turn.
 *
 * HTTP is preferred (it matches production/multi-client behavior — the same path any external
 * client uses) but requires both that the loopback server answer health checks
 * ([healthReachable]) and that the resident node has finished loading a model ([nodeReady]).
 * Otherwise the UI falls back to calling the engine in-process so chat still works before/without
 * the HTTP server being reachable.
 */
fun chooseTransport(healthReachable: Boolean, nodeReady: Boolean): TransportKind =
  if (healthReachable && nodeReady) TransportKind.HTTP else TransportKind.IN_PROCESS

/** One turn to stream: prior [history], the new [userText], and optional attachments. */
data class ChatStreamRequest(
  val history: List<ParsedTurn>,
  val userText: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
)

/** Final result of a completed (non-cancelled) stream. */
data class ChatStreamResult(
  val text: String,
  val backend: RelaisBackend,
  val tokensPerSec: Double,
  val finishReason: String,
  val modelId: String,
)

/**
 * Streams one chat turn over whichever [TransportKind] the caller has selected (HTTP or
 * in-process). Implementations are provided in Task 5.
 */
interface ChatTransport {
  suspend fun stream(
    request: ChatStreamRequest,
    onToken: (String) -> Unit,
    onReasoning: (String) -> Unit,
    shouldCancel: () -> Boolean,
  ): ChatStreamResult
}
