/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Provisioner adoption guard: a model an operator pre-staged at [RelaisEngine.defaultModelPath]
 * (the conventional side-load location) must be adopted as-is, so a fresh install whose model is
 * already on disk boots LIVE instead of re-downloading multiple GB. Regression test for the bug
 * where [RelaisModelProvisioner.ensureModel] only checked the persisted and allowlist-resolved
 * paths and so re-downloaded a model that was already side-loaded.
 *
 * Hermetic + offline: fast path 1 is forced to miss by pointing the persisted path at a file that
 * does not exist, and the staged file makes fast path 2 hit before any allowlist fetch or download,
 * so this never touches the network. Ported from androidTest (PR5) — uses Robolectric for Context
 * and file I/O (Robolectric's filesDir is a real JVM temp directory). This covers the adoption
 * LOGIC headlessly; the on-device SELinux/file-visibility path (an adb-pushed file can read
 * length()=0 from the app uid) is NOT modeled by a JVM tempdir, so the androidTest
 * RelaisProvisionerTest is retained to validate that on real hardware (two-tier).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisProvisionerTest {

  @Test
  fun adoptsPreStagedModelWithoutDownloading() {
    val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    // Robolectric gives each test a clean app sandbox, but snapshot and restore prefs for parity
    // with the androidTest original — also guards against any future test ordering issues.
    val savedModelId = RelaisConfig.modelId(ctx)
    val savedPath = RelaisConfig.modelPath(ctx)

    // Eligibility: fast path 2 is gated to the default model id.
    RelaisConfig.setModelId(ctx, RelaisConfig.DEFAULT_MODEL_ID)
    // Force fast path 1 (persisted path) to miss so adoption is what's under test.
    RelaisConfig.setModelPath(ctx, File(ctx.cacheDir, "no-such-model.litertlm").absolutePath)

    // Stage a model at the conventional side-load location. Under Robolectric, filesDir is a real
    // JVM temp directory, so File I/O works normally. Always create a fresh stand-in (the
    // Robolectric sandbox is isolated per test) and clean it up in finally.
    val staged = File(RelaisEngine.defaultModelPath(ctx))
    staged.parentFile?.mkdirs()
    staged.writeBytes(byteArrayOf(0x00))

    try {
      val resolved = RelaisModelProvisioner.ensureModel(ctx)
      assertEquals("staged model must be adopted, not re-downloaded", staged.absolutePath, resolved)
    } finally {
      staged.delete()
      // Restore id first (a change clears KEY_MODEL_PATH), then the path, so both end as found.
      RelaisConfig.setModelId(ctx, savedModelId)
      savedPath?.let { RelaisConfig.setModelPath(ctx, it) }
    }
  }
}
