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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.grepon.relais.data.RelaisModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ref provisioning guard: a persisted [RelaisModelRef] lets [RelaisModelProvisioner.resolveModel]
 * build the right [cc.grepon.relais.data.Model] **with no network and no allowlist match** — the
 * change that unlocks serving arbitrary (non-allowlist) HF models. Also covers the ref's JSON
 * round-trip.
 *
 * Hermetic + offline: a ref whose id matches the configured id makes resolveModel take the ref fast
 * path before any allowlist fetch. Snapshots and restores the real prefs (targetContext) so an
 * operator's configured model survives the test unchanged.
 */
@RunWith(AndroidJUnit4::class)
class RelaisModelRefProvisionTest {

  @Test
  fun jsonRoundTrips() {
    val ref = RelaisModelRef("acme/demo-litert-lm", "demo.litertlm", "c0ffee", 42L, "demo-litert-lm", RelaisModelRef.SOURCE_HUGGINGFACE)
    assertEquals(ref, RelaisModelRef.fromJson(ref.toJson()))
    // Malformed / absent JSON decodes to null so a corrupt pref can't brick a headless boot.
    assertNull(RelaisModelRef.fromJson(null))
    assertNull(RelaisModelRef.fromJson("not json"))
    assertNull("missing required fields are rejected", RelaisModelRef.fromJson("""{"displayName":"x"}"""))
  }

  @Test
  fun resolveModelBuildsFromRefWithoutNetwork() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    val savedId = RelaisConfig.modelId(ctx)
    val savedRef = RelaisConfig.modelRef(ctx)
    val savedPath = RelaisConfig.modelPath(ctx)

    val ref =
      RelaisModelRef(
        modelId = "acme/test-litert-lm",
        modelFile = "model.litertlm",
        commitHash = "abc123",
        sizeInBytes = 123_456_789L,
        displayName = "test-litert-lm",
        source = RelaisModelRef.SOURCE_HUGGINGFACE,
      )

    try {
      RelaisConfig.setModelRef(ctx, ref)

      // No network: the ref id == modelId, so resolveModel returns before the allowlist fetch.
      val model = RelaisModelProvisioner.resolveModel(ctx)

      assertEquals(
        "https://huggingface.co/acme/test-litert-lm/resolve/abc123/model.litertlm?download=true",
        model.url,
      )
      assertEquals("model.litertlm", model.downloadFileName)
      assertEquals("abc123", model.version)
      assertEquals("test-litert-lm", model.name)
      assertEquals(123_456_789L, model.sizeInBytes)
    } finally {
      // Restore: setModelRef keeps id+ref coherent; clearModelRef+setModelId restores the id-only
      // case. Restore the provisioned path last (the setters above clear KEY_MODEL_PATH on change).
      if (savedRef != null) {
        RelaisConfig.setModelRef(ctx, savedRef)
      } else {
        RelaisConfig.clearModelRef(ctx)
        RelaisConfig.setModelId(ctx, savedId)
      }
      savedPath?.let { RelaisConfig.setModelPath(ctx, it) }
    }
  }

  /**
   * Staleness guard (invariant: a bare id change supersedes a diverged ref). Setting a different
   * model id must drop a ref that names another repo and invalidate the cached provisioned path, so
   * adb `--es modelId` resolves the new id via the allowlist instead of serving the old ref's model.
   * A same-repo id keeps its ref.
   */
  @Test
  fun divergingModelIdDropsStaleRefButSameRepoKeepsIt() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    val savedId = RelaisConfig.modelId(ctx)
    val savedRef = RelaisConfig.modelRef(ctx)
    val savedPath = RelaisConfig.modelPath(ctx)

    try {
      // Diverging id drops the ref and clears the cached path.
      RelaisConfig.setModelRef(
        ctx,
        RelaisModelRef("acme/a", "a.litertlm", "c1", 1L, "a", RelaisModelRef.SOURCE_HUGGINGFACE),
      )
      RelaisConfig.setModelPath(ctx, "/tmp/a-provisioned") // simulate a provisioned path
      RelaisConfig.setModelId(ctx, "other/b")
      assertNull("diverged ref is dropped", RelaisConfig.modelRef(ctx))
      assertNull("provisioned path invalidated on id change", RelaisConfig.modelPath(ctx))

      // Same-repo id keeps the ref (the selector's coherent setModelRef path).
      val keep = RelaisModelRef("keep/me", "m.litertlm", "c2", 2L, "me", RelaisModelRef.SOURCE_ALLOWLIST)
      RelaisConfig.setModelRef(ctx, keep)
      RelaisConfig.setModelId(ctx, "keep/me")
      assertEquals("matching id keeps the ref", keep, RelaisConfig.modelRef(ctx))
    } finally {
      if (savedRef != null) {
        RelaisConfig.setModelRef(ctx, savedRef)
      } else {
        RelaisConfig.clearModelRef(ctx)
        RelaisConfig.setModelId(ctx, savedId)
      }
      savedPath?.let { RelaisConfig.setModelPath(ctx, it) }
    }
  }
}
