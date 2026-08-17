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

import android.content.Context
import android.util.Log
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.ReportSendState
import cc.grepon.relais.worker.ReportSendWorker

private const val TAG = "RelaisReportSend"

/**
 * One delivery attempt, recorded on the row and rescheduled if it should be retried (#273).
 *
 * This is the seam between the pure policy ([dispositionFor]) and the two stateful things it drives:
 * the Room row and WorkManager. Both chat surfaces call it — `ChatViewModel` for the Relais chat and
 * `ChatPanel` for the inherited Gallery chat — for the same reason [persistContentReport] is shared:
 * the two surfaces must not drift on what an attempt does. It also backs the manual SEND action in
 * `CONFIGURE › REPORTED OUTPUT`, so a hand-triggered retry is accounted for identically to an
 * automatic one.
 *
 * Returns whether the report is now delivered, which is what [deliverReport]'s `send` parameter wants
 * — so the two chat surfaces' existing notice copy keeps working unchanged. The richer outcome lives
 * on the row.
 *
 * [reportId] is nullable so a caller with no persisted row (the save failed) can still attempt a send
 * without a special case; the attempt then simply has nowhere to be recorded, and no retry is
 * scheduled, because a report that isn't on disk cannot be retried after this process dies.
 *
 * [attempt] and [nowMs] are injected so this is testable without a network stack or a clock — the same
 * seam `ChatViewModel`'s `sendReport` parameter already provides.
 */
suspend fun attemptReportSend(
  context: Context,
  reportId: Long?,
  draft: ContentReportDraft,
  surface: String,
  attemptsSoFar: Int = 0,
  nowMs: Long = System.currentTimeMillis(),
  attempt: (ContentReportDraft, String) -> ReportSendResult = ContentReportDelivery::send,
): Boolean {
  val result = attempt(draft, surface)
  if (reportId == null) return result == ReportSendResult.SENT

  val disposition = dispositionFor(result, attemptsSoFar)
  // Swallow a persistence failure rather than let it undo the send: the report may genuinely have
  // reached the Worker, and throwing here would surface as "could not reach the developer" on a
  // successful delivery. A lost state write costs at most one redundant retry.
  runCatching {
    RelaisDatabase.get(context)
      .reportDao()
      .markSend(
        id = reportId,
        state = disposition.state,
        attempts = disposition.attempts,
        atMs = nowMs,
      )
  }
  // Scheduling is best-effort, and its failure must not propagate: WorkManager.getInstance throws when
  // WorkManager isn't initialized for the process, and letting that escape would abort the caller
  // mid-outcome — the operator would be left on the "saved" notice with no send verdict at all, which
  // is strictly worse than a missed retry. The row stays PENDING either way, so the next scheduled run
  // (or a manual SEND) still picks it up.
  disposition.retryDelayMs?.let {
    runCatching { ReportSendWorker.enqueue(context, it) }
      .onFailure { t -> Log.w(TAG, "could not schedule the report retry: ${t.message}") }
  }
  return disposition.state == ReportSendState.SENT
}
