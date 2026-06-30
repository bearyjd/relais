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

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The node's heavy-endpoint admission gate: a FAIR [Semaphore] of [capacity] permits with two modes.
 *
 *  - **SHARED** (normal inference — `/generate`, `/v1/chat/completions`): one permit, non-blocking
 *    ([tryAcquireShared]). Up to [capacity] shared holders run concurrently — the original
 *    bounded-concurrency behavior; saturation → the caller answers 429.
 *
 *  - **EXCLUSIVE** (image generation, feature #16): ALL [capacity] permits, via a bounded *fair* wait
 *    ([tryAcquireExclusive]). Image gen is the heaviest GPU op on the device, so it must run with NO
 *    concurrent LLM decode and no second image-gen sharing GPU memory. Holding every permit guarantees
 *    that **by construction**: a successful exclusive acquire implies zero other in-flight work
 *    (single-flight + decode-exclusive). The fair wait lets the in-flight shared holders DRAIN before
 *    the exclusive op starts.
 *
 * Liveness, by design:
 *  - Shared acquirers use the *barging* (non-fair) [Semaphore.tryAcquire], so while an exclusive lock is
 *    held they see zero permits and **fail fast** (429) — they never block behind the ~minutes-long
 *    image generate.
 *  - Because shared acquirers barge, under sustained decode load the exclusive waiter may not be able to
 *    drain the gate within its timeout; [tryAcquireExclusive] then returns false having acquired NOTHING,
 *    and the caller answers 503 "busy". The device is never starved and the lock is never half-held.
 *
 * Pure JVM (no Android types) so the concurrency contract is unit-testable in isolation.
 */
class RelaisAdmissionGate(private val capacity: Int) {

  init {
    require(capacity > 0) { "admission gate capacity must be > 0, was $capacity" }
  }

  private val sem = Semaphore(capacity, /* fair = */ true)

  // True from the moment one exclusive (image-gen) request begins draining the gate until it releases.
  // Enforces SINGLE-FLIGHT (a 2nd exclusive fast-fails instead of parking a thread for the whole wait),
  // guards [releaseExclusive] against over-release, and lets callers tell an exclusive hold apart from
  // ordinary shared saturation (both leave availablePermits()==0).
  private val exclusiveInFlight = AtomicBoolean(false)

  /**
   * Non-blocking 1-permit (barging) acquire. Returns true iff admitted — the caller MUST call
   * [releaseShared] in a `finally`. False = the gate is saturated (or held exclusively); answer 429.
   */
  fun tryAcquireShared(): Boolean = sem.tryAcquire()

  /** Release one shared permit. Pair with a successful [tryAcquireShared]. */
  fun releaseShared() = sem.release()

  /**
   * Bounded *fair* acquire of ALL [capacity] permits, single-flight. Returns true iff the whole gate was
   * drained and is now held exclusively — the caller MUST call [releaseExclusive] in a `finally`.
   *
   * Returns false (and the caller simply answers 503, no release needed) in either case, both of which
   * acquire NOTHING:
   *  - another exclusive op is already in flight — fast-fail, so a 2nd image-gen never parks a worker
   *    thread for the full [timeoutMs] on a wait that cannot succeed while the first holds the device; or
   *  - the permits could not be gathered within [timeoutMs] (in-flight decode didn't drain) — all-or-none.
   */
  fun tryAcquireExclusive(timeoutMs: Long): Boolean {
    if (!exclusiveInFlight.compareAndSet(false, true)) return false // single-flight: a 2nd exclusive fast-fails
    val acquired =
      try {
        sem.tryAcquire(capacity, timeoutMs, TimeUnit.MILLISECONDS)
      } catch (t: Throwable) {
        exclusiveInFlight.set(false) // never leave the flag set if the wait is interrupted
        throw t
      }
    if (!acquired) exclusiveInFlight.set(false) // drain timed out → own nothing → release the flag
    return acquired
  }

  /**
   * Release the exclusive hold (all [capacity] permits). Pair with a successful [tryAcquireExclusive].
   * CAS-guarded so a mispaired or double release can never push `availablePermits()` above [capacity]
   * (which would silently break the "exclusive == all permits" invariant the whole feature rests on).
   */
  fun releaseExclusive() {
    if (exclusiveInFlight.compareAndSet(true, false)) sem.release(capacity)
  }

  /**
   * True while an exclusive (image-gen) op is in flight (draining or held). Lets a caller distinguish an
   * exclusive hold from ordinary shared saturation — both leave `availablePermits()==0` — when scaling
   * the backpressure Retry-After hint.
   */
  fun isHeldExclusively(): Boolean = exclusiveInFlight.get()

  /**
   * Current in-flight permit count (`capacity - available`). Used to scale the admission Retry-After
   * hint; reads the gate's own state so it can never disagree with [tryAcquireShared].
   */
  fun inFlightDepth(): Int = capacity - sem.availablePermits()
}
