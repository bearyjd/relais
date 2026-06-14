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

/**
 * Pure session-memory policy (Feature #5): session-key resolution, the precedence rule that keeps
 * client-supplied history authoritative, and the budget-merge that caps stored history to the most
 * recent N turns. No Android types — unit-testable on the JVM ([RelaisSessionPolicyTest]).
 *
 * Privacy (security M6): [resolveSessionKey] NEVER returns a raw IP. The caller passes a *hash* of
 * the client IP (computed in [RelaisSessionStore]); this layer only chooses between the explicit
 * header and that hashed fallback, and sanitizes the header to a bounded, safe charset so a session
 * id can never become an unbounded metric label or smuggle control characters into the store.
 */
object RelaisSessionPolicy {
  /** Max characters of an `X-Relais-Session` header we accept; longer headers are rejected. */
  const val MAX_SESSION_KEY_CHARS = 128

  /** Prefix marking a header-derived key, vs the `ip:` hashed-fallback key. Aids debugging/audit. */
  private const val HEADER_PREFIX = "h:"
  private const val IP_HASH_PREFIX = "ip:"

  /**
   * Resolves the storage key for a request. Precedence:
   *  1. A non-blank `X-Relais-Session` header, trimmed, length-capped to [MAX_SESSION_KEY_CHARS],
   *     and sanitized to `[A-Za-z0-9_.:-]` (other chars dropped). Empty-after-sanitize -> ignored.
   *  2. Else the supplied [clientIpHash] (already a non-reversible hash — never a raw IP).
   *  3. Else null (no key — the request is not associated with stored history).
   *
   * @param header the raw `X-Relais-Session` value, or null/absent.
   * @param clientIpHash a non-reversible hash of the client IP, or null to disable the IP fallback.
   */
  fun resolveSessionKey(header: String?, clientIpHash: String?): String? {
    val sanitized = header?.let { sanitize(it) }
    if (!sanitized.isNullOrEmpty()) return HEADER_PREFIX + sanitized
    val hash = clientIpHash?.trim()?.takeIf { it.isNotEmpty() }
    if (hash != null) return IP_HASH_PREFIX + sanitize(hash).take(MAX_SESSION_KEY_CHARS)
    return null
  }

  /**
   * Whether stored server-side history should be injected for this request. True ONLY for a "bare"
   * turn — a request whose client `messages[]` carried no prior history (a single user message).
   * A client that manages its own multi-turn history is authoritative; injecting stored turns then
   * would double-inject context (Devil's Advocate #1, the headline BLOCKER).
   */
  fun shouldUseStoredHistory(clientHistoryTurns: Int): Boolean = clientHistoryTurns <= 0

  /**
   * Caps [stored] to the most recent [budgetTurns] turns (tail), preserving order (oldest first).
   * A non-positive budget yields an empty list. Pure list operation; [stored] is not mutated.
   */
  fun mergeHistory(stored: List<ParsedTurn>, budgetTurns: Int): List<ParsedTurn> {
    if (budgetTurns <= 0 || stored.isEmpty()) return emptyList()
    return if (stored.size <= budgetTurns) stored else stored.takeLast(budgetTurns)
  }

  /**
   * Trims to a bounded, safe charset (`[A-Za-z0-9_.:-]`) and caps length. Keeps session ids free of
   * whitespace, control chars, and metric-label-breaking characters (security M6). Does NOT add a
   * prefix — callers do.
   */
  private fun sanitize(raw: String): String =
    raw.trim().take(MAX_SESSION_KEY_CHARS).filter { it.isLetterOrDigit() || it in SAFE_PUNCT }

  private val SAFE_PUNCT = setOf('_', '.', ':', '-')
}
