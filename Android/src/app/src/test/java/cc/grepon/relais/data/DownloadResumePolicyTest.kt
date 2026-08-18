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

package cc.grepon.relais.data

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a download *starting* apart from a download *resuming*.
 *
 * Both arrive as WorkManager `ENQUEUED`, and conflating them silently corrupted two things on every
 * interruption — the recorded duration and the start-event count. Neither is visible while testing
 * locally on a fast network, which is why it needs pinning here rather than by inspection.
 */
class DownloadResumePolicyTest {

  @Test
  fun `a first enqueue records the start time and logs the start event`() {
    val d = dispositionForEnqueue(existingStartTimeMs = 0L)
    assertFalse(d.isResume)
    assertTrue("a genuine start must stamp the timestamp", d.recordStartTime)
    assertTrue("a genuine start must be counted", d.logStartEvent)
  }

  @Test
  fun `a resume never overwrites the original start time`() {
    // The load-bearing one. Overwriting meant a download interrupted at 90% reported the duration of
    // only its final leg, so the slowest downloads recorded the fastest times — the metric was
    // wrong in the most misleading possible direction.
    val d = dispositionForEnqueue(existingStartTimeMs = 1_000L)
    assertTrue(d.isResume)
    assertFalse("resuming must not restamp the start time", d.recordStartTime)
  }

  @Test
  fun `a resume is not counted as another download start`() {
    // Otherwise a download stopped five times reports six starts against one completion, and the
    // start/finish ratio silently stops meaning anything.
    assertFalse(dispositionForEnqueue(existingStartTimeMs = 1_000L).logStartEvent)
  }

  @Test
  fun `any positive stored timestamp counts as already started`() {
    // Guards the boundary: a timestamp of 1ms past the epoch is still a real recorded start, and
    // must not be mistaken for the 0L "nothing stored" sentinel.
    assertTrue(dispositionForEnqueue(existingStartTimeMs = 1L).isResume)
    assertFalse(dispositionForEnqueue(existingStartTimeMs = 0L).isResume)
  }

  @Test
  fun `the quota stop reason is named explicitly enough to act on`() {
    // This is the string that would tell us Android 16's quota is actually biting in the field,
    // which is the evidence that would justify moving downloads off WorkManager entirely. If it
    // read "reason 10" nobody would ever connect it to the platform change.
    val text = describeStopReason(WorkInfo.STOP_REASON_QUOTA)
    assertTrue("quota stops must be self-explanatory in a log: $text", text.contains("quota"))
  }

  @Test
  fun `common stop reasons are distinguishable from each other`() {
    val quota = describeStopReason(WorkInfo.STOP_REASON_QUOTA)
    val network = describeStopReason(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
    val cancelled = describeStopReason(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
    assertEquals("distinct reasons must not collapse to one string", 3, setOf(quota, network, cancelled).size)
  }

  @Test
  fun `an unrecognized stop reason still reports its raw code`() {
    // Never swallow an unknown reason into "unknown": the raw code is what makes a new platform
    // stop reason searchable when it first shows up in a log.
    assertTrue(describeStopReason(9999).contains("9999"))
  }
}
