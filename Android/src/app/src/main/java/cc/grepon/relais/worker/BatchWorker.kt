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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.RelaisEngine
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.RelaisRequest
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.batch.BatchChat
import cc.grepon.relais.batch.WebhookDelivery
import cc.grepon.relais.data.BatchJob
import cc.grepon.relais.data.BatchStatus
import cc.grepon.relais.data.RelaisDatabase
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Drains queued batch jobs (Feature #14) off the request path: atomically claims a job (queued→running),
 * runs the chat through [RelaisEngine], writes the result, and (if a webhook is set) POSTs the signed
 * result. Bounded per run ([MAX_PER_RUN]) so a single execution stays well under WorkManager's 10-minute
 * hard ceiling (each job is capped by the engine's own per-request timeout); it re-kicks itself if jobs
 * remain, so throughput is preserved across runs. On every start it first reaps jobs stranded `running`
 * by a previously-killed worker, then TTL-prunes old jobs.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    val dao = RelaisDatabase.get(ctx).batchDao()
    val startMs = System.currentTimeMillis()
    runCatching { dao.deleteOlderThan(startMs - TTL_MS) }
    // Recover anything a prior worker left `running` (process death / reboot / time-budget kill) so a
    // client never polls `running` forever. Cutoff is well past the max single-job time.
    runCatching {
      dao.failStaleRunning(
        running = BatchStatus.RUNNING,
        failed = BatchStatus.FAILED,
        errorJson = STALE_ERROR_JSON,
        now = startMs,
        cutoffMs = startMs - STALE_RUNNING_MS,
      )
    }

    var processed = 0
    var iterations = 0
    // `processed` bounds the per-run inference budget; `iterations` is a cheap safety cap so a burst of
    // lost claims (another drain raced us) can't spin. Each iteration removes one job from the queued set
    // (we claim it, or the winner already did), so the loop converges regardless.
    while (processed < MAX_PER_RUN && iterations < MAX_PER_RUN * 4) {
      iterations++
      val job = dao.byStatus(BatchStatus.QUEUED, 1).firstOrNull() ?: break
      val now = System.currentTimeMillis()
      if (dao.claim(job.jobId, BatchStatus.QUEUED, BatchStatus.RUNNING, now) == 0) continue // lost the race
      val (status, resultJson) = process(ctx, job)
      dao.finish(job.jobId, status, resultJson, System.currentTimeMillis())
      deliverWebhook(ctx, job, status, resultJson)
      processed++
    }
    // Anything left (submitted mid-run, or beyond the per-run cap) → run again.
    if (runCatching { dao.countByStatus(BatchStatus.QUEUED) }.getOrDefault(0) > 0) kick(ctx)
    return Result.success()
  }

  /** Best-effort signed webhook; records the delivery outcome so silent loss is observable to operators. */
  private fun deliverWebhook(ctx: Context, job: BatchJob, status: String, resultJson: String) {
    val url = job.webhookUrl?.takeIf { it.isNotBlank() } ?: return
    val envelope = JSONObject()
      .put("job_id", job.jobId).put("status", status).put("result", JSONObject(resultJson))
      .toString()
    val delivered = runCatching { WebhookDelivery.deliver(ctx, url, envelope) }.getOrDefault(false)
    RelaisMetrics.recordWebhookDelivery(delivered)
  }

  private fun process(ctx: Context, job: BatchJob): Pair<String, String> =
    try {
      val req = BatchChat.extract(JSONObject(job.requestJson))
        ?: return BatchStatus.FAILED to
          JSONObject().put("error", "batch v1 needs a chat 'messages' body with a text user turn").toString()
      val result = RelaisEngine.generate(
        ctx,
        RelaisRequest(text = req.text, systemPrompt = req.system, temperature = req.temperature, topP = req.topP, seed = req.seed),
        shouldCancel = { ThermalGovernor.shouldTruncate() },
      )
      BatchStatus.COMPLETED to BatchChat.envelope(job.jobId, result.text, result.completionTokens, result.finishReason).toString()
    } catch (e: CancellationException) {
      throw e // structured-concurrency contract: never swallow cancellation
    } catch (e: Exception) {
      Log.w(TAG, "batch job ${job.jobId} failed: ${e.message}")
      BatchStatus.FAILED to JSONObject().put("error", "job failed: ${e.message}").toString()
    }

  companion object {
    private const val TAG = "RelaisBatch"
    // Bounded so worst-case (MAX_PER_RUN × the engine's per-request timeout) stays under WorkManager's
    // 10-minute execution ceiling; leftover jobs are drained by the re-kick.
    private const val MAX_PER_RUN = 3
    // A `running` job whose updatedAt is older than this was orphaned by a killed worker. Must stay
    // comfortably above the engine's per-request timeout (~120s) so a legitimately in-flight job is
    // never reaped; if that timeout is ever raised toward this value, raise this too.
    private const val STALE_RUNNING_MS = 5L * 60 * 1000
    private const val STALE_ERROR_JSON = """{"error":"job interrupted (worker stopped before completion)"}"""
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000 // keep results 7 days
    private const val UNIQUE = "relais-batch-drain"

    /** Kicks a drain (on submit + at node startup). APPEND_OR_REPLACE guarantees a run follows a submit. */
    fun kick(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<BatchWorker>().build(),
      )
    }
  }
}
