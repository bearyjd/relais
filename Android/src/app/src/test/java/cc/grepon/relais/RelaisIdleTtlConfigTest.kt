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

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the [RelaisConfig.idleTtlMinutes] default + clamp contract (#178), including the special
 * "0 = disabled" sentinel that — unlike the shed thresholds it's modeled after — is a legitimate
 * in-band operator choice rather than a device-safety hazard to clamp away.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisIdleTtlConfigTest {

  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Test fun `defaults to IDLE_TTL_DEFAULT_MINUTES on a fresh install`() {
    assertEquals(IDLE_TTL_DEFAULT_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `zero means disabled and round-trips as zero, not the floor`() {
    RelaisConfig.setIdleTtlMinutes(context, 0)
    assertEquals(IDLE_TTL_DISABLED_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `negative values collapse to disabled, not a clamp artifact`() {
    RelaisConfig.setIdleTtlMinutes(context, -5)
    assertEquals(IDLE_TTL_DISABLED_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `clamps to the max ceiling`() {
    RelaisConfig.setIdleTtlMinutes(context, 100_000)
    assertEquals(IDLE_TTL_MAX_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `the smallest enabled value (the min floor) round-trips exactly, not as disabled`() {
    // IDLE_TTL_MIN_MINUTES is the smallest *enabled* value — distinct from the 0 sentinel — so it
    // must survive sanitization unchanged, not collapse to IDLE_TTL_DISABLED_MINUTES.
    RelaisConfig.setIdleTtlMinutes(context, IDLE_TTL_MIN_MINUTES)
    assertEquals(IDLE_TTL_MIN_MINUTES, RelaisConfig.idleTtlMinutes(context))
  }

  @Test fun `enabled value round-trips unclamped within band`() {
    RelaisConfig.setIdleTtlMinutes(context, 30)
    assertEquals(30, RelaisConfig.idleTtlMinutes(context))
  }
}
