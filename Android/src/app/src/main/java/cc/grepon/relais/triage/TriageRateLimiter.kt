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

import java.util.concurrent.atomic.AtomicLong

/**
 * Debounce gate for the near-real-time urgent-surfacing path. This is the storm-safety guarantee: a
 * burst of notifications can claim the urgent slot **at most once per cooldown window**, so N posted
 * notifications produce at most one urgency-classification inference per [URGENT_COOLDOWN_MS] (which
 * then classifies the whole buffered batch in a single pass). Inference is never run per-notification.
 *
 * In-memory only; the timestamp resets when the process dies, which is fine — a fresh process starts
 * with an empty buffer too.
 */
object TriageRateLimiter {
  const val URGENT_COOLDOWN_MS = 120_000L

  private val lastUrgentRun = AtomicLong(0L)

  /**
   * Atomically claim the urgent slot if the cooldown has elapsed. Returns true exactly once per
   * cooldown window even under concurrent callers (CAS loop).
   */
  fun tryClaimUrgent(now: Long, cooldownMs: Long = URGENT_COOLDOWN_MS): Boolean {
    while (true) {
      val last = lastUrgentRun.get()
      if (now - last < cooldownMs) return false
      if (lastUrgentRun.compareAndSet(last, now)) return true
    }
  }

  /** Test/kill-switch hook: allow the next claim to proceed immediately. */
  fun reset() = lastUrgentRun.set(0L)
}
