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

package cc.grepon.relais.share

/**
 * The four outcomes of a share into the node. RUN is the only one that triggers inference; the rest
 * are dead-ends the trampoline reports and finishes on. Modeled as an enum so callers do an
 * exhaustive `when` (no `else`).
 */
enum class ShareDecision {
  /** Enabled, engine resident, and a non-blank payload — run the in-process inference. */
  RUN,

  /** The operator turned the share target off in the control panel. */
  DISABLED,

  /** The node is not running (engine not resident). Never RUN here — a share must not cold-start. */
  NODE_OFF,

  /** Nothing usable was shared (no text, all blank). */
  EMPTY,
}

private const val SEPARATOR = "\n\n"

/**
 * Builds the prompt text from an `ACTION_SEND` `EXTRA_TEXT` (+ optional `EXTRA_SUBJECT` prefix) or an
 * `ACTION_SEND_MULTIPLE` `EXTRA_TEXT` list (joined with blank lines). Pure (no Android types) so it's
 * JVM-unit-testable.
 *
 * Rules:
 * - Single [text] wins over [extraTexts] when both are present (ACTION_SEND populates EXTRA_TEXT).
 * - Blank entries are dropped; everything is trimmed.
 * - A non-blank [subject] is prefixed once (skipped if it equals the body, so apps that duplicate the
 *   string into both extras don't echo it twice).
 * - The result is capped to [maxChars] (the subject prefix counts toward the cap).
 * - Returns null when nothing usable remains, or when [maxChars] is non-positive.
 */
fun extractSharedText(
  text: String?,
  subject: String?,
  extraTexts: List<String>?,
  maxChars: Int,
): String? {
  if (maxChars <= 0) return null

  val body =
    text?.trim()?.takeIf { it.isNotEmpty() }
      ?: extraTexts
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(SEPARATOR)
      ?: return null

  val cleanSubject = subject?.trim()?.takeIf { it.isNotEmpty() && it != body }
  val combined = if (cleanSubject != null) cleanSubject + SEPARATOR + body else body

  return combined.take(maxChars).takeIf { it.isNotBlank() }
}

/**
 * The cold-start guard in pure form. RUN only when the target is enabled, the engine is already
 * resident ([ready]), and there's a non-blank [payload] — so a share can NEVER cold-start the engine.
 * Precedence: DISABLED (operator off-switch) > NODE_OFF (most actionable: "start it first") > EMPTY.
 */
fun shouldRunShare(shareEnabled: Boolean, ready: Boolean, payload: String?): ShareDecision =
  when {
    !shareEnabled -> ShareDecision.DISABLED
    !ready -> ShareDecision.NODE_OFF
    payload.isNullOrBlank() -> ShareDecision.EMPTY
    else -> ShareDecision.RUN
  }
