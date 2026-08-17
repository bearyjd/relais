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
 * The four states a report submission can settle into (#258 gate 1). Data, not a formatted string —
 * [cc.grepon.relais.ChatViewModel] and `cc.grepon.relais.ui.common.chat.ChatPanel` each render these
 * into their own copy (a persistent all-caps strip vs. a Material snackbar; the two surfaces have
 * genuinely different presentation conventions, not just duplicated ones).
 */
enum class ReportOutcome {
  SAVE_FAILED,
  SAVED_ONLY,
  SAVED_AND_SENT,
  SAVED_SEND_FAILED,
}

/**
 * The one gating + sequencing rule for a report submission, shared by both chat surfaces so it exists
 * exactly once: save always happens; the send is a separate, later, explicit per-report opt-in that
 * only runs when the save succeeded ([saved]) *and* the operator asked for it ([alsoSend]).
 *
 * [onOutcome] fires twice when a send is attempted — once for [ReportOutcome.SAVED_ONLY] immediately,
 * then again once [send] resolves — so a caller can show "saved" right away rather than leaving the
 * operator with up to ~35s of silence while a slow network call resolves (the original version of this
 * feature awaited the send before showing anything, which read as "nothing happened" and invited
 * duplicate reports). It fires once for [ReportOutcome.SAVE_FAILED] and returns immediately when the
 * save itself failed, and once for [ReportOutcome.SAVED_ONLY] with no follow-up when the operator
 * didn't opt in to sending.
 *
 * [send] and [onOutcome] are both injected so this function is unit-testable without a network stack
 * or a Compose/ViewModel host — the same seam [cc.grepon.relais.ChatViewModel] already uses for its
 * speech test dispatcher.
 */
suspend fun deliverReport(
  saved: Boolean,
  alsoSend: Boolean,
  draft: ContentReportDraft?,
  surface: String,
  send: suspend (ContentReportDraft, String) -> Boolean,
  onOutcome: (ReportOutcome) -> Unit,
) {
  // [draft] is nullable because a caller may know `saved=false` without having a draft at all (e.g.
  // the draft itself was rejected before a save was ever attempted) — that's still just "could not
  // save", the same outcome as a draft that built fine but failed to persist. It's never read when
  // saved is false, so `saved=true` with a null draft would be a caller bug, not a state this
  // function needs to model separately.
  if (!saved || draft == null) {
    onOutcome(ReportOutcome.SAVE_FAILED)
    return
  }
  onOutcome(ReportOutcome.SAVED_ONLY)
  if (alsoSend) {
    val sent = send(draft, surface)
    onOutcome(if (sent) ReportOutcome.SAVED_AND_SENT else ReportOutcome.SAVED_SEND_FAILED)
  }
}
