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

import com.google.ai.edge.litertlm.ExperimentalApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for [RelaisRequest.samplerConfig] defaulting + clamping (feature: sampler params). */
@OptIn(ExperimentalApi::class)
class RelaisSamplerTest {

  @Test fun `defaults applied when unspecified`() {
    val s = RelaisRequest(text = "x").samplerConfig()
    assertEquals(64, s.topK)
    assertEquals(0.95, s.topP, 1e-9)
    assertEquals(1.0, s.temperature, 1e-9)
    assertEquals(0, s.seed)
  }

  @Test fun `request values are honored`() {
    val s = RelaisRequest(text = "x", temperature = 0.2, topP = 0.5, seed = 7).samplerConfig()
    assertEquals(0.2, s.temperature, 1e-9)
    assertEquals(0.5, s.topP, 1e-9)
    assertEquals(7, s.seed)
  }

  @Test fun `temperature is clamped to 0_0 - 2_0`() {
    assertEquals(2.0, RelaisRequest(text = "x", temperature = 5.0).samplerConfig().temperature, 1e-9)
    assertEquals(0.0, RelaisRequest(text = "x", temperature = -1.0).samplerConfig().temperature, 1e-9)
  }

  @Test fun `top_p is clamped to 0_0 - 1_0`() {
    assertEquals(1.0, RelaisRequest(text = "x", topP = 2.0).samplerConfig().topP, 1e-9)
    assertEquals(0.0, RelaisRequest(text = "x", topP = -0.5).samplerConfig().topP, 1e-9)
  }

  @Test fun `temperature zero is preserved (greedy)`() {
    assertEquals(0.0, RelaisRequest(text = "x", temperature = 0.0).samplerConfig().temperature, 1e-9)
  }
}
