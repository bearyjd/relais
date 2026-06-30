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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for [RelaisAdmissionGate] — the shared/exclusive concurrency contract image
 * generation (#16) relies on. Pure JVM (a fair Semaphore underneath); no Context, no sockets.
 */
class RelaisAdmissionGateTest {

  // -------------------------------------------------------------------------
  // Shared mode: bounded concurrency (the original /generate + /chat behavior)
  // -------------------------------------------------------------------------

  @Test
  fun sharedAcquireBoundsConcurrencyToCapacity() {
    val gate = RelaisAdmissionGate(3)
    assertTrue(gate.tryAcquireShared())
    assertTrue(gate.tryAcquireShared())
    assertTrue(gate.tryAcquireShared())
    assertFalse("a 4th shared acquire must be rejected at capacity 3", gate.tryAcquireShared())

    gate.releaseShared()
    assertTrue("releasing one permit re-admits one shared holder", gate.tryAcquireShared())
  }

  @Test
  fun inFlightDepthTracksAcquisitions() {
    val gate = RelaisAdmissionGate(4)
    assertEquals(0, gate.inFlightDepth())
    gate.tryAcquireShared()
    gate.tryAcquireShared()
    assertEquals(2, gate.inFlightDepth())
    gate.releaseShared()
    assertEquals(1, gate.inFlightDepth())
  }

  // -------------------------------------------------------------------------
  // Exclusive mode: drain-the-gate (image generation)
  // -------------------------------------------------------------------------

  @Test
  fun exclusiveDrainsAnEmptyGateImmediately() {
    val gate = RelaisAdmissionGate(4)
    assertTrue("an idle gate should grant exclusive access at once", gate.tryAcquireExclusive(50))
    assertEquals("exclusive holds ALL permits", 4, gate.inFlightDepth())
    gate.releaseExclusive()
    assertEquals(0, gate.inFlightDepth())
  }

  @Test
  fun exclusiveExcludesSharedWhileHeld() {
    val gate = RelaisAdmissionGate(4)
    assertTrue(gate.tryAcquireExclusive(50))
    // While image-gen holds the whole gate, decode requests must fast-fail (no co-occupancy).
    assertFalse("shared must be excluded under an exclusive lock", gate.tryAcquireShared())
    gate.releaseExclusive()
    assertTrue("shared resumes once the exclusive lock is released", gate.tryAcquireShared())
  }

  @Test
  fun exclusiveTimesOutAndAcquiresNothingWhenGateIsBusy() {
    val gate = RelaisAdmissionGate(4)
    assertTrue(gate.tryAcquireShared()) // one decode in flight → gate cannot fully drain

    val t0 = System.nanoTime()
    assertFalse("exclusive cannot drain while a shared permit is held", gate.tryAcquireExclusive(60))
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
    assertTrue("a failed exclusive acquire should wait out (roughly) its timeout, was ${elapsedMs}ms", elapsedMs >= 40)

    // All-or-none: the failed exclusive grabbed NOTHING, so the rest of the capacity is still available.
    assertEquals(1, gate.inFlightDepth())
    assertTrue(gate.tryAcquireShared())
    assertTrue(gate.tryAcquireShared())
    assertTrue(gate.tryAcquireShared())
    assertEquals(4, gate.inFlightDepth())
  }

  @Test
  fun exclusiveAcquiresOnceInFlightSharedDrains() {
    val gate = RelaisAdmissionGate(4)
    assertTrue(gate.tryAcquireShared()) // hold one permit so exclusive (needs all 4) must wait

    val started = CountDownLatch(1)
    val result = AtomicReference<Boolean?>(null)
    val waiter = Thread {
      started.countDown()
      result.set(gate.tryAcquireExclusive(3000))
    }
    waiter.start()
    started.await()
    Thread.sleep(50) // best-effort: let the waiter enter the blocking acquire
    gate.releaseShared() // gate can now drain to full → the waiter should win exclusivity

    waiter.join(TimeUnit.SECONDS.toMillis(5))
    assertFalse("the exclusive waiter should have finished", waiter.isAlive)
    assertEquals("exclusive must succeed once the gate drains", true, result.get())
    assertEquals(4, gate.inFlightDepth())
    gate.releaseExclusive()
  }

  @Test
  fun secondConcurrentExclusiveFastFailsWithoutWaiting() {
    val gate = RelaisAdmissionGate(4)
    assertTrue(gate.tryAcquireExclusive(50)) // first image-gen holds the device

    // A 2nd exclusive must NOT park for the whole timeout on a wait it can't win — it fast-fails.
    val t0 = System.nanoTime()
    assertFalse("a 2nd concurrent exclusive must be rejected", gate.tryAcquireExclusive(5000))
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
    assertTrue("single-flight must fast-fail, not wait out the 5s timeout, was ${elapsedMs}ms", elapsedMs < 1000)

    gate.releaseExclusive()
    assertTrue("exclusive is available again once released", gate.tryAcquireExclusive(50))
    gate.releaseExclusive()
  }

  @Test
  fun sharedBargesPastAQueuedExclusiveWaiterThenExclusiveWinsOnDrain() {
    val gate = RelaisAdmissionGate(4)
    assertTrue(gate.tryAcquireShared()) // one decode in flight → exclusive (needs all 4) must wait

    val started = CountDownLatch(1)
    val result = AtomicReference<Boolean?>(null)
    val waiter = Thread {
      started.countDown()
      result.set(gate.tryAcquireExclusive(3000))
    }
    waiter.start()
    started.await()
    Thread.sleep(50) // let the exclusive waiter enter its (fair) blocking acquire

    // Decode must NOT be starved by a queued image-gen: a shared acquire barges past the waiter.
    assertTrue("shared must barge past a queued exclusive waiter", gate.tryAcquireShared())

    // Now drain both shared permits → the exclusive waiter can finally gather all 4.
    gate.releaseShared()
    gate.releaseShared()
    waiter.join(TimeUnit.SECONDS.toMillis(5))
    assertFalse("the exclusive waiter should have finished", waiter.isAlive)
    assertEquals("exclusive wins once the gate drains", true, result.get())
    gate.releaseExclusive()
  }

  @Test
  fun capacityMustBePositive() {
    assertThrows(IllegalArgumentException::class.java) { RelaisAdmissionGate(0) }
  }
}
