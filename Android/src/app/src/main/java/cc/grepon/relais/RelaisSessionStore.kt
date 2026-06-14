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

package cc.grepon.relais

import android.content.Context
import android.util.Log
import cc.grepon.relais.data.RelaisDatabase
import cc.grepon.relais.data.SessionTurn
import java.security.MessageDigest

private const val TAG = "RelaisSessionStore"

/**
 * Storage facade for the optional server-side session memory (Feature #5). Wraps [SessionDao] with
 * the session-budget read, best-effort record, explicit clear, and TTL/cap prune. DEFAULT-OFF: all
 * call sites are gated on [RelaisConfig.sessionMemoryEnabled] by the HTTP layer; this object is never
 * touched when the flag is off (no DB open on the disabled hot path).
 *
 * Privacy (security M6): the IP-fallback key is a non-reversible SHA-256 of `ip + salt` (the per-
 * install API key is the salt), truncated for compactness — the raw IP is NEVER stored, logged, or
 * placed in a metric label. The stored key is a hash (or an operator-supplied header value), not an
 * address.
 */
object RelaisSessionStore {

  /**
   * Loads up to [budget] most-recent stored turns for [key], oldest-first, as [ParsedTurn]s shaped
   * exactly like client history (so they seed via the same [RelaisEngine] initialMessages path).
   * Best-effort: any failure returns an empty list rather than failing the request.
   */
  suspend fun loadHistory(context: Context, key: String, budget: Int): List<ParsedTurn> =
    runCatching {
      if (budget <= 0) return emptyList()
      val dao = RelaisDatabase.get(context).sessionDao()
      // recentDesc returns newest-first; reverse to oldest-first for seeding.
      dao.recentDesc(key, budget).asReversed().map { ParsedTurn(role = it.role, text = it.content) }
    }.getOrElse {
      Log.w(TAG, "loadHistory failed for a session (swallowed)")
      emptyList()
    }

  /**
   * Records the live user turn + assistant reply for [key] (best-effort, after the reply is sent).
   * Blank turns are skipped. Trims the session to the per-session cap so DB growth stays bounded.
   * Never throws — a persistence failure must not affect the already-sent response.
   */
  suspend fun record(context: Context, key: String, userContent: String, assistantContent: String) {
    runCatching {
      val dao = RelaisDatabase.get(context).sessionDao()
      val now = System.currentTimeMillis()
      if (userContent.isNotBlank()) {
        dao.insert(SessionTurn(sessionKey = key, role = "user", content = userContent, createdAt = now))
      }
      if (assistantContent.isNotBlank()) {
        // +1 ms so the assistant turn orders strictly after the user turn even at the same wall clock.
        dao.insert(
          SessionTurn(sessionKey = key, role = "assistant", content = assistantContent, createdAt = now + 1)
        )
      }
      dao.trimToCap(key, RelaisConfig.sessionMaxTurns(context))
    }.onFailure { Log.w(TAG, "record failed for a session (swallowed)") }
  }

  /** Clears all stored turns for [key]. Best-effort. */
  suspend fun clear(context: Context, key: String) {
    runCatching { RelaisDatabase.get(context).sessionDao().deleteFor(key) }
      .onFailure { Log.w(TAG, "clear failed for a session (swallowed)") }
  }

  /** Number of stored turns for [key] (for GET /v1/sessions metadata). 0 on failure. */
  suspend fun count(context: Context, key: String): Int =
    runCatching { RelaisDatabase.get(context).sessionDao().countFor(key) }.getOrDefault(0)

  /**
   * Prunes turns older than [ttlMs] (TTL), retroactively trims every still-over-cap session down to
   * [perSessionCap] (so lowering the cap shrinks existing sessions, not just future ones), then
   * enforces the absolute [globalMaxTurns] ceiling across all sessions (so a key-varying client can't
   * grow the DB without bound between TTL passes). Called by the periodic prune worker; all work runs
   * on the worker thread. Best-effort; logs and swallows failures.
   */
  suspend fun prune(context: Context, ttlMs: Long, perSessionCap: Int, globalMaxTurns: Int) {
    runCatching {
      val dao = RelaisDatabase.get(context).sessionDao()
      dao.deleteOlderThan(System.currentTimeMillis() - ttlMs)
      // Retroactive per-session cap: per-session trim is enforced on every record(), but a lowered
      // cap (or sessions written before a cap change) needs this catch-up pass. distinctSessionKeys()
      // is post-TTL so dead sessions are already gone.
      for (key in dao.distinctSessionKeys()) dao.trimToCap(key, perSessionCap)
      // Absolute global ceiling: bounds total rows regardless of how many distinct sessions exist.
      dao.trimToGlobalCap(globalMaxTurns)
    }.onFailure { Log.w(TAG, "prune failed (swallowed)") }
  }

  /** Current stored turn count across all sessions (gauge). 0 on failure. */
  suspend fun totalTurns(context: Context): Int =
    runCatching { RelaisDatabase.get(context).sessionDao().countAll() }.getOrDefault(0)

  /**
   * Non-reversible session key for a client IP: `sha256(ip + ":" + salt)` hex, truncated. The salt is
   * the per-install API key (a stable per-install secret) so keys are unguessable and unlinkable
   * across installs. Returns null for a blank/unknown IP (disables the fallback). NEVER returns the IP.
   */
  fun hashIp(ip: String?, salt: String): String? {
    val clean = ip?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" } ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest("$clean:$salt".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
  }
}
