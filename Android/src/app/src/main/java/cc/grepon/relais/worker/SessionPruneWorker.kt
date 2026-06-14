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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.grepon.relais.RelaisConfig
import cc.grepon.relais.RelaisMetrics
import cc.grepon.relais.RelaisSessionStore
import java.util.concurrent.TimeUnit

private const val TAG = "SessionPruneWorker"

/**
 * Periodic TTL/cap prune for the optional session memory (Feature #5). DEFAULT-OFF aware: when
 * [RelaisConfig.sessionMemoryEnabled] is false it no-ops immediately (no DB open). Otherwise it
 * deletes turns past the configured TTL and refreshes the stored-turn gauge. Cheap and best-effort —
 * always returns success so WorkManager never escalates a backoff for a prune miss.
 */
class SessionPruneWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val ctx = applicationContext
    if (!RelaisConfig.sessionMemoryEnabled(ctx)) {
      // DEFAULT-OFF: never open the DB when the feature is disabled.
      RelaisMetrics.setSessionTurns(0)
      return Result.success()
    }
    runCatching {
      val ttlMs = RelaisConfig.sessionTtlHours(ctx).toLong() * 60L * 60L * 1000L
      RelaisSessionStore.prune(
        ctx,
        ttlMs,
        RelaisConfig.sessionMaxTurns(ctx),
        RelaisConfig.sessionGlobalMaxTurns(ctx),
      )
      RelaisMetrics.setSessionTurns(RelaisSessionStore.totalTurns(ctx).toLong())
    }.onFailure { Log.w(TAG, "session prune tick failed (swallowed)") }
    return Result.success()
  }

  companion object {
    private const val UNIQUE_WORK = "relais_session_prune"
    private const val INTERVAL_HOURS = 6L

    /**
     * Schedules the periodic prune (idempotent — KEEP policy preserves an existing schedule). Safe to
     * call unconditionally on node start: the worker itself no-ops when the feature is off, so there
     * is no behavior change for operators who never enable session memory.
     */
    fun schedule(context: Context) {
      val request =
        PeriodicWorkRequestBuilder<SessionPruneWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
  }
}
