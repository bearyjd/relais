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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure admission-control functions in [RelaisAdmission.kt].
 * No Context, no Android types, no sockets — pure JVM.
 *
 * TDD order: all tests were written (RED) before [admit] / [decideBackpressure] /
 * [AdmissionDecision] existed; they compile-fail = RED. GREEN after implementing
 * RelaisAdmission.kt.
 */
class RelaisAdmissionTest {

  // -------------------------------------------------------------------------
  // 1. accept-under-capacity
  // -------------------------------------------------------------------------

  @Test
  fun `admit returns Accept when queue is empty`() {
    val result = admit(queuedOrRunning = 0, capacity = QUEUE_CAPACITY)
    assertEquals(AdmissionDecision.Accept, result)
  }

  @Test
  fun `admit returns Accept at one below capacity`() {
    // Boundary: capacity - 1 must still accept (strict less-than).
    val result = admit(queuedOrRunning = QUEUE_CAPACITY - 1, capacity = QUEUE_CAPACITY)
    assertEquals(AdmissionDecision.Accept, result)
  }

  // -------------------------------------------------------------------------
  // 2. reject-at-capacity
  // -------------------------------------------------------------------------

  @Test
  fun `admit returns Reject at exactly capacity`() {
    val result = admit(queuedOrRunning = QUEUE_CAPACITY, capacity = QUEUE_CAPACITY)
    assertTrue("Expected Reject at capacity", result is AdmissionDecision.Reject)
  }

  @Test
  fun `admit returns Reject when over capacity`() {
    val result = admit(queuedOrRunning = QUEUE_CAPACITY + 4, capacity = QUEUE_CAPACITY)
    assertTrue("Expected Reject over capacity", result is AdmissionDecision.Reject)
  }

  // -------------------------------------------------------------------------
  // 3. Retry-After value and clamp
  // -------------------------------------------------------------------------

  @Test
  fun `Retry-After is DEFAULT_SERVICE_SECONDS when one over capacity`() {
    // queuedOrRunning == capacity -> (capacity - capacity + 1) * DEFAULT_SERVICE_SECONDS = 1 * 8 = 8
    val result = admit(queuedOrRunning = QUEUE_CAPACITY, capacity = QUEUE_CAPACITY) as AdmissionDecision.Reject
    assertEquals(DEFAULT_SERVICE_SECONDS, result.retryAfterSeconds)
  }

  @Test
  fun `Retry-After clamps at MAX_RETRY_AFTER for deep backlog`() {
    val result = admit(queuedOrRunning = 100, capacity = QUEUE_CAPACITY) as AdmissionDecision.Reject
    assertEquals(MAX_RETRY_AFTER, result.retryAfterSeconds)
  }

  @Test
  fun `Retry-After is never below MIN_RETRY_AFTER`() {
    // Even for the smallest reject case, the value must be >= MIN_RETRY_AFTER.
    val result = admit(queuedOrRunning = QUEUE_CAPACITY, capacity = QUEUE_CAPACITY) as AdmissionDecision.Reject
    assertTrue("retryAfterSeconds must be >= MIN_RETRY_AFTER", result.retryAfterSeconds >= MIN_RETRY_AFTER)
  }

  @Test
  fun `Retry-After is monotonically non-decreasing as depth increases`() {
    val capacity = QUEUE_CAPACITY
    var prev = 0
    for (depth in capacity until capacity + 20) {
      val r = admit(queuedOrRunning = depth, capacity = capacity) as AdmissionDecision.Reject
      assertTrue(
        "retryAfterSeconds must be non-decreasing: prev=$prev current=${r.retryAfterSeconds} depth=$depth",
        r.retryAfterSeconds >= prev,
      )
      prev = r.retryAfterSeconds
    }
  }

  // -------------------------------------------------------------------------
  // 4. Thermal-shed vs queue ordering (decideBackpressure)
  // -------------------------------------------------------------------------

  @Test
  fun `thermal shed wins regardless of queue depth when under capacity`() {
    val result = decideBackpressure(shed = true, queuedOrRunning = 0, capacity = QUEUE_CAPACITY)
    assertEquals(BackpressureOutcome.Shed503, result)
  }

  @Test
  fun `thermal shed wins regardless of queue depth when at capacity`() {
    val result = decideBackpressure(shed = true, queuedOrRunning = QUEUE_CAPACITY, capacity = QUEUE_CAPACITY)
    assertEquals(BackpressureOutcome.Shed503, result)
  }

  @Test
  fun `thermal shed wins regardless of queue depth when over capacity`() {
    val result = decideBackpressure(shed = true, queuedOrRunning = QUEUE_CAPACITY + 5, capacity = QUEUE_CAPACITY)
    assertEquals(BackpressureOutcome.Shed503, result)
  }

  @Test
  fun `queue reject fires when cool device is at capacity`() {
    val result = decideBackpressure(shed = false, queuedOrRunning = QUEUE_CAPACITY, capacity = QUEUE_CAPACITY)
    assertTrue("Expected QueueReject429", result is BackpressureOutcome.QueueReject429)
  }

  @Test
  fun `admit fires when cool device is under capacity`() {
    val result = decideBackpressure(shed = false, queuedOrRunning = 0, capacity = QUEUE_CAPACITY)
    assertEquals(BackpressureOutcome.Admit, result)
  }

  // -------------------------------------------------------------------------
  // 5. Semaphore matches admit policy (runtime gate == spec)
  // -------------------------------------------------------------------------

  @Test
  fun `fair Semaphore rejects exactly when admit says Reject`() {
    val sem = Semaphore(QUEUE_CAPACITY, /* fair = */ true)
    // Drain all permits.
    repeat(QUEUE_CAPACITY) { assertTrue("permit $it should be available", sem.tryAcquire()) }
    // Next tryAcquire must fail — matching admit(QUEUE_CAPACITY, QUEUE_CAPACITY) == Reject.
    assertFalse("Semaphore must reject when drained (matches admit policy)", sem.tryAcquire())
    // Admit says Reject for the same depth.
    assertTrue(admit(QUEUE_CAPACITY, QUEUE_CAPACITY) is AdmissionDecision.Reject)
    // Release one permit; both policy and semaphore now accept.
    sem.release()
    assertTrue("Semaphore must accept after release", sem.tryAcquire())
    assertEquals(AdmissionDecision.Accept, admit(QUEUE_CAPACITY - 1, QUEUE_CAPACITY))
  }
}
