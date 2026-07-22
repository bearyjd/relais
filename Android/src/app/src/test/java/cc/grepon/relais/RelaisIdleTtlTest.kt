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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure idle-unload decision in [RelaisIdleTtl.kt] (#178). No Context,
 * no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisAdmissionTest].
 *
 * The real concurrency safety (the highest-risk part of #178 — never racing a fresh request against
 * an in-progress unload) is NOT covered here: it lives in [RelaisEngine.releaseIfIdle]'s
 * lock-ordering against [RelaisEngine.generate], which depends on the real (native, AAR-provided)
 * `Engine` and can't be faked in a hermetic JVM test — see that KDoc for the argument and why it
 * needs an on-device/instrumented probe instead.
 */
class RelaisIdleTtlTest {

  private val ttlMs = 15L * 60_000L // 15 minutes, matches IDLE_TTL_DEFAULT_MINUTES

  // -------------------------------------------------------------------------
  // 1. not ready -> never unload (nothing resident to release)
  // -------------------------------------------------------------------------

  @Test
  fun `never unloads when engine is not ready`() {
    val now = 1_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = false,
        inFlightDepth = 0,
        lastActivityAtMs = now - ttlMs - 1,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 2. disabled TTL (0 or negative) -> never unload
  // -------------------------------------------------------------------------

  @Test
  fun `never unloads when ttl is disabled (zero)`() {
    val now = 1_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 0,
        lastActivityAtMs = 0L,
        nowMs = now,
        ttlMs = 0L,
      )
    )
  }

  @Test
  fun `never unloads when ttl is negative`() {
    val now = 1_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 0,
        lastActivityAtMs = 0L,
        nowMs = now,
        ttlMs = -1L,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 3. in-flight request -> never unload (the highest-risk invariant)
  // -------------------------------------------------------------------------

  @Test
  fun `never unloads while a request is in flight, even long past ttl`() {
    val now = 10_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 1,
        lastActivityAtMs = 0L, // way past the ttl window
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  @Test
  fun `never unloads with a deep in-flight queue`() {
    val now = 10_000_000L
    assertFalse(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 8,
        lastActivityAtMs = 0L,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 4. boundary: exactly at ttl vs. one ms under
  // -------------------------------------------------------------------------

  @Test
  fun `unloads at exactly the ttl boundary`() {
    val lastActivity = 0L
    val now = ttlMs // elapsed == ttlMs exactly
    assertTrue(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 0,
        lastActivityAtMs = lastActivity,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  @Test
  fun `does not unload one ms under the ttl boundary`() {
    val lastActivity = 0L
    val now = ttlMs - 1
    assertFalse(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 0,
        lastActivityAtMs = lastActivity,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 5. well past ttl, idle, ready -> unload
  // -------------------------------------------------------------------------

  @Test
  fun `unloads when idle well past ttl with nothing in flight`() {
    val now = 1_000_000_000L
    assertTrue(
      shouldUnloadIdleEngine(
        ready = true,
        inFlightDepth = 0,
        lastActivityAtMs = 0L,
        nowMs = now,
        ttlMs = ttlMs,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 6. defaults / constants sanity
  // -------------------------------------------------------------------------

  @Test
  fun `default and bound constants are sane`() {
    assertTrue(IDLE_TTL_DISABLED_MINUTES == 0)
    assertTrue(IDLE_TTL_DEFAULT_MINUTES in IDLE_TTL_MIN_MINUTES..IDLE_TTL_MAX_MINUTES)
    assertTrue(IDLE_TTL_MIN_MINUTES > IDLE_TTL_DISABLED_MINUTES)
    assertTrue(IDLE_TTL_MAX_MINUTES >= IDLE_TTL_MIN_MINUTES)
  }
}
