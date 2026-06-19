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
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

private const val TAG = "RelaisAicore"

/**
 * The NPU path: Gemini Nano via AICore (ML Kit GenAI), image+text only. Selected by
 * [BackendSelector] for image/text requests **only on devices where AICore is available**
 * (Pixel 10+/qualifying hardware).
 *
 * UNVERIFIED on the Pixel 9 Pro Fold used for the spike: that device is excluded from the AICore
 * device groups, so [available] returns false and this path is never exercised here. The code is
 * structured to light up automatically on a Pixel 10 — wired to the same ML Kit GenAI API the
 * existing AICoreModelHelper uses.
 */
object RelaisAicore {
  @Volatile private var cachedAvailable: Boolean? = null

  private fun client() =
    Generation.getClient(
      generationConfig {
        modelConfig = modelConfig {
          releaseStage = ModelReleaseStage.PREVIEW // E4B "Gemini Nano via AICore" is preview
          preference = ModelPreference.FULL
        }
      }
    )

  /** Memoized capability probe: ML Kit checkStatus()==AVAILABLE/DOWNLOADABLE. False if unsupported. */
  fun available(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
    cachedAvailable?.let {
      return it
    }
    val result =
      try {
        runBlocking {
          when (client().checkStatus()) {
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> true
            else -> false
          }
        }
      } catch (e: Exception) {
        Log.i(TAG, "AICore not available on this device: ${e.message}")
        false
      }
    cachedAvailable = result
    return result
  }

  /** Image+text generation on the NPU via Gemini Nano. Audio is not supported (never routed here). */
  fun generate(request: RelaisRequest): String {
    val gm = client()
    val req =
      if (request.imagePng != null) {
        val bmp = BitmapFactory.decodeByteArray(request.imagePng, 0, request.imagePng.size)
        generateContentRequest(ImagePart(bmp), TextPart(request.text)) {}
      } else {
        generateContentRequest(TextPart(request.text)) {}
      }
    val sb = StringBuilder()
    runBlocking {
      gm.warmup()
      gm.generateContentStream(req).collect { response ->
        sb.append(response.candidates.firstOrNull()?.text ?: "")
      }
    }
    return sb.toString()
  }
}
