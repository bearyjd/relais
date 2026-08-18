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

package cc.grepon.relais.data

import androidx.work.WorkInfo

/**
 * What a WorkManager `ENQUEUED` transition means for a model download.
 *
 * `ENQUEUED` is two different events wearing one name: a download **starting**, and a download
 * **resuming** after the system stopped its worker. WorkManager marks an interrupted worker ENQUEUED
 * immediately so it can be rescheduled, so every stop — a lost network constraint today, and on
 * Android 16+ a spent JobScheduler runtime quota — comes back through the same branch as a fresh
 * start.
 *
 * Treating the two identically is what [DefaultDownloadRepository] used to do, and it corrupted two
 * things on every interruption: the persisted start timestamp was overwritten, so the duration
 * reported on success measured only the final leg rather than the whole download, and a second
 * "start" analytics event fired, inflating starts against completions. Neither symptom is visible
 * locally — they only show up as implausibly fast downloads and a start/finish ratio that drifts.
 */
data class EnqueueDisposition(
  /** True when a download that had already begun is coming back for another attempt. */
  val isResume: Boolean,
  /** Stamp the start timestamp. Only ever true for a genuine first start. */
  val recordStartTime: Boolean,
  /** Log the "download started" analytics event. Only ever true for a genuine first start. */
  val logStartEvent: Boolean,
)

/**
 * Decide what an `ENQUEUED` transition means, from the one piece of state that survives process
 * death: whether a start timestamp was already persisted for this model.
 *
 * Deliberately keyed on the stored timestamp rather than `WorkInfo.runAttemptCount`. The attempt
 * counter lives with the worker, so a download resumed after the app process was killed — the exact
 * case a multi-hour quota-throttled download produces — can present a fresh counter while the
 * download is plainly mid-flight. The persisted timestamp is the thing that actually tracks "this
 * download already began", which is the question being asked.
 *
 * Pure JVM (no Context, no WorkManager instance) so the whole matrix is unit-testable — mirrors
 * [shouldUnloadIdleEngine] in RelaisIdleTtl.kt.
 */
fun dispositionForEnqueue(existingStartTimeMs: Long): EnqueueDisposition =
  if (existingStartTimeMs > 0L) {
    EnqueueDisposition(isResume = true, recordStartTime = false, logStartEvent = false)
  } else {
    EnqueueDisposition(isResume = false, recordStartTime = true, logStartEvent = true)
  }

/**
 * A readable name for a WorkManager stop reason, for the log line that explains a resume.
 *
 * This exists because the app had **no** stop observability at all: nothing called
 * `getStopReason()`, so a download that halted could not be told apart from one throttled by the
 * system, one that lost the network, or one killed by the user. Android's own guidance for
 * diagnosing a stopped job is to log this value, and without it any claim about *why* downloads
 * stall in the field is speculation.
 *
 * [WorkInfo.STOP_REASON_QUOTA] is the one to watch on Android 16+: jobs running alongside a
 * foreground service now count against the app's JobScheduler runtime quota regardless of
 * `targetSdkVersion`, and a multi-gigabyte model download is exactly the shape of work that spends
 * it. Seeing this reason in the field is the evidence that would justify moving downloads off
 * WorkManager to a user-initiated data transfer job.
 */
fun describeStopReason(stopReason: Int): String =
  when (stopReason) {
    WorkInfo.STOP_REASON_NOT_STOPPED -> "not stopped"
    WorkInfo.STOP_REASON_QUOTA -> "job quota exhausted (Android 16+ counts FGS-concurrent jobs)"
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "lost the network constraint"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "device state (doze/thermal)"
    WorkInfo.STOP_REASON_APP_STANDBY -> "app standby bucket"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restricted"
    WorkInfo.STOP_REASON_TIMEOUT -> "execution timeout"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
    WorkInfo.STOP_REASON_USER -> "stopped by the user"
    WorkInfo.STOP_REASON_PREEMPT -> "preempted"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
    else -> "reason $stopReason"
  }
