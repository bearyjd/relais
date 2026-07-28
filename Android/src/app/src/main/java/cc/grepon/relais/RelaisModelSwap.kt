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
 * SUPERSEDED BY THE FULL FEATURE: the narrow "only the configured model" guard above was the first
 * cut's whole safety boundary. It is now carried by [RelaisModelRegistry] instead — a swap target
 * must be a model already provisioned ON THIS DEVICE, so a client still cannot originate a
 * download, but it can name any model the operator actually has. See [resolveModelRequest].
 */
/**
 * What to do with a request's `model` field (#180, full feature).
 *
 * Replaces the first cut's boolean because the answer is genuinely three-way: serve, swap, or
 * refuse. A boolean could not express "the client named a model this node does not have", so an
 * unknown id silently fell through and was answered by whatever happened to be resident — the
 * drop-in-fidelity gap this issue exists to close.
 */
sealed interface ModelRequestOutcome {
  /** Serve the resident model: no `model` field, not ready yet, or it already matches. */
  data object ServeResident : ModelRequestOutcome

  /** [targetModelId] is provisioned locally — kick a single-slot swap and have the client retry. */
  data class SwapThenRetry(val targetModelId: String) : ModelRequestOutcome

  /** The client named a model that is not on this device. Answer 404 `model_not_found`. */
  data class NotProvisioned(val requestedModelId: String) : ModelRequestOutcome
}

/**
 * Decide what a request's `model` field means for this node.
 *
 * [requestedModelId] MUST be the RAW field — `null` when the client omitted it. Do **not** pass
 * `RelaisHttpServer.DEFAULT_MODEL` in its place: that cosmetic alias matches no real id, so
 * substituting it would turn every omitted-`model` request into a 404. (The first cut tolerated the
 * substitution because an unmatched id merely meant "don't swap"; under [NotProvisioned] the same
 * value becomes a hard client-visible error. This is the one behavioural landmine in the change.)
 *
 * [provisionedModelIds] comes from [RelaisModelRegistry] — models actually on disk. Membership is
 * what makes a swap legal, and it is why an arbitrary client string still cannot trigger a
 * download: the registry only grows on a locally-successful provision. [configuredModelId] stays
 * swap-eligible on its own so the operator's current selection works before it has been recorded.
 *
 * Pure JVM (no Context, no Engine) so the whole matrix is unit-tested in isolation — mirrors
 * [shouldUnloadIdleEngine] in RelaisIdleTtl.kt.
 */
fun resolveModelRequest(
  residentModelId: String?,
  requestedModelId: String?,
  configuredModelId: String,
  provisionedModelIds: Set<String>,
  isReady: Boolean,
): ModelRequestOutcome {
  // Not ready: the normal not-ready path (503) owns this. Refusing here would answer 404 for a
  // model the node may well have, purely because it hasn't finished coming up.
  if (!isReady) return ModelRequestOutcome.ServeResident
  val requested = requestedModelId?.takeIf { it.isNotBlank() } ?: return ModelRequestOutcome.ServeResident
  if (requested == residentModelId) return ModelRequestOutcome.ServeResident
  if (requested == configuredModelId || requested in provisionedModelIds) {
    return ModelRequestOutcome.SwapThenRetry(requested)
  }
  return ModelRequestOutcome.NotProvisioned(requested)
}
