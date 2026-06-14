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

package cc.grepon.relais.core

import android.content.Context
import android.net.Uri
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * In-process one-shot inference for NON-HTTP callers (tiles, widgets, share, NFC, triage, Tasker).
 * Wraps [RelaisEngine.generate].
 *
 * CRITICAL CONTRACT: this is a *consumer* of an already-resident engine. It does NOT cold-start or
 * provision a multi-GB model — so a home-screen tap can never trigger a download/init with no
 * foreground service behind it (which the OS would OOM-kill). When the engine isn't ready it fails
 * fast and **eagerly** with [NodeNotReadyException] (thrown by the call itself, before the Flow is
 * returned), so callers surface "node not running" instead of hanging or provisioning.
 */
object RelaisInference {

  class NodeNotReadyException : IllegalStateException("Relais node is not running")

  fun isReady(): Boolean = RelaisEngine.isReady

  /**
   * Streams the visible answer deltas. Reasoning ("thinking") deltas are intentionally dropped here
   * (UI surfaces want the answer, not the chain-of-thought; the HTTP path exposes reasoning_content).
   * The blocking engine call runs on [Dispatchers.IO]; tokens are delivered with back-pressure
   * (`trySendBlocking`) so none are dropped. Throws [NodeNotReadyException] eagerly if not ready.
   */
  fun complete(
    context: Context,
    prompt: String,
    system: String? = null,
    images: List<Uri> = emptyList(),
  ): Flow<String> {
    if (!RelaisEngine.isReady) throw NodeNotReadyException() // EAGER: before returning the Flow
    val request = RelaisRequest(
      text = prompt,
      systemPrompt = system,
      imagePng = images.firstOrNull()?.let { readPng(context, it) }, // single image (v1)
    )
    return callbackFlow {
      val job = launch(Dispatchers.IO) {
        try {
          RelaisEngine.generate(
            context = context,
            request = request,
            onToken = { delta -> trySendBlocking(delta) }, // paces the producer to the collector
          )
          close()
        } catch (t: Throwable) {
          close(t)
        }
      }
      awaitClose { job.cancel() }
    }
  }

  /** Collects [complete] to the full answer text. The primary API for non-streaming callers. */
  suspend fun completeText(context: Context, prompt: String, system: String? = null): String =
    buildString { complete(context, prompt, system).collect { append(it) } }

  private fun readPng(context: Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}
