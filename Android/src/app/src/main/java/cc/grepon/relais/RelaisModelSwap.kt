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
 * Single-slot swap-on-mismatch decision (#180, first cut): should the resident engine be closed and
 * reloaded to serve a DIFFERENT model than the one currently resident?
 *
 * Scope is deliberately narrow. This node has exactly one resident engine slot (no LRU, no
 * multi-model cache — see [RelaisEngine]'s KDoc), so a "swap" is always a strict close-then-load,
 * never a hitless load-before-close (no evidence litertlm supports two simultaneously-resident
 * `Engine` objects, and phone RAM is the whole reason this feature exists). The narrow-scope guard
 * below — requiring the requested id to match [configuredModelId], not merely to differ from
 * [residentModelId] — is the actual safety boundary of this feature:
 *
 * An HTTP client's `model` field is arbitrary, untrusted input. If [shouldSwapModel] triggered a
 * swap purely on "requested != resident", any client on the LAN could force the node to download
 * and load an arbitrary model it names, entirely unattended — new attack surface (disk fill,
 * bandwidth, a multi-GB unattended fetch) with no operator involvement. Gating the swap to "AND it
 * matches what the operator already staged via the app's Models UI ([ModelSwitch.applyRef] /
 * [ModelSwitch.applyManualId], which only persist [RelaisConfig] — they do NOT themselves force a
 * reload, which is the actual gap this issue closes)" means a request can only ever complete a swap
 * the operator initiated locally, never originate one. This is intentionally the whole feature for
 * this first cut — see the issue's own scope note before broadening it.
 *
 * Pure JVM (no Context, no Engine, no [RelaisEngine]) so the decision is unit-testable in isolation —
 * mirrors [shouldUnloadIdleEngine] in RelaisIdleTtl.kt. The actual concurrency/lock-ordering safety
 * (never swapping mid-inference, watchdog not mistaking the swap's not-ready window for a crash) is
 * NOT expressed here — it lives in [RelaisEngine.ensureModelSwapInBackground], which reuses
 * [RelaisEngine.startupInProgress] (the same "still coming up, not dead" signal every existing
 * not-ready window already relies on — see that function's KDoc and [RelaisWatchdogReceiver]).
 *
 * @param residentModelId [RelaisEngine.residentModelId] — the id actually loaded right now, or null
 *   before any successful init.
 * @param requestedModelId the inbound request's `model` field, or null/blank if the client omitted it.
 * @param configuredModelId [RelaisConfig.modelId] — the operator's currently-staged selection.
 * @param isReady [RelaisEngine.isReady] — nothing to swap away from if no engine is resident yet;
 *   let the normal not-ready path ([RelaisEngine.ensureInitializedInBackground] / lazy
 *   [RelaisEngine.ensureInitialized] on the next request) handle that case instead.
 *
 * INVARIANT this function silently depends on: `RelaisHttpServer.DEFAULT_MODEL` (substituted in for
 * [requestedModelId] when a request omits `model` entirely) must never be normalized to equal a real
 * [configuredModelId]/[residentModelId] — today it's a short cosmetic alias (`"gemma-4-e4b-it"`) that
 * can never match a full HF-style id, so an omitted-`model` request correctly never swaps, but that's
 * accidental unless this stays true. See the comment at `DEFAULT_MODEL`'s definition.
 */
fun shouldSwapModel(
  residentModelId: String?,
  requestedModelId: String?,
  configuredModelId: String,
  isReady: Boolean,
): Boolean {
  if (!isReady) return false // nothing resident to swap away from
  if (requestedModelId.isNullOrBlank()) return false // no explicit ask -> serve whatever is resident
  if (requestedModelId == residentModelId) return false // already serving the requested model
  // Narrow-scope guard: only swap TO the operator's currently-configured selection, never to an
  // arbitrary client-supplied id that isn't what's staged — see this file's KDoc for why.
  return requestedModelId == configuredModelId
}
