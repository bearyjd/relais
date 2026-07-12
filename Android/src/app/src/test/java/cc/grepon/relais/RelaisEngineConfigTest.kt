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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [isMissingEncoder] — the text-only fallback trigger (LiteRT-LM rejects a model
 * that lacks an image/audio encoder), anchored to the `TF_LITE_*_ENCODER` shape so an unrelated
 * failure can't silently downgrade a multimodal model.
 *
 * (The former `isG5Incompatible` pre-flight gate and its tests were removed once gemma-4-E4B was
 * verified to init + serve on Tensor G5 without the SIGSEGV it guarded — see RelaisEngine.)
 */
class RelaisEngineConfigTest {

  @Test
  fun missing_encoder_detected_from_litertlm_messages() {
    // litertlm 0.11 — rejected at initialize()
    assertTrue(
      isMissingEncoder(
        RuntimeException("Failed to create engine: NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model.")
      )
    )
    // litertlm 0.13 — rejected at createConversation()
    assertTrue(
      isMissingEncoder(
        RuntimeException(
          "Failed to create conversation: NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model."
        )
      )
    )
    assertTrue(isMissingEncoder(RuntimeException("not_found: tf_lite_vision_encoder_hw not found")))
  }

  @Test
  fun non_encoder_errors_are_not_missing_encoder() {
    assertFalse(isMissingEncoder(RuntimeException("OUT_OF_RANGE: max_num_tokens too large")))
    assertFalse(isMissingEncoder(RuntimeException("NOT_FOUND: model file missing"))) // not an encoder
    // NOT_FOUND mentioning an encoder but NOT the TF_LITE_*_ENCODER token -> not a missing-encoder signal.
    assertFalse(isMissingEncoder(RuntimeException("NOT_FOUND: weights for AUDIO_ENCODER could not be loaded")))
    assertFalse(isMissingEncoder(RuntimeException("CL_OUT_OF_RESOURCES in OpenCL kernel")))
    assertFalse(isMissingEncoder(RuntimeException(null as String?)))
  }
}
