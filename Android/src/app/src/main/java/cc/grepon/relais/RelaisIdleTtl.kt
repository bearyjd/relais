/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

/**
 * Idle-TTL auto-unload policy (#178): release the resident engine after a period with no request,
 * so a phone doesn't hold a multi-GB model (RAM + thermal budget) resident indefinitely once loaded.
 * Mirrors Ollama's `OLLAMA_KEEP_ALIVE` / LM Studio's JIT-TTL idea, adapted to this node's
 * single-resident-engine model: the next request reloads lazily ([RelaisEngine.ensureInitialized]
 * already does this), so a short cold-start is an acceptable trade for hours of idle drain avoided.
 *
 * `0` (or negative) TTL means "disabled — never auto-unload", matching the `0 = never` convention
 * already used elsewhere in this codebase (see [RelaisConfig] session-memory-style toggles).
 *
 * Pure JVM (no Android types, no Context, no [RelaisEngine]) so the decision is unit-testable in
 * isolation — mirrors [admit] / [decideBackpressure] in RelaisAdmission.kt. The actual concurrency
 * safety (never unloading mid-inference, never racing a fresh request against an in-progress
 * unload) is NOT expressed here — it lives in [RelaisEngine.releaseIfIdle], which re-evaluates this
 * function while holding the same lock every request dispatch acquires before touching the engine.
 * See the KDoc on [RelaisEngine.releaseIfIdle] and [RelaisEngine.generate] for that argument; it
 * can't be captured as a pure-JVM test because it depends on the real (native, AAR-provided)
 * `Engine`/`Conversation` types, which aren't fakeable in a hermetic JVM test — it needs an
 * on-device/instrumented probe instead.
 */

/** Sentinel meaning "idle-TTL auto-unload is disabled" — the engine is never released for idleness. */
const val IDLE_TTL_DISABLED_MINUTES = 0

/** Default idle window before the resident engine is released. Judgment call — see #178 discussion. */
const val IDLE_TTL_DEFAULT_MINUTES = 15

/** Floor for the configured TTL (anything below, other than the disabled sentinel, clamps up to this). */
const val IDLE_TTL_MIN_MINUTES = 1

/** Ceiling for the configured TTL — a 24h cap keeps a mis-set value from meaning "effectively never". */
const val IDLE_TTL_MAX_MINUTES = 24 * 60

/**
 * Circuit-breaker threshold for [RelaisEngine.consecutiveCloseFailures]: after this many consecutive
 * [com.google.ai.edge.litertlm.Engine.close] failures, idle-TTL stops attempting further auto-unloads
 * (leaving the engine resident) rather than risk repeating a possibly-leaking native teardown forever
 * on a fully-automatic, 60s-polled path (#178 review — a rare `close()` bug that was tolerable at
 * shutdown()'s pre-#178 call frequency — process teardown, explicit model switch — becomes a repeated
 * leak once shutdown() is on a recurring idle-triggered cadence).
 */
const val IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES = 3

/**
 * Pure idle-unload decision: should the resident engine be released *right now*?
 *
 * @param ready true iff a resident engine is actually loaded ([RelaisEngine.isReady]); nothing to
 *   release if not.
 * @param inFlightDepth current in-flight request count ([RelaisMetrics.queueDepth]) — the
 *   highest-risk guard: any request queued or running (even one still being admitted, since
 *   `incInFlight()` happens before a request touches the engine) blocks the release outright.
 * @param lastActivityAtMs wall-clock ms of the last time the engine became ready OR finished serving
 *   a request ([RelaisEngine]'s activity clock).
 * @param nowMs current wall-clock ms.
 * @param ttlMs configured idle window in ms; `<= 0` means disabled (never unload).
 * @param consecutiveCloseFailures [RelaisEngine.consecutiveCloseFailures] — at or past
 *   [IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES], auto-unload stops trying (circuit breaker).
 */
fun shouldUnloadIdleEngine(
  ready: Boolean,
  inFlightDepth: Int,
  lastActivityAtMs: Long,
  nowMs: Long,
  ttlMs: Long,
  consecutiveCloseFailures: Int = 0,
): Boolean {
  if (!ready) return false // nothing resident to release
  if (ttlMs <= 0L) return false // disabled (0 = never), matches the codebase's "0 = never" convention
  if (inFlightDepth > 0) return false // NEVER unload mid-inference — the highest-risk invariant (#178)
  if (consecutiveCloseFailures >= IDLE_TTL_MAX_CONSECUTIVE_CLOSE_FAILURES) return false // circuit breaker
  return nowMs - lastActivityAtMs >= ttlMs
}
