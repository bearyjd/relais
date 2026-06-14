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

import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Feature #10 configurable, CLAMPED shed thresholds and the device-safety invariant.
 *
 * Two concerns:
 *  - [RelaisConfig] clamps out-of-range threshold writes/reads into the safe band (so a misconfigured
 *    operator value can never disable a shed signal — e.g. decodeFloorTokS must stay > 0).
 *  - [ThermalGovernor] keeps the `statusValue >= SEVERE` shed as a hardcoded, non-bypassable backstop
 *    regardless of how loose the configured thresholds are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThermalGovernorTest {

  private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

  @After
  fun tearDown() {
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_NONE)
    ThermalGovernor.resetThroughputForTest()
    ThermalGovernor.resetThresholdsForTest()
  }

  // --- RelaisConfig clamping ---------------------------------------------------------------------

  @Test
  fun `shedHeadroom clamps out-of-range inputs and honors in-range`() {
    RelaisConfig.setShedHeadroom(context, 9.0f) // above 1.5 max
    assertEquals(1.5f, RelaisConfig.shedHeadroom(context), 1e-6f)
    RelaisConfig.setShedHeadroom(context, 0.1f) // below 0.5 min
    assertEquals(0.5f, RelaisConfig.shedHeadroom(context), 1e-6f)
    RelaisConfig.setShedHeadroom(context, 0.9f) // in range
    assertEquals(0.9f, RelaisConfig.shedHeadroom(context), 1e-6f)
  }

  @Test
  fun `decodeFloorTokS clamps to stay strictly positive so the throughput-floor signal cannot be disabled`() {
    RelaisConfig.setDecodeFloorTokS(context, 0.0) // below 0.5 min -> must clamp up (never 0)
    assertEquals(0.5, RelaisConfig.decodeFloorTokS(context), 1e-9)
    assertTrue("floor must remain strictly positive", RelaisConfig.decodeFloorTokS(context) > 0.0)
    RelaisConfig.setDecodeFloorTokS(context, 999.0) // above 50 max
    assertEquals(50.0, RelaisConfig.decodeFloorTokS(context), 1e-9)
    RelaisConfig.setDecodeFloorTokS(context, 3.0) // in range
    assertEquals(3.0, RelaisConfig.decodeFloorTokS(context), 1e-9)
  }

  @Test
  fun `moderateCooldownMs clamps out-of-range inputs and honors in-range`() {
    RelaisConfig.setModerateCooldownMs(context, -100L) // below 0 min
    assertEquals(0L, RelaisConfig.moderateCooldownMs(context))
    RelaisConfig.setModerateCooldownMs(context, 999_999L) // above 10s max
    assertEquals(10_000L, RelaisConfig.moderateCooldownMs(context))
    RelaisConfig.setModerateCooldownMs(context, 1_500L) // in range
    assertEquals(1_500L, RelaisConfig.moderateCooldownMs(context))
  }

  @Test
  fun `non-finite thresholds are neutralized to safe in-band values (coerceIn lets NaN through)`() {
    // A NaN/Inf threshold would slip past a bare coerceIn and silently disable the headroom and
    // throughput-floor shed signals. Both read and write must map non-finite -> default, in band.
    RelaisConfig.setShedHeadroom(context, Float.NaN)
    assertTrue("NaN headroom must read back finite", RelaisConfig.shedHeadroom(context).isFinite())
    assertEquals(0.95f, RelaisConfig.shedHeadroom(context), 1e-6f)
    RelaisConfig.setShedHeadroom(context, Float.POSITIVE_INFINITY)
    assertEquals(0.95f, RelaisConfig.shedHeadroom(context), 1e-6f)

    RelaisConfig.setDecodeFloorTokS(context, Double.NaN)
    assertTrue("NaN floor must read back > 0", RelaisConfig.decodeFloorTokS(context) > 0.0)
    assertEquals(3.0, RelaisConfig.decodeFloorTokS(context), 1e-9)
    RelaisConfig.setDecodeFloorTokS(context, Double.NEGATIVE_INFINITY)
    assertTrue("Inf floor must read back > 0", RelaisConfig.decodeFloorTokS(context) > 0.0)
  }

  @Test
  fun `defaults match the historical consts`() {
    assertEquals(0.95f, RelaisConfig.shedHeadroom(context), 1e-6f)
    assertEquals(3.0, RelaisConfig.decodeFloorTokS(context), 1e-9)
    assertEquals(1_500L, RelaisConfig.moderateCooldownMs(context))
  }

  // --- ThermalGovernor honors configured thresholds ----------------------------------------------

  @Test
  fun `governor reads configured thresholds pushed in via setThresholdsForTest`() {
    ThermalGovernor.setThresholdsForTest(shedHeadroom = 0.7f, decodeFloorTokS = 5.0, moderateCooldownMs = 2_000L)
    // Moderate cooldown is now the configured value.
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_MODERATE)
    assertEquals(2_000L, ThermalGovernor.cooldownMs())
  }

  // --- device-safety invariant (headless, no Context needed) -------------------------------------

  @Test
  fun `SEVERE always sheds even under the loosest possible config`() {
    // Loosest config: floor at its minimum and headroom at its maximum. Even then, SEVERE must shed.
    ThermalGovernor.setThresholdsForTest(
      shedHeadroom = 1.5f,        // max -> headroom signal practically never fires
      decodeFloorTokS = 0.5,      // min (>0) -> floor signal hardest to trip
      moderateCooldownMs = 0L,
    )
    ThermalGovernor.resetThroughputForTest() // no throughput samples -> floor signal cannot fire
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_SEVERE)
    assertTrue("SEVERE must remain a non-bypassable shed backstop", ThermalGovernor.shouldShed())
  }
}
