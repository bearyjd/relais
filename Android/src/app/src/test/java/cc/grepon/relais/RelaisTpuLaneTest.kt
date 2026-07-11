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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaisTpuLaneTest {

  @Test
  fun `G5 AOT filenames are recognized`() {
    assertTrue(RelaisTpuLane.isTpuCompiledModel("Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm"))
    assertTrue(RelaisTpuLane.isTpuCompiledModel("gemma-4-E2B-it_Google_Tensor_G5.litertlm"))
    assertTrue(RelaisTpuLane.isTpuCompiledModel("model_google_tensor_g5.litertlm")) // case-insensitive
  }

  @Test
  fun `regular models are not TPU-compiled`() {
    assertFalse(RelaisTpuLane.isTpuCompiledModel("gemma-4-E2B-it.litertlm"))
    assertFalse(RelaisTpuLane.isTpuCompiledModel("gemma3-1b-it-int4.litertlm"))
    assertFalse(RelaisTpuLane.isTpuCompiledModel("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm")) // Qualcomm AOT
    assertFalse(RelaisTpuLane.isTpuCompiledModel(""))
    assertFalse(RelaisTpuLane.isTpuCompiledModel(null))
  }

  @Test
  fun `ekv marker sets the token budget`() {
    assertEquals(1280, RelaisTpuLane.tpuMaxNumTokens("Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm", 4096))
    assertEquals(4096, RelaisTpuLane.tpuMaxNumTokens("model_ekv4096_Google_Tensor_G5.litertlm", 1024))
    assertEquals(2048, RelaisTpuLane.tpuMaxNumTokens("MODEL_EKV2048.litertlm", 4096)) // case-insensitive
  }

  @Test
  fun `missing ekv marker falls back to the engine default`() {
    assertEquals(4096, RelaisTpuLane.tpuMaxNumTokens("gemma-4-E2B-it_Google_Tensor_G5.litertlm", 4096))
    assertEquals(4096, RelaisTpuLane.tpuMaxNumTokens(null, 4096))
    assertEquals(4096, RelaisTpuLane.tpuMaxNumTokens("model_ekvXL.litertlm", 4096)) // non-numeric
  }

  @Test
  fun `custom sampler detection`() {
    assertFalse(RelaisTpuLane.requestUsesCustomSampler(null, null, null))
    assertTrue(RelaisTpuLane.requestUsesCustomSampler(0.7, null, null))
    assertTrue(RelaisTpuLane.requestUsesCustomSampler(null, 0.9, null))
    assertTrue(RelaisTpuLane.requestUsesCustomSampler(null, null, 42))
  }
}
