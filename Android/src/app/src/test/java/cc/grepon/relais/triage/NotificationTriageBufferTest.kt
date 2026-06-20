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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationTriageBufferTest {
  private fun rec(key: String, urgency: Urgency? = null) =
    TriageRecord(key = key, pkg = "com.example", title = "t-$key", text = "b-$key", postedAt = 0L, urgency = urgency)

  @Before fun setUp() = NotificationTriageBuffer.clear()

  @After fun tearDown() = NotificationTriageBuffer.clear()

  @Test fun `offer buffers records in order`() {
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b"))
    assertEquals(listOf("a", "b"), NotificationTriageBuffer.snapshotAll().map { it.key })
  }

  @Test fun `offer with existing key replaces and moves to newest`() {
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b"))
    NotificationTriageBuffer.offer(rec("a").copy(title = "updated"))
    val all = NotificationTriageBuffer.snapshotAll()
    assertEquals(listOf("b", "a"), all.map { it.key })
    assertEquals("updated", all.last().title)
    assertEquals(2, NotificationTriageBuffer.size())
  }

  @Test fun `eviction keeps only the newest MAX_RECORDS`() {
    val total = NotificationTriageBuffer.MAX_RECORDS + 25
    for (i in 1..total) NotificationTriageBuffer.offer(rec("k$i"))
    assertEquals(NotificationTriageBuffer.MAX_RECORDS, NotificationTriageBuffer.size())
    val keys = NotificationTriageBuffer.snapshotAll().map { it.key }
    assertTrue(keys.contains("k$total"))
    assertTrue(!keys.contains("k1"))
  }

  @Test fun `snapshotUnclassified excludes classified records`() {
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b", urgency = Urgency.LOW))
    assertEquals(listOf("a"), NotificationTriageBuffer.snapshotUnclassified().map { it.key })
  }

  @Test fun `applyClassification sets urgency by key and ignores unknown keys`() {
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b"))
    NotificationTriageBuffer.applyClassification(mapOf("a" to Urgency.URGENT, "ghost" to Urgency.LOW))
    val all = NotificationTriageBuffer.snapshotAll().associateBy { it.key }
    assertEquals(Urgency.URGENT, all["a"]?.urgency)
    assertNull(all["b"]?.urgency)
  }

  @Test fun `removeKeys drops only the named keys`() {
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b"))
    NotificationTriageBuffer.offer(rec("c"))
    NotificationTriageBuffer.removeKeys(listOf("a", "c"))
    assertEquals(listOf("b"), NotificationTriageBuffer.snapshotAll().map { it.key })
  }

  @Test fun `a record offered during inference is not dropped by a stale removeKeys`() {
    // Models the worker flow: snapshot a batch, run a slow inference, then remove exactly that batch.
    NotificationTriageBuffer.offer(rec("a"))
    NotificationTriageBuffer.offer(rec("b"))
    val inFlightBatch = NotificationTriageBuffer.snapshotAll().map { it.key } // [a, b]
    // A new notification arrives mid-inference:
    NotificationTriageBuffer.offer(rec("c"))
    // Worker finishes and removes only what it consumed:
    NotificationTriageBuffer.removeKeys(inFlightBatch)
    assertEquals(listOf("c"), NotificationTriageBuffer.snapshotAll().map { it.key })
  }

  @Test fun `concurrent offers do not lose or corrupt records`() {
    val threads =
      (1..8).map { t ->
        Thread {
          for (i in 1..50) NotificationTriageBuffer.offer(rec("t$t-$i"))
        }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    // 8*50 = 400 distinct keys offered; capacity caps at MAX_RECORDS with no exception.
    assertEquals(NotificationTriageBuffer.MAX_RECORDS, NotificationTriageBuffer.size())
  }
}
