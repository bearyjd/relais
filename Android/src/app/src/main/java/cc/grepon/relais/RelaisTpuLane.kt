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

/**
 * Pure decision logic for the Tensor-TPU engine lane (spike plan T-4; proven T-2/T-3 on rango).
 *
 * The TPU lane is DISPATCHER-gated, not AICore-gated: `libLiteRtDispatch_GoogleTensor.so` in the
 * app's nativeLibraryDir is what makes `Backend.NPU` reachable — AICore/Gemini-Nano is an unrelated
 * system service that GrapheneOS never ships. The lane additionally requires a Google-Tensor
 * AOT-compiled `.litertlm` (fixed-shape graphs); a regular model on `Backend.NPU` fails init.
 *
 * Kept free of android.* imports so the selection rules are device-free JVM-testable
 * ([RelaisTpuLaneTest]); the actual `.so`-presence check lives with the caller.
 */
object RelaisTpuLane {
  /** The Google Tensor TPU dispatch library the debug build bundles (see scripts/fetch-tensor-dispatcher.sh). */
  const val DISPATCHER_LIB = "libLiteRtDispatch_GoogleTensor.so"

  /**
   * True iff [fileName] is a Google-Tensor AOT-compiled model per the litert-community naming
   * convention (`…_Google_Tensor_G5.litertlm`, case-insensitive). Only such models can execute on
   * the TPU; everything else must stay on the GPU lane.
   */
  fun isTpuCompiledModel(fileName: String?): Boolean =
    fileName?.contains("google_tensor", ignoreCase = true) == true

  /**
   * The engine token budget for an AOT model: its KV cache is compiled to a FIXED size, encoded in
   * the filename (`ekv1280` → 1280). A mismatched `maxNumTokens` breaks the NPU executor's decode
   * step-tracking. Falls back to [engineDefault] when the filename carries no marker.
   */
  fun tpuMaxNumTokens(fileName: String?, engineDefault: Int): Int {
    val match = Regex("ekv(\\d+)", RegexOption.IGNORE_CASE).find(fileName ?: "") ?: return engineDefault
    return match.groupValues[1].toIntOrNull() ?: engineDefault
  }

  /**
   * True when a request carries explicit sampling parameters. The NPU compiled-model executor
   * crashes mid-decode under a custom [com.google.ai.edge.litertlm.SamplerConfig]
   * ("new_step must be <= TokenCount()", observed rango 2026-07-10) — the TPU lane must run the
   * engine-default sampler and warn until upstream supports it.
   */
  fun requestUsesCustomSampler(temperature: Double?, topP: Double?, seed: Int?): Boolean =
    temperature != null || topP != null || seed != null
}
