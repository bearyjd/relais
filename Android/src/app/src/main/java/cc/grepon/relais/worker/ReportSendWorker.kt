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

package cc.grepon.relais.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.chat.ContentReportDraft
import cc.grepon.relais.chat.attemptReportSend
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.ReportSendState
import java.util.concurrent.TimeUnit

private const val TAG = "RelaisReportSendWorker"

/**
 * Drains reports the operator opted in to sending but which never got delivered (#273).
 *
 * Before this worker, `ContentReportDelivery.send` made exactly one attempt: a report submitted on a
 * dead network was saved locally, told the operator "could not reach the developer", and there the
 * matter ended — despite an explicit opt-in. The row's `sendState` now survives process death, so the
 * retry does too.
 *
 * Only ever touches rows in [ReportSendState.PENDING]. A `none` row (the operator did not opt in, and
 * the backfilled state of every report written before #273) is never read, let alone transmitted —
 * that is the invariant the Data Safety declaration in `docs/store-submission.md` gate 1 rests on, and
 * this worker is the one piece of code that could quietly break it.
 *
 * Always returns [Result.success], never [Result.retry]: rescheduling is [attemptReportSend]'s job via
 * the pure policy in `ContentReportRetry.kt`, which knows the difference between a 429 and a 500 and
 * holds the attempt cap. Handing WorkManager its own independent backoff on top would double-schedule
 * retries and could spend the Worker's 10-per-hour caller budget twice as fast as the policy intends.
 */
class ReportSendWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    runCatching {
        val dao = RelaisDatabase.get(ctx).reportDao()
        val pending = dao.awaitingSend(ReportSendState.PENDING, MAX_PER_RUN)
        if (pending.isEmpty()) return@runCatching
        Log.i(TAG, "draining ${pending.size} pending report send(s)")
        for (report in pending) {
          attemptReportSend(
            context = ctx,
            reportId = report.id,
            draft =
              ContentReportDraft(
                reasonId = report.reasonId,
                excerpt = report.excerpt,
                note = report.note,
                modelId = report.modelId,
                backend = report.backend,
              ),
            surface = report.surface,
            attemptsSoFar = report.sendAttempts,
          )
        }
      }
      .onFailure { Log.w(TAG, "report drain tick failed (swallowed): ${it.message}") }
    return Result.success()
  }

  companion object {
    private const val UNIQUE_WORK = "relais_report_send"

    /**
     * Bound on one run, well under the Worker's 10-requests-per-60-minutes per-caller budget.
     *
     * Draining the whole backlog in one pass would 429 the tail of it, and — worse — leave nothing of
     * the budget for the operator's next *live* report, whose immediate send would then fail in front
     * of them. A leftover backlog is picked up by the next scheduled run instead.
     */
    private const val MAX_PER_RUN = 5

    /**
     * Schedule a drain in [delayMs].
     *
     * [ExistingWorkPolicy.REPLACE] rather than KEEP: the delay carries the policy's decision about how
     * long to wait, and a fresh attempt's decision is always the better-informed one — a rate-limited
     * attempt's 65-minute cooldown must be able to push back a queued 1-minute transient backoff,
     * otherwise the earlier, shorter schedule wins and walks straight into another 429. APPEND would be
     * worse still: the runs would pile up rather than coalesce.
     *
     * Requires connectivity, so a retry scheduled on a dead network waits for one instead of spending
     * an attempt to discover it is still down.
     */
    fun enqueue(context: Context, delayMs: Long) {
      val request =
        OneTimeWorkRequestBuilder<ReportSendWorker>()
          .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
    }
  }
}
