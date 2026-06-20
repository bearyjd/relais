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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.core.RelaisInference
import kotlinx.coroutines.CancellationException

/**
 * Near-real-time urgent-surfacing pass. **Never runs per-notification** — it is kicked only through
 * [kickThrottled], which the [TriageRateLimiter] gates to at most once per cooldown window, so a
 * notification storm produces at most one classification inference that labels the whole buffered
 * batch in a single pass.
 *
 * The pass is a pure CONSUMER of the resident engine: it re-asserts readiness and thermal headroom
 * and returns [Result.retry] (never drops records) if the node is down or shedding. Records it
 * classifies as NORMAL/LOW stay buffered for the periodic [TriageDigestWorker]; URGENT ones are
 * surfaced immediately.
 */
class TriageUrgentWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    if (!TriageConfig.enabled(ctx) || !TriageConfig.urgentEnabled(ctx)) return Result.success()
    if (!RelaisEngine.isReady) return Result.retry()
    if (ThermalGovernor.shouldShed()) return Result.retry()

    val pending = NotificationTriageBuffer.snapshotUnclassified()
    if (pending.isEmpty()) return Result.success()
    val batch = pending.take(TriagePromptBuilder.MAX_ITEMS)

    val reply =
      try {
        RelaisInference.completeText(
          ctx,
          TriagePromptBuilder.buildUrgencyPrompt(batch),
          TriagePromptBuilder.URGENCY_SYSTEM,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Node went down mid-pass or inference failed transiently — keep records, retry later.
        return Result.retry()
      }

    // Default every record in this batch to NORMAL, then overlay the model's verdicts (fail-safe: a
    // malformed/garbled reply buries nothing as LOW and escalates nothing to URGENT). Extracted to
    // TriagePromptBuilder.classify so this decision is directly unit-tested.
    val classification = TriagePromptBuilder.classify(batch.map { it.key }, reply)
    NotificationTriageBuffer.applyClassification(classification)

    val urgent = batch.filter { classification[it.key] == Urgency.URGENT }
    if (urgent.isNotEmpty()) {
      TriageNotifications.postUrgent(ctx, urgent)
      RelaisMetrics.recordTriageUrgent(urgent.size)
      // Surfaced individually → drop them so the periodic digest doesn't recap the same items.
      NotificationTriageBuffer.removeKeys(urgent.map { it.key })
    }
    return Result.success()
  }

  companion object {
    const val UNIQUE = "relais_triage_urgent"

    /**
     * Enqueue an urgent pass iff the cooldown has elapsed (storm safety). KEEP coalesces any
     * already-pending pass so concurrent kicks never stack.
     */
    fun kickThrottled(context: Context) {
      if (!TriageRateLimiter.tryClaimUrgent(System.currentTimeMillis())) return
      WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<TriageUrgentWorker>().build(),
      )
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
  }
}
