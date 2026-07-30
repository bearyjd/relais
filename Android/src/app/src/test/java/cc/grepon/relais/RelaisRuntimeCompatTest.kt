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

import cc.grepon.relais.RelaisRuntimeCompat.Loadability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime-compatibility + license-gating classification for curated allowlist entries (#220).
 *
 * The upstream allowlist is keyed by our `versionName` and drifts independently of the litertlm we
 * pin, so it offers models that download cleanly (multi-GB) and then fail engine-create, plus models
 * that need an HF token nobody asked for. Every expectation below is transcribed from the **measured**
 * table in issue #220 — do not "fix" a case here without a fresh on-device measurement.
 */
class RelaisRuntimeCompatTest {

  // --- Loadability against the pinned runtime -------------------------------------------------

  @Test
  fun modelsMeasuredToServeAreVerified() {
    assertEquals(
      Loadability.VERIFIED,
      RelaisRuntimeCompat.loadability("litert-community/gemma-4-E2B-it-litert-lm"),
    )
    assertEquals(
      Loadability.VERIFIED,
      RelaisRuntimeCompat.loadability("litert-community/gemma-4-E4B-it-litert-lm"),
    )
  }

  @Test
  fun qwenIsIncompatibleAndSaysWhy() {
    val id = "litert-community/Qwen2.5-1.5B-Instruct"
    assertEquals(Loadability.INCOMPATIBLE, RelaisRuntimeCompat.loadability(id))
    val reason = RelaisRuntimeCompat.incompatibleReason(id)
    assertNotNull("an incompatible model must explain itself", reason)
    assertTrue(
      "reason should quote the observed engine-create failure, got: $reason",
      reason!!.contains("LlmMetadata"),
    )
  }

  /**
   * DeepSeek shares Qwen's `multi-prefill-seq_q8_ekv4096` build family but was **never measured**.
   * It must NOT be reported as broken (that would hide a possibly-working model) and must NOT be
   * reported as fine (that reintroduces the wasted-download trap). It is its own state.
   */
  @Test
  fun deepSeekIsSuspectNotIncompatible() {
    val id = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B"
    assertEquals(Loadability.SUSPECT, RelaisRuntimeCompat.loadability(id))
    assertNull("SUSPECT is an untested guess, not an observed failure", RelaisRuntimeCompat.incompatibleReason(id))
  }

  @Test
  fun unmeasuredModelsAreUnknownNotAssumedGood() {
    assertEquals(Loadability.UNKNOWN, RelaisRuntimeCompat.loadability("someone/brand-new-model"))
    assertNull(RelaisRuntimeCompat.incompatibleReason("someone/brand-new-model"))
  }

  // --- Offerability: only an OBSERVED failure is withheld --------------------------------------

  @Test
  fun onlyMeasuredFailuresAreWithheldFromTheCatalog() {
    assertFalse(RelaisRuntimeCompat.isOfferable("litert-community/Qwen2.5-1.5B-Instruct"))
    // Suspect / unknown / gated models stay on offer — withholding on suspicion would silently
    // shrink the catalog on every future allowlist addition.
    assertTrue(RelaisRuntimeCompat.isOfferable("litert-community/DeepSeek-R1-Distill-Qwen-1.5B"))
    assertTrue(RelaisRuntimeCompat.isOfferable("someone/brand-new-model"))
    assertTrue(RelaisRuntimeCompat.isOfferable("litert-community/Gemma3-1B-IT"))
  }

  // --- HF license gating ------------------------------------------------------------------------

  /**
   * Regression for the `startsWith("google/")` heuristic the selector used to carry: issue #220
   * verified host-side that `litert-community/Gemma3-1B-IT` answers a ranged GET with **401**, so it
   * is gated despite not being a `google/` repo — and it rendered with no "token" badge.
   */
  @Test
  fun litertCommunityGemma3IsGatedDespiteNotBeingAGoogleRepo() {
    assertTrue(
      "Gemma3-1B-IT is Gemma-licensed and 401s without a token (#220)",
      RelaisRuntimeCompat.requiresHfToken("litert-community/Gemma3-1B-IT"),
    )
  }

  @Test
  fun googleRepositoriesAreGated() {
    assertTrue(RelaisRuntimeCompat.requiresHfToken("google/gemma-3n-E2B-it-litert-lm"))
    assertTrue(RelaisRuntimeCompat.requiresHfToken("google/gemma-3n-E4B-it-litert-lm"))
    // The prefix rule still stands in for google/ repos we have not individually measured.
    assertTrue(RelaisRuntimeCompat.requiresHfToken("google/some-future-model"))
  }

  @Test
  fun ungatedApacheModelsNeedNoToken() {
    // Both verified host-side with a ranged GET → HTTP 206 (#220).
    assertFalse(RelaisRuntimeCompat.requiresHfToken("litert-community/gemma-4-E2B-it-litert-lm"))
    assertFalse(RelaisRuntimeCompat.requiresHfToken("litert-community/gemma-4-E4B-it-litert-lm"))
    assertFalse(RelaisRuntimeCompat.requiresHfToken("litert-community/Qwen2.5-1.5B-Instruct"))
  }

  // --- Staleness guard --------------------------------------------------------------------------

  /**
   * The whole table above is only true for one runtime. If litertlm is bumped, these measurements
   * are void until re-run on hardware.
   *
   * Asserting the constant against a literal would be tautological — it cannot fail for the reason
   * that matters, which is `libs.versions.toml` moving while this table stands still. So read the
   * real pin. Skips (rather than fails) when the TOML isn't locatable from the test working
   * directory, since the JVM test CWD is a build-layout detail, not something under test.
   */
  @Test
  fun compatibilityTableTracksThePinnedRuntime() {
    val toml =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "gradle/libs.versions.toml") }
        .firstOrNull { it.exists() } ?: return
    val pinned =
      Regex("""^\s*litertlm\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(toml.readText())
        ?.groupValues
        ?.get(1)

    assertEquals(
      "litertlm was bumped without re-measuring the #220 compatibility table — " +
        "re-run the models on hardware, don't just re-point the constant",
      pinned,
      RelaisRuntimeCompat.PINNED_LITERTLM_VERSION,
    )
  }

  /**
   * Gating and loadability are independent axes, and collapsing them would drop a working model:
   * `Gemma3-1B-IT` is a license-gated repo whose Relais-pinned `…_Google_Tensor_G5` AOT build
   * ([RelaisModelCatalog.G5_TPU_REFS]) serves fine — it is the model the #146 on-device passes ran on.
   */
  @Test
  fun gatingAndLoadabilityAreIndependentAxes() {
    val id = "litert-community/Gemma3-1B-IT"
    assertTrue("gated", RelaisRuntimeCompat.requiresHfToken(id))
    assertTrue("but still offerable — gating is not incompatibility", RelaisRuntimeCompat.isOfferable(id))
  }
}
