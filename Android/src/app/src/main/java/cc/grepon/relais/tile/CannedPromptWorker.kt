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

package cc.grepon.relais.tile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import cc.grepon.relais.R
import cc.grepon.relais.core.RelaisInference
import cc.grepon.relais.templates.WorkflowRegistry

/** Inputs/IDs for the canned-prompt run. The prompt text is canned; the template supplies the system prompt. */
const val CANNED_WORK_NAME = "relais-canned-prompt"
private const val KEY_TEMPLATE_ID = "template_id"
private const val CHANNEL_ID = "relais_canned_result"
private const val NOTIFICATION_ID = 0x52454C41 // "RELA" — stable single slot, so results don't stack
private const val TAG = "CannedPromptWorker"
private const val RESULT_CAP = 600 // bound the notification body (shade is a public surface)
private const val CANNED_PROMPT = "Give me a one-line status check."

/**
 * One-shot canned-prompt run triggered from the QS tile (#2): resolve the configured template via
 * [WorkflowRegistry], run the in-process [RelaisInference] (a CONSUMER — it throws eagerly rather than
 * cold-starting), and post the answer as a notification. Enqueued with `enqueueUniqueWork(KEEP)` so
 * repeated taps don't stack inferences. Notification styled per DESIGN.md (amber beacon, private).
 */
class CannedPromptWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val templateId = inputData.getString(KEY_TEMPLATE_ID)?.takeIf { it.isNotBlank() }
      ?: return Result.failure()
    // Re-assert readiness inside the worker: the tile gated on isReady() at enqueue time, but the
    // node could have stopped before this runs — never let the run cold-start the engine.
    if (!RelaisInference.isReady()) {
      Log.i(TAG, "engine not resident at run time; skipping canned prompt")
      return Result.failure()
    }
    val system = WorkflowRegistry.resolve(applicationContext, templateId)?.system
    val answer = runCatching {
      RelaisInference.completeText(applicationContext, CANNED_PROMPT, system = system)
    }.getOrElse {
      if (it is kotlinx.coroutines.CancellationException) throw it // never swallow cancellation
      if (it is RelaisInference.NodeNotReadyException) {
        Log.i(TAG, "node went down mid-run; skipping notification")
      } else {
        Log.e(TAG, "canned prompt failed", it) // never silently swallow
      }
      return Result.failure()
    }
    postResult(answer.take(RESULT_CAP))
    return Result.success()
  }

  /** Posts the answer; silently no-ops if POST_NOTIFICATIONS isn't granted (runtime perm is API 33+). */
  private fun postResult(text: String) {
    val ctx = applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      Log.i(TAG, "POST_NOTIFICATIONS not granted; canned result not shown")
      return
    }
    ensureChannel(ctx)
    val notification =
      NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle("Relais · result")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSmallIcon(R.drawable.ic_relais_tile)
        .setColor(0xFFFFB000.toInt()) // DESIGN.md signal amber accent
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // shade content stays off the lock screen
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification) }
      .onFailure { Log.w(TAG, "failed to post canned result", it) }
  }

  private fun ensureChannel(ctx: Context) {
    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Relais results", NotificationManager.IMPORTANCE_DEFAULT)
          .apply { description = "Canned-prompt results from the Relais Quick Settings tile" }
      )
    }
  }

  companion object {
    /** Builds the work input for [templateId]. */
    fun inputData(templateId: String): Data =
      Data.Builder().putString(KEY_TEMPLATE_ID, templateId).build()
  }
}
