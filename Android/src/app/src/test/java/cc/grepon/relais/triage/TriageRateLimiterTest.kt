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

package cc.grepon.relais.triage

import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TriageRateLimiterTest {
  @Before fun setUp() = TriageRateLimiter.reset()

  @After fun tearDown() = TriageRateLimiter.reset()

  @Test fun `first claim succeeds`() {
    assertTrue(TriageRateLimiter.tryClaimUrgent(now = 1_000L, cooldownMs = 100L))
  }

  @Test fun `claim within cooldown is rejected`() {
    assertTrue(TriageRateLimiter.tryClaimUrgent(now = 1_000L, cooldownMs = 100L))
    assertFalse(TriageRateLimiter.tryClaimUrgent(now = 1_050L, cooldownMs = 100L))
  }

  @Test fun `claim after cooldown succeeds again`() {
    assertTrue(TriageRateLimiter.tryClaimUrgent(now = 1_000L, cooldownMs = 100L))
    assertFalse(TriageRateLimiter.tryClaimUrgent(now = 1_099L, cooldownMs = 100L))
    assertTrue(TriageRateLimiter.tryClaimUrgent(now = 1_100L, cooldownMs = 100L))
  }

  @Test fun `a storm of concurrent claims at the same instant wins exactly once`() {
    val wins = AtomicInteger(0)
    val threads =
      (1..32).map {
        Thread { if (TriageRateLimiter.tryClaimUrgent(now = 5_000L, cooldownMs = 1_000L)) wins.incrementAndGet() }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertEquals(1, wins.get())
  }
}
