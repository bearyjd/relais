/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Relais.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RelaisModelProvisioner.ensureModel] must REFUSE a model measured not to load on the pinned
 * runtime, before it downloads anything.
 *
 * ### Why this test exists
 * #220 filtered [RelaisModelCatalog], which controls what the selector and `/v1/models` **offer**.
 * That left every other route into provisioning open:
 *
 *  - `adb … --es modelId litert-community/Qwen2.5-1.5B-Instruct` — **issue #220's own reproduction
 *    command**, which resolves against the raw allowlist, not the filtered catalog
 *  - a ref persisted before the model was known-bad
 *  - a ref built by the selector's HuggingFace search, which can name any repo
 *
 * All of them still downloaded ~1.6 GB and then died in engine-create — the exact symptom #220
 * exists to prevent. Filtering what is *offered* is not the same as refusing what is *loaded*.
 *
 * The gate lives at the top of `ensureModel`, not in `resolveModel`: the offline fast paths return
 * before `resolveModel` is ever called, so a check there is bypassable. Same lesson the G5 default
 * substitution learned in #19.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelaisProvisionCompatGateTest {

  private lateinit var ctx: Context
  private var savedId: String? = null

  @Before
  fun snapshotPrefs() {
    ctx = ApplicationProvider.getApplicationContext()
    savedId = RelaisConfig.modelId(ctx)
  }

  @After
  fun restorePrefs() {
    savedId?.let { RelaisConfig.setModelId(ctx, it) }
  }

  @Test
  fun `ensureModel refuses a measured-incompatible model before downloading`() {
    // The exact id from #220's repro command.
    val knownBad = "litert-community/Qwen2.5-1.5B-Instruct"
    check(RelaisRuntimeCompat.incompatibleReason(knownBad) != null) {
      "fixture drift: $knownBad is no longer in the measured-incompatible table"
    }
    RelaisConfig.setModelId(ctx, knownBad)

    try {
      RelaisModelProvisioner.ensureModel(ctx)
      fail("ensureModel accepted a model measured not to load — #220's repro still reaches download")
    } catch (e: IllegalStateException) {
      val msg = e.message.orEmpty()
      assertTrue("the refusal must name the model, got: $msg", msg.contains(knownBad))
      assertTrue(
        "the refusal must explain WHY rather than failing opaquely, got: $msg",
        msg.contains("LlmMetadata") || msg.contains("not loadable"),
      )
    }
  }

  @Test
  fun `ensureModel does not refuse a model that is merely unmeasured`() {
    // Only MEASURED failures are withheld. An unmeasured id must fall through to the normal
    // resolve path — over-blocking here would quietly break every model we have not tested.
    val unmeasured = "someone/brand-new-model"
    check(RelaisRuntimeCompat.incompatibleReason(unmeasured) == null)
    RelaisConfig.setModelId(ctx, unmeasured)

    try {
      RelaisModelProvisioner.ensureModel(ctx)
    } catch (e: IllegalStateException) {
      // Reaching the allowlist and failing to match there (or failing offline) is the CORRECT
      // outcome — it proves the compat gate let this id through. Only a compat refusal is a bug.
      val msg = e.message.orEmpty()
      assertTrue(
        "unmeasured model was blocked by the compat gate, not the normal resolve path: $msg",
        !msg.contains("not loadable"),
      )
    }
  }
}
