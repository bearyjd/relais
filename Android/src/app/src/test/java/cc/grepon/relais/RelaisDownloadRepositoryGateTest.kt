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
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import cc.grepon.relais.data.DefaultDownloadRepository
import cc.grepon.relais.data.Model
import cc.grepon.relais.data.ModelDownloadStatus
import cc.grepon.relais.data.ModelDownloadStatusType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The #220 download gate, tested against the **real** [DefaultDownloadRepository].
 *
 * ### Why this exists rather than only the helper tests
 * [RelaisDownloadCompatGateTest] pins [RelaisRuntimeCompat.incompatibleReasonForDownloadUrl]. That
 * proves the decision function is right and proves NOTHING about whether the download stack ever
 * calls it — delete the gate from `downloadModel` and every one of those tests still passes. This
 * repo has already shipped two tests that passed under the bug they claimed to pin, so the wiring
 * gets a test of its own.
 *
 * ### What it pins
 * `DefaultDownloadRepository.downloadModel` is the one place both legacy download routes converge:
 * a tap (via `ModelManagerViewModel.downloadModel`) and `processPendingDownloads()`, which calls it
 * DIRECTLY to resume every `PARTIALLY_DOWNLOADED` model on each `MainActivity` launch. The resume
 * route is why this matters — no user action, raw allowlist, and a partial pre-#220 Qwen download
 * would otherwise resume multi-GB on every cold start for a model the engine cannot load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelaisDownloadRepositoryGateTest {

  private lateinit var ctx: Context
  private lateinit var repo: DefaultDownloadRepository

  private val knownBad = "litert-community/Qwen2.5-1.5B-Instruct"

  /** Minimal stub: the gate returns long before anything reads foreground state. */
  private class FakeLifecycle : AppLifecycleProvider {
    override var isAppInForeground: Boolean = false
  }

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    repo = DefaultDownloadRepository(ctx, FakeLifecycle())
    check(RelaisRuntimeCompat.incompatibleReason(knownBad) != null) {
      "fixture drift: $knownBad is no longer in the measured-incompatible table"
    }
  }

  private fun modelAt(repoId: String) =
    Model(
      name = "test-model",
      url = "https://huggingface.co/$repoId/resolve/abc123/model.litertlm?download=true",
    )

  @Test
  fun `refuses a measured-incompatible model instead of starting the download`() {
    val seen = mutableListOf<ModelDownloadStatus>()
    repo.downloadModel(task = null, model = modelAt(knownBad)) { _, status -> seen.add(status) }

    assertEquals("the refusal must report exactly one status", 1, seen.size)
    assertEquals(ModelDownloadStatusType.FAILED, seen.single().status)
    val msg = seen.single().errorMessage
    assertTrue(
      "the refusal must explain WHY rather than failing opaquely, got: $msg",
      msg.contains("LlmMetadata") || msg.contains("not loadable"),
    )
  }

  @Test
  fun `an unmeasured model is NOT refused by this gate`() {
    // Only MEASURED failures are withheld. An unmeasured id must fall through to the normal enqueue
    // path — this is what proves the gate is a filter and not a blanket "downloads are off" switch.
    val seen = mutableListOf<ModelDownloadStatus>()
    repo.downloadModel(task = null, model = modelAt("someone/brand-new-model")) { _, status ->
      seen.add(status)
    }

    assertTrue(
      "an unmeasured model must not be refused by the compat gate, got: $seen",
      seen.none {
        it.status == ModelDownloadStatusType.FAILED &&
          (it.errorMessage.contains("not loadable") || it.errorMessage.contains("LlmMetadata"))
      },
    )
  }
}
