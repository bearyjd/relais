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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Provisioning must REFUSE a model measured not to load on the pinned runtime, before it downloads
 * anything — at BOTH points where the configured model id is read.
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
 * ### Why there are two gates, and why both are tested
 * [RelaisModelProvisioner.ensureModel] reads the id once at entry; [RelaisModelProvisioner.resolveModel]
 * reads it AGAIN. A single gate at either site is bypassable:
 *  - only at `ensureModel` — flip the selection mid-provision and `resolveModel` downloads an id no
 *    gate inspected (the #11 drift guard declines to *persist* the path, but still returns it)
 *  - only at `resolveModel` — the offline fast paths return before `resolveModel` is ever called
 *
 * ### Hermetic by construction
 * Every test here persists a real on-disk path first, so provisioning completes through the offline
 * fast path and never touches the network. An earlier revision let the unmeasured-id case fall
 * through to a live allowlist fetch: it passed either way, but it made a JVM unit test depend on
 * GitHub being reachable and could burn the 15s connect + 30s read timeouts on the way to passing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelaisProvisionCompatGateTest {

  @get:Rule val tmp = TemporaryFolder()

  private lateinit var ctx: Context
  private lateinit var onDisk: File

  /** The exact id from #220's repro command. */
  private val knownBad = "litert-community/Qwen2.5-1.5B-Instruct"

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    // A real file, so the offline fast path in ensureModel would SUCCEED if the gate let it through.
    // That is what makes these assertions about ordering rather than about the network being down.
    onDisk = tmp.newFile("model.litertlm").apply { writeText("not a real model") }
    check(RelaisRuntimeCompat.incompatibleReason(knownBad) != null) {
      "fixture drift: $knownBad is no longer in the measured-incompatible table"
    }
  }

  /**
   * Selects [modelId] and leaves a provisioned path on disk for it.
   *
   * Order matters and is the whole reason this is a helper: [RelaisConfig.setModelId] REMOVES the
   * persisted path whenever the id actually changes (so the fast path can't serve the previous
   * model's file). Persisting first and selecting second silently clears the path, and every test
   * here would fall through to a live allowlist fetch — the exact non-hermetic behavior this file
   * was rewritten to remove.
   */
  private fun selectWithProvisionedPath(modelId: String) {
    RelaisConfig.setModelId(ctx, modelId)
    RelaisConfig.setModelPath(ctx, onDisk.absolutePath)
    check(RelaisConfig.modelPath(ctx) == onDisk.absolutePath) { "fixture: persisted path was cleared" }
  }

  @Test
  fun `ensureModel refuses a measured-incompatible model before downloading`() {
    selectWithProvisionedPath(knownBad)

    try {
      val path = RelaisModelProvisioner.ensureModel(ctx)
      fail(
        "ensureModel returned '$path' for a model measured not to load — #220's repro still reaches " +
          "provisioning. Note the persisted path exists, so the gate must sit ABOVE the offline fast path."
      )
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
  fun `resolveModel refuses it too, so a mid-provision model change cannot slip past`() {
    // The second read. ensureModel's gate inspected whatever was configured at entry; by the time
    // resolveModel re-reads the preference the operator may have switched to a known-bad model, and
    // without this gate that id would resolve and download unchecked.
    selectWithProvisionedPath(knownBad)

    try {
      RelaisModelProvisioner.resolveModel(ctx)
      fail("resolveModel accepted a measured-incompatible id — the mid-provision race is still open")
    } catch (e: IllegalStateException) {
      val msg = e.message.orEmpty()
      assertTrue("the refusal must name the model, got: $msg", msg.contains(knownBad))
      assertTrue(
        "the refusal must come from the compat gate, not the allowlist lookup, got: $msg",
        msg.contains("LlmMetadata") || msg.contains("not loadable"),
      )
    }
  }

  @Test
  fun `ensureModel does not refuse a model that is merely unmeasured`() {
    // Only MEASURED failures are withheld. An unmeasured id must fall through to the normal path —
    // over-blocking here would quietly break every model nobody has run on hardware yet.
    val unmeasured = "someone/brand-new-model"
    check(RelaisRuntimeCompat.incompatibleReason(unmeasured) == null)
    selectWithProvisionedPath(unmeasured)

    // Reaching the persisted path proves the gate let this id through, with no network involved.
    assertEquals(onDisk.absolutePath, RelaisModelProvisioner.ensureModel(ctx))
  }

  @Test
  fun `a SUSPECT model is deliberately NOT refused`() {
    // DeepSeek shares a build family with the measured failure, so it is badged "untested" in the
    // selector — but it has never been run, and the gate withholds only MEASURED failures. Pinned so
    // that promoting it to the incompatible table is a deliberate edit to this expectation, not a
    // silent behavior change nobody notices.
    val suspect = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B"
    check(RelaisRuntimeCompat.loadability(suspect) == RelaisRuntimeCompat.Loadability.SUSPECT) {
      "fixture drift: $suspect is no longer classified SUSPECT"
    }
    selectWithProvisionedPath(suspect)

    assertEquals(onDisk.absolutePath, RelaisModelProvisioner.ensureModel(ctx))
  }
}
