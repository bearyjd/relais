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
import cc.grepon.relais.RelaisRequest
import cc.grepon.relais.ThermalGovernor
import cc.grepon.relais.batch.BatchChat
import cc.grepon.relais.batch.WebhookDelivery
import cc.grepon.relais.data.BatchJob
import cc.grepon.relais.data.BatchStatus
import cc.grepon.relais.data.RelaisDatabase
import org.json.JSONObject

/**
 * Drains queued batch jobs (Feature #14) off the request path: marks RUNNING, runs the chat job through
 * [RelaisEngine], writes the result, and (if a webhook is set) POSTs the signed result. Bounded per run
 * ([MAX_PER_RUN]); re-kicks itself if jobs remain (a submit during a run, or > MAX_PER_RUN queued), so
 * nothing sits. Prunes jobs older than the TTL each run.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    val dao = RelaisDatabase.get(ctx).batchDao()
    runCatching { dao.deleteOlderThan(System.currentTimeMillis() - TTL_MS) }

    var processed = 0
    while (processed < MAX_PER_RUN) {
      val job = dao.byStatus(BatchStatus.QUEUED, 1).firstOrNull() ?: break
      dao.updateStatus(job.jobId, BatchStatus.RUNNING, System.currentTimeMillis())
      val (status, resultJson) = process(ctx, job)
      dao.finish(job.jobId, status, resultJson, System.currentTimeMillis())
      job.webhookUrl?.takeIf { it.isNotBlank() }?.let { url ->
        val envelope = JSONObject()
          .put("job_id", job.jobId).put("status", status).put("result", JSONObject(resultJson))
          .toString()
        runCatching { WebhookDelivery.deliver(ctx, url, envelope) }
      }
      processed++
    }
    // Anything left (submitted mid-run, or beyond the per-run cap) → run again.
    if (runCatching { dao.countByStatus(BatchStatus.QUEUED) }.getOrDefault(0) > 0) kick(ctx)
    return Result.success()
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
    } catch (e: Exception) {
      Log.w(TAG, "batch job ${job.jobId} failed: ${e.message}")
      BatchStatus.FAILED to JSONObject().put("error", "job failed: ${e.message}").toString()
    }

  companion object {
    private const val TAG = "RelaisBatch"
    private const val MAX_PER_RUN = 20
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
