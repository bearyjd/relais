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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-4 verification probe (docs/tensor-tpu-spike-plan.md): the REAL production path —
 * [RelaisEngine.ensureInitialized] + [RelaisEngine.generate] — serves a request from the Tensor
 * TPU when the resident model is Google-Tensor AOT-compiled and the dispatcher lib is bundled
 * (debug builds). Asserts the result reports [RelaisBackend.TPU_LITERTLM] with real decode
 * throughput — the same lane the OpenAI HTTP endpoints and the in-app chat use.
 *
 * Needs a G5-AOT model staged app-owned (GrapheneOS FUSE ignores chmod on shell-pushed files):
 *   adb exec-out cat <staged>.litertlm | adb shell "run-as <appId> sh -c 'cat > files/bench/g5.litertlm'"
 *
 * Run (hardware only, not CI):
 *   adb shell am instrument -w \
 *     -e class cc.grepon.relais.TensorTpuProbe#tpuLaneServesGenerate \
 *     -e model /data/data/<appId>/files/bench/g5-1b.litertlm \
 *     <appId>.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Watch: adb logcat -s RelaisTpuProbe RelaisEngine
 */
@RunWith(AndroidJUnit4::class)
class TensorTpuProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun tpuLaneServesGenerate() {
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <path to a _Google_Tensor_G5 .litertlm>", modelPath != null)
    assumeTrue("model file missing/unreadable: $modelPath", File(modelPath!!).canRead())
    assumeTrue(
      "TPU dispatcher not bundled in this build (debug-only)",
      File(context.applicationInfo.nativeLibraryDir, RelaisTpuLane.DISPATCHER_LIB).exists(),
    )

    RelaisEngine.ensureInitialized(context, modelPath)
    assertTrue("engine not ready after init", RelaisEngine.isReady)
    assertTrue("TPU lane not selected — check filename marker + dispatcher", RelaisEngine.residentIsTpu)

    val result =
      RelaisEngine.generate(context, RelaisRequest(text = "In one short sentence, what is a relay station?"))
    Log.i(
      TAG,
      "TPU lane result: backend=${result.backend} decode=${"%.2f".format(result.decodeTokensPerSec)} tok/s " +
        "tokens=${result.completionTokens} text=\"${result.text.take(120)}\"",
    )
    assertEquals(RelaisBackend.TPU_LITERTLM, result.backend)
    assertTrue("no visible tokens decoded", result.completionTokens > 0)
    assertTrue("empty response", result.text.isNotBlank())
    assertTrue("throughput not measured", result.decodeTokensPerSec > 0.0)
  }

  private companion object {
    const val TAG = "RelaisTpuProbe"
  }
}
