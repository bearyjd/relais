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

package cc.grepon.relais.widget

/** Bound on persisted model output in the launcher (a public surface). Mirrors the tile's cap. */
const val RESPONSE_CAP = 600

/** The four display phases the home-screen widget (#3) can render. */
enum class WidgetPhase { IDLE, LOADING, DONE, ERROR }

/**
 * Pure value object the [RelaisWidget] renders from (persisted via Glance Preferences state). Kept
 * free of Android/Glance types so the transition machine + cap are JVM-unit-testable.
 *
 * - [prompt]: the canned prompt that produced the current state (null in IDLE).
 * - [response]: the (capped) answer in DONE, or the error message in ERROR (null in IDLE/LOADING).
 *
 * Build states only through the transition helpers ([loading]/[done]/[error]/[idle]) so the cap is
 * always applied and a phase never leaks another phase's fields (e.g. LOADING never carries a stale
 * response).
 */
data class WidgetUiState(
  val phase: WidgetPhase,
  val prompt: String?,
  val response: String?,
) {
  /** Enters LOADING for [prompt], dropping any prior answer so a stale response can't flash. */
  fun loading(prompt: String): WidgetUiState =
    WidgetUiState(WidgetPhase.LOADING, prompt = prompt, response = null)

  /** Settles to DONE, keeping the originating [prompt] and carrying the capped [response]. */
  fun done(response: String): WidgetUiState =
    copy(phase = WidgetPhase.DONE, response = capResponse(response))

  /** Settles to ERROR, keeping the originating [prompt] and carrying the capped [message]. */
  fun error(message: String): WidgetUiState =
    copy(phase = WidgetPhase.ERROR, response = capResponse(message))

  /** CLEAR: back to the empty IDLE state (no prompt, no response). */
  fun cleared(): WidgetUiState = idle()

  companion object {
    /** The cleared/initial state. */
    fun idle(): WidgetUiState = WidgetUiState(WidgetPhase.IDLE, prompt = null, response = null)
  }
}

/** Caps [text] to [RESPONSE_CAP] — a no-op at/under the cap. Pure; unit-tested. */
fun capResponse(text: String): String = text.take(RESPONSE_CAP)

/**
 * Cold-start guard (pure, unit-tested): a widget tap may run a prompt ONLY when the engine is already
 * [ready] AND a non-blank [prompt] is present. False when the node is off (a tap must never cold-start
 * a multi-GB engine) or the prompt is missing/blank. Mirrors the QS tile's `isReady` gate.
 */
fun shouldRunWidgetPrompt(ready: Boolean, prompt: String?): Boolean =
  ready && !prompt.isNullOrBlank()
