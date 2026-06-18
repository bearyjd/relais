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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One async batch job (Feature #14). [requestJson] is the stored chat-completions request body; the
 * [BatchWorker] runs it off the request path, writes [resultJson], and (if [webhookUrl] is set) POSTs
 * the signed result. [status] is one of [BatchStatus]. [jobId] is the public UUID the client polls.
 */
@Entity(
  tableName = "batch_jobs",
  indices = [
    Index(value = ["status", "createdAt"]),
    Index(value = ["jobId"], unique = true),
  ],
)
data class BatchJob(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val jobId: String,
  val status: String,
  val requestJson: String,
  val resultJson: String?,
  val webhookUrl: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

/** Batch job lifecycle states (stored as the `status` TEXT column). */
object BatchStatus {
  const val QUEUED = "queued"
  const val RUNNING = "running"
  const val COMPLETED = "completed"
  const val FAILED = "failed"
}
