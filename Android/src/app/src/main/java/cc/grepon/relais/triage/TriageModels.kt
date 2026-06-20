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
 * Urgency label produced by the resident model. The classifier is constrained to these three tokens
 * so prompt-injected notification content cannot escalate beyond mislabeling — there is no token that
 * triggers any device action. Anything unknown/malformed parses back to [NORMAL] (fail-safe: an
 * attacker-controlled notification can never force itself to URGENT via a malformed reply, and a
 * dropped/garbled reply never silently buries something as LOW).
 */
enum class Urgency {
  URGENT,
  NORMAL,
  LOW;

  companion object {
    fun fromToken(raw: String?): Urgency =
      when (raw?.trim()?.uppercase()) {
        "URGENT" -> URGENT
        "LOW" -> LOW
        else -> NORMAL
      }
  }
}

/**
 * One buffered notification. Content is length-capped at capture time and lives only in the in-memory
 * [NotificationTriageBuffer] — it is never persisted to disk and never leaves the device.
 *
 * @param key stable per-notification key (the platform StatusBarNotification key); used to dedupe
 *   updates and to mark a record classified without re-running inference on it.
 * @param pkg source package (must be on the allowlist to have been buffered at all).
 * @param title capped notification title.
 * @param text capped notification body text.
 * @param postedAt wall-clock millis when the notification was posted.
 * @param urgency null until the urgent-check worker classifies it; non-null records are not re-classified.
 */
data class TriageRecord(
  val key: String,
  val pkg: String,
  val title: String,
  val text: String,
  val postedAt: Long,
  val urgency: Urgency? = null,
)

/** A per-item verdict parsed from the urgency-check model output. [index] is 1-based (as enumerated in the prompt). */
data class TriageVerdict(val index: Int, val urgency: Urgency)

/** The grouped digest summary shown as a single low-importance notification. */
data class TriageDigest(val summary: String, val itemCount: Int)
