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
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls the resident [RelaisEngine] directly, in-process — bypassing the loopback HTTP server.
 * Used when the HTTP server is unreachable or the resident node isn't ready yet (see
 * [chooseTransport]).
 */
class InProcessChatTransport(private val context: Context) : ChatTransport {
  override suspend fun stream(
    request: ChatStreamRequest,
    onToken: (String) -> Unit,
    onReasoning: (String) -> Unit,
    shouldCancel: () -> Boolean,
  ): ChatStreamResult =
    withContext(Dispatchers.IO) {
      val engineRequest =
        RelaisRequest(
          text = request.userText,
          imagePng = request.imagePng,
          audioWav = request.audioWav,
          history = request.history,
        )
      val result =
        RelaisEngine.generate(
          context = context,
          request = engineRequest,
          onToken = onToken,
          shouldCancel = shouldCancel,
          onReasoning = onReasoning,
        )
      ChatStreamResult(
        text = result.text,
        backend = result.backend,
        tokensPerSec = result.decodeTokensPerSec,
        finishReason = result.finishReason,
        modelId = RelaisConfig.modelId(context),
      )
    }
}
