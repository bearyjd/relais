/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the DEFAULT-OFF invariant for session memory (Feature #5), the config clamps, and the inert
 * resolution contract that the HTTP path relies on to do zero work when the feature is disabled.
 */
@RunWith(RobolectricTestRunner::class)
class RelaisSessionConfigTest {

  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Test fun `session memory is off by default`() {
    // The #1 requirement: a fresh install must report the feature disabled, so the HTTP path never
    // captures the header, opens the DB, or persists anything (verified by the gate in the server).
    assertFalse(RelaisConfig.sessionMemoryEnabled(context))
  }

  @Test fun `off-path key resolution is inert when no header and no ip hash`() {
    // When disabled, the server passes neither a header nor an IP hash to the policy; the resolver
    // returns null, so the load/merge/record block is never entered (zero behavior change).
    assertNull(RelaisSessionPolicy.resolveSessionKey(header = null, clientIpHash = null))
  }

  @Test fun `enable toggle round-trips`() {
    RelaisConfig.setSessionMemoryEnabled(context, true)
    assertTrue(RelaisConfig.sessionMemoryEnabled(context))
    RelaisConfig.setSessionMemoryEnabled(context, false)
    assertFalse(RelaisConfig.sessionMemoryEnabled(context))
  }

  @Test fun `ttl hours default and clamp`() {
    assertEquals(24, RelaisConfig.sessionTtlHours(context))
    RelaisConfig.setSessionTtlHours(context, 100_000)
    assertEquals(24 * 30, RelaisConfig.sessionTtlHours(context)) // clamped to MAX (30 days)
    RelaisConfig.setSessionTtlHours(context, 0)
    assertEquals(1, RelaisConfig.sessionTtlHours(context)) // clamped to MIN
  }

  @Test fun `max turns default and clamp`() {
    assertEquals(40, RelaisConfig.sessionMaxTurns(context))
    RelaisConfig.setSessionMaxTurns(context, 9_999)
    assertEquals(200, RelaisConfig.sessionMaxTurns(context)) // clamped to MAX
    RelaisConfig.setSessionMaxTurns(context, 1)
    assertEquals(2, RelaisConfig.sessionMaxTurns(context)) // clamped to MIN
  }
}
