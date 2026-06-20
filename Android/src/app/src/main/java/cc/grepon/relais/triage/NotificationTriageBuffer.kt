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

/**
 * Bounded, in-memory ring of pending notifications awaiting triage.
 *
 * **No disk persistence by design** — notification content is among the most sensitive data on the
 * device, so it is held only in process memory and only until a digest run consumes it (or the node
 * is killed). The buffer is FIFO-evicting at [MAX_RECORDS]; an update to an already-seen notification
 * (same key) replaces the prior record and moves it to the most-recent slot rather than double-counting.
 *
 * Thread-safe: the platform delivers `onNotificationPosted` callbacks off the main thread and the
 * triage workers read/mutate concurrently.
 */
object NotificationTriageBuffer {
  const val MAX_RECORDS = 200

  private val lock = Any()
  // key -> record, insertion-ordered so the oldest key is always the eviction target.
  private val records = LinkedHashMap<String, TriageRecord>()

  /** Buffer a notification (or replace an existing one with the same key, moving it to newest). */
  fun offer(record: TriageRecord) =
    synchronized(lock) {
      records.remove(record.key)
      records[record.key] = record
      while (records.size > MAX_RECORDS) {
        val oldest = records.keys.iterator().next()
        records.remove(oldest)
      }
    }

  /** Records that have not yet been assigned an urgency (the urgent worker's work-list). */
  fun snapshotUnclassified(): List<TriageRecord> =
    synchronized(lock) { records.values.filter { it.urgency == null } }

  /** All buffered records (the digest worker's work-list). */
  fun snapshotAll(): List<TriageRecord> = synchronized(lock) { records.values.toList() }

  fun size(): Int = synchronized(lock) { records.size }

  /** Assign urgencies by key; keys no longer present are ignored. */
  fun applyClassification(verdicts: Map<String, Urgency>) =
    synchronized(lock) {
      for ((key, urgency) in verdicts) {
        val existing = records[key] ?: continue
        records[key] = existing.copy(urgency = urgency)
      }
    }

  /**
   * Remove exactly the keys a worker has finished with (peek-then-remove-on-success). Removing by key
   * rather than clearing wholesale means a notification that arrived during the (multi-second) digest
   * inference is not silently dropped.
   */
  fun removeKeys(keys: Collection<String>) =
    synchronized(lock) { keys.forEach { records.remove(it) } }

  /** Hard drain — used by the kill switch and on disable. */
  fun clear() = synchronized(lock) { records.clear() }
}
