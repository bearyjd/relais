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
 * One stored conversation turn for the optional server-side session memory (Feature #5). Added to
 * [RelaisDatabase] at schema v2 (see the migration there). DEFAULT-OFF: nothing is ever written here
 * unless [cc.grepon.relais.RelaisConfig.sessionMemoryEnabled] is on.
 *
 * Privacy (security M6): [sessionKey] at rest is a *header value* or a non-reversible *IP hash* (see
 * [cc.grepon.relais.RelaisSessionStore]) — never a raw IP. The `(sessionKey, createdAt)` index serves
 * the ordered per-session read and the per-session row-cap trim.
 */
@Entity(
  tableName = "session_turns",
  indices = [Index(value = ["sessionKey", "createdAt"])],
)
data class SessionTurn(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionKey: String,
  /** "user" | "assistant" — maps 1:1 to the OpenAI/[cc.grepon.relais.ParsedTurn] role. */
  val role: String,
  val content: String,
  /** Epoch milliseconds the turn was recorded (drives TTL prune + ordered reads). */
  val createdAt: Long,
)
