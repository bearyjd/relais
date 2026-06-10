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

package cc.grepon.relais.relais

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate 3 deterministic checks: the thermal governor's tier transitions (driven via the test seam,
 * no real hardware needed) and the /metrics label-hygiene invariant (security M6 — no paths/secrets).
 */
@RunWith(AndroidJUnit4::class)
class RelaisGate3Test {

  @Test
  fun shedsAtSevereNotAtNone() {
    ThermalGovernor.resetThroughputForTest()
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_NONE)
    assertFalse("must not shed when cool", ThermalGovernor.shouldShed())

    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_SEVERE)
    assertTrue("must shed at SEVERE", ThermalGovernor.shouldShed())
    assertFalse("SEVERE does not truncate in-flight", ThermalGovernor.shouldTruncate())

    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_CRITICAL)
    assertTrue("CRITICAL truncates in-flight", ThermalGovernor.shouldTruncate())
  }

  @Test
  fun shedsWhenThroughputCollapses() {
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_NONE)
    ThermalGovernor.resetThroughputForTest()
    repeat(4) { ThermalGovernor.onDecodeThroughput(1.5) } // sustained below the 3.0 tok/s floor
    assertTrue("collapsed throughput is a throttle signal", ThermalGovernor.shouldShed())

    ThermalGovernor.resetThroughputForTest()
    repeat(4) { ThermalGovernor.onDecodeThroughput(6.0) } // healthy baseline
    assertFalse("healthy throughput does not shed", ThermalGovernor.shouldShed())
  }

  @Test
  fun cooldownOnlyWhenModerate() {
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_LIGHT)
    assertEquals(0L, ThermalGovernor.cooldownMs())
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_MODERATE)
    assertTrue(ThermalGovernor.cooldownMs() > 0L)
  }

  @Test
  fun retryAfterEscalatesWithHeat() {
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_SEVERE)
    val severe = ThermalGovernor.retryAfterSeconds()
    ThermalGovernor.setStatusForTest(PowerManager.THERMAL_STATUS_CRITICAL)
    assertTrue("critical backs off longer than severe", ThermalGovernor.retryAfterSeconds() > severe)
  }

  @Test
  fun metricsDoNotLeakPathsOrSecrets() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    RelaisMetrics.recordRequest("/v1/chat/completions", 200)
    RelaisMetrics.recordLatency(1.2)
    RelaisMetrics.recordThroughput(10, 5.5, "GPU_LITERTLM")
    val prom = RelaisMetrics.renderProm(ctx)

    assertTrue(prom.contains("relais_build_info"))
    assertTrue(prom.contains("relais_inference_duration_seconds_bucket"))
    assertTrue(prom.contains("relais_requests_total"))

    // M6: never expose the model filesystem path, the keystore, or the bearer key.
    assertFalse(prom.contains("/data/"))
    assertFalse(prom.contains("/sdcard"))
    assertFalse(prom.contains(".litertlm"))
    assertFalse(prom.contains("Bearer"))
    assertFalse(prom.contains(RelaisConfig.apiKey(ctx)))
  }
}
