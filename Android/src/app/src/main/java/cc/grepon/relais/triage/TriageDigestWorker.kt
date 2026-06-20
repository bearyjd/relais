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

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.core.RelaisInference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Periodic digest pass. Summarizes all buffered notifications into one grouped low-importance
 * notification, then removes exactly the records it consumed (peek-then-remove-on-success, so a
 * notification that arrived during the multi-second inference is never dropped).
 *
 * CONSUMER of the resident engine: [Result.retry] when the node is down or thermal-shedding, so a
 * scheduled run never burns the engine for a digest under pressure and never loses notifications.
 */
class TriageDigestWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    // Disabled — the kill switch may have fired without the control screen open. Drain and stop.
    if (!TriageConfig.enabled(ctx)) {
      NotificationTriageBuffer.clear()
      return Result.success()
    }
    // One digest at a time across BOTH entry points (periodic + "Triage now"), so a manual tap during
    // a scheduled run can't double-summarize the same batch, post twice, or double-count the metric.
    if (!inProgress.compareAndSet(false, true)) return Result.success()
    try {
      if (!RelaisEngine.isReady) return Result.retry()
      if (ThermalGovernor.shouldShed()) return Result.retry()

      // URGENT items are surfaced individually by the urgent worker (and removed there); exclude them
      // so the digest can never recap an already-surfaced item — makes "delivered once" exact and
      // closes the classify→remove vs snapshot race. A rare lingering URGENT record (urgent worker
      // died mid-pass) is dropped by the ring buffer's eviction rather than recapped.
      val pending = NotificationTriageBuffer.snapshotAll().filterNot { it.urgency == Urgency.URGENT }
      if (pending.isEmpty()) return Result.success()
      val batch = pending.take(TriagePromptBuilder.MAX_ITEMS)

      val reply =
        try {
          RelaisInference.completeText(
            ctx,
            TriagePromptBuilder.buildDigestPrompt(batch),
            TriagePromptBuilder.DIGEST_SYSTEM,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return Result.retry()
        }

      TriageNotifications.postDigest(ctx, TriagePromptBuilder.parseDigest(reply, batch.size))
      NotificationTriageBuffer.removeKeys(batch.map { it.key })
      RelaisMetrics.recordTriageDigest()
      return Result.success()
    } finally {
      inProgress.set(false)
    }
  }

  companion object {
    const val UNIQUE = "relais_triage_digest"
    private const val UNIQUE_NOW = "relais_triage_digest_now"

    // Bounds digest execution to one at a time across the periodic + "Triage now" entry points.
    // Assumes WorkManager's default single-process execution (no android:process= split for workers).
    private val inProgress = AtomicBoolean(false)

    private fun periodicRequest(context: Context) =
      PeriodicWorkRequestBuilder<TriageDigestWorker>(TriageConfig.intervalMinutes(context), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
        .build()

    /** Schedule the periodic digest, keeping any existing schedule (idempotent). */
    fun ensureScheduled(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, periodicRequest(context))
    }

    /** Replace the schedule after an interval change. */
    fun reschedule(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(context))
    }

    fun cancel(context: Context) {
      val wm = WorkManager.getInstance(context)
      wm.cancelUniqueWork(UNIQUE)
      wm.cancelUniqueWork(UNIQUE_NOW)
    }

    /** Run a digest immediately ("Triage now"); replaces any pending manual run. */
    fun triggerNow(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_NOW,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<TriageDigestWorker>().build(),
      )
    }
  }
}
