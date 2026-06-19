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

package cc.grepon.relais.runtime.aicore

import android.content.Context
import android.graphics.Bitmap
import cc.grepon.relais.data.Model
import cc.grepon.relais.runtime.CleanUpListener
import cc.grepon.relais.runtime.LlmModelHelper
import cc.grepon.relais.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

/**
 * De-Googled flavor stub for the AICore (Gemini Nano / ML Kit GenAI) runtime. AICore requires Google
 * Play Services, which the `degoogled` flavor excludes. AICORE-runtime models are never available in
 * this flavor (see the [RelaisAicore][cc.grepon.relais.RelaisAicore] stub), so the model-helper
 * router (`ModelHelperExt`) would only reach this object via an explicit AICORE model, which the
 * de-Googled build does not ship. Every entry point therefore fails fast / no-ops rather than
 * touching a Play-Services API. The real implementation lives in `src/full/`.
 */
@Suppress("UNUSED_PARAMETER")
object AICoreModelHelper : LlmModelHelper {

  private const val UNAVAILABLE = "AICore is not available in the de-Googled build"

  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    onDone(UNAVAILABLE)
  }

  /**
   * Explicit-download entry point (mirrors the `full` impl; called from `ModelManagerViewModel`).
   * No AICore in this flavor → report the error and stop.
   */
  fun downloadModel(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onProgress: (Long, Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    onError(UNAVAILABLE)
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    // no-op: no AICore conversation exists in the de-Googled flavor.
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    onDone()
  }

  override fun stopResponse(model: Model) {
    // no-op: no AICore inference can be running in the de-Googled flavor.
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    onError(UNAVAILABLE)
  }
}
