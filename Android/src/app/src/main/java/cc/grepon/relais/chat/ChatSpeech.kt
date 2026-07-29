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

package cc.grepon.relais.chat

/**
 * In-app speech playback state for the chat screen (issue #211). Exactly one assistant turn can be
 * speaking at a time, so this is a single top-level state carrying the turn it applies to — not a
 * per-turn map. Every non-[Idle] state names its turn, which is what lets the message list decide
 * whether a given row shows SPEAK or the active label.
 */
sealed interface SpeechState {
  /** Nothing is being synthesized or played. */
  data object Idle : SpeechState

  /** The voice model isn't on disk yet; a background provision was kicked. */
  data class Fetching(val turnId: String) : SpeechState

  /** Synthesis is running (CPU-bound; ~RTF 0.12) — audio hasn't started yet. */
  data class Preparing(val turnId: String) : SpeechState

  /** Audio is playing out the speaker. */
  data class Speaking(val turnId: String) : SpeechState

  /**
   * Synthesis or playback failed for this turn.
   *
   * [message] is **diagnostic only** — the row renders a fixed `SPEECH FAILED` label (see
   * [speechActionLabel]), so this value reaches logs and tests, never the screen. Keep it that way:
   * exception text here can carry absolute storage paths, and this row is exactly what a user
   * screenshots into a bug report.
   */
  data class Failed(val turnId: String, val message: String) : SpeechState
}

/** The turn this state applies to, or null when [SpeechState.Idle]. */
fun SpeechState.turnId(): String? =
  when (this) {
    is SpeechState.Idle -> null
    is SpeechState.Fetching -> turnId
    is SpeechState.Preparing -> turnId
    is SpeechState.Speaking -> turnId
    is SpeechState.Failed -> turnId
  }

/**
 * The action-label text for [turnId]'s row, given the screen-wide [state]. Pure so the whole label
 * matrix is JVM-tested rather than eyeballed on-device.
 *
 * A turn that isn't the active one always reads `SPEAK` — including while *another* turn is speaking,
 * because tapping it is a legal action (it supersedes the current playback).
 */
fun speechActionLabel(state: SpeechState, turnId: String): String {
  if (state.turnId() != turnId) return "SPEAK"
  return when (state) {
    is SpeechState.Idle -> "SPEAK"
    is SpeechState.Fetching -> "FETCHING VOICE"
    is SpeechState.Preparing -> "SYNTHESIZING"
    is SpeechState.Speaking -> "STOP"
    is SpeechState.Failed -> "SPEECH FAILED"
  }
}

/**
 * True when tapping [turnId]'s label should *stop* rather than start. Only an actively-speaking turn
 * stops; a tap during [SpeechState.Preparing] is ignored (see [speechActionEnabled]) so a slow
 * synthesis can't be half-cancelled into an inconsistent state.
 */
fun speechActionStops(state: SpeechState, turnId: String): Boolean =
  state is SpeechState.Speaking && state.turnId == turnId

/**
 * False only while this row's own synthesis is in flight — there, the label is a status, not a button.
 *
 * [SpeechState.Fetching] stays **enabled** on purpose. A voice download is ~64 MB and can fail
 * (the provisioner logs and gives up), so a disabled FETCHING label would strand the row forever with
 * no way out. Re-tapping is idempotent — it re-checks availability and no-ops if a fetch is already
 * running — so leaving it tappable is the recoverable choice.
 */
fun speechActionEnabled(state: SpeechState, turnId: String): Boolean {
  if (state.turnId() != turnId) return true
  return state !is SpeechState.Preparing
}

/**
 * Whether an assistant turn should offer speech at all. Error turns ([ERROR_BACKEND]) are excluded —
 * reading `[error] connection refused` aloud is noise, not a feature.
 */
fun turnIsSpeakable(backend: String?, content: String): Boolean =
  backend != ERROR_BACKEND && content.isNotBlank()
