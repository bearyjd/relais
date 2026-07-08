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

import java.util.Locale

/** The single state-appropriate primary action (AUDIT.md §4.0) — never more than one per screen. */
enum class PrimaryAction { START, CANCEL, STOP }

/** The three top-level home-screen states. THERMAL SHED is a LIVE sub-state, not a fourth value (§4.4). */
enum class NodeStatus { OFFLINE, STARTING, LIVE }

/** The provisioning phase behind STARTING (§4.2), so the phase line is never a bare "starting…". */
enum class ProvisionPhase { IDLE, RESOLVING, DOWNLOADING, LOADING_ENGINE }

/**
 * Pure, JVM-testable snapshot of everything the redesigned control-panel Compose layer renders for
 * one poll tick. The composable stays a thin projection of this — no state derivation in the UI
 * layer (AUDIT.md §4).
 */
data class RelaisControlPanelState(
  val status: NodeStatus,
  val statusWord: String,
  val detailLine: String,
  /**
   * True => the detail line renders Paper (bright = attention by brightness, no new color): LIVE
   * while thermally shedding, or the failed-init message. False => Muted, the quiet default.
   * Derived here, once, so the Compose layer never re-derives this decision (review L5).
   */
  val detailLineBright: Boolean,
  val primaryAction: PrimaryAction,
  val modelRowEnabled: Boolean,
  val modelLockedCaption: String?,
  val showLocalEndpoint: Boolean,
  /** True => the LAN endpoint value renders Paper (the screen's peak, LIVE only); false => Muted preview. */
  val lanEndpointLive: Boolean,
  val showProgressBar: Boolean,
  /** 0f..1f while [showProgressBar]; null otherwise (indeterminate or not downloading). */
  val progressFraction: Float?,
)

/**
 * Assembles [RelaisControlPanelState] from raw engine/provisioner signals. [ready]/[running] mirror
 * [RelaisEngine.isReady] / [RelaisConfig.shouldRun]; [thermalShedding] mirrors
 * [ThermalGovernor.shouldShed]; [phase] and the download byte counts mirror [RelaisNodeProgress];
 * [initFailed] mirrors [RelaisEngine.lastInitFailed] (already consumed by the QS tile).
 *
 * [initFailed] only produces the failed-init message while [running] is still true (review M1):
 * `RelaisNodeService` sets `lastInitFailed=true` on a failed attempt (e.g. a first-run gated-repo
 * 401) but never clears `shouldRun`/`lastInitFailed` itself — only the next init attempt (a fresh
 * START) resets `lastInitFailed`. So once the operator has explicitly STOPped, `running` is false
 * and any stale `initFailed` is ignored — the panel reads a plain, honest "node stopped", not a
 * message about an attempt that's no longer in flight.
 */
fun computeControlPanelState(
  ready: Boolean,
  running: Boolean,
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
  initFailed: Boolean = false,
): RelaisControlPanelState {
  // Still "running" (shouldRun=true) but the engine never came up and won't on its own: an honest
  // failed state, not a perpetual "STARTING · resolving model…" with nothing happening behind it.
  val failed = running && !ready && initFailed
  val status = when {
    ready -> NodeStatus.LIVE
    failed -> NodeStatus.OFFLINE // OFFLINE-rendered on purpose (§ review M1): retry via START, never CANCEL-locked.
    running -> NodeStatus.STARTING
    else -> NodeStatus.OFFLINE
  }
  // Blocked while the node is provisioning (running but not yet ready, and NOT already failed out):
  // a mid-download model change could resurrect a superseded path once the in-flight ensureModel()
  // resolves. A failed attempt is not "provisioning" — the row must stay open so the likely fix
  // (a different model/token) is reachable without a detour.
  val nodeBusy = status == NodeStatus.STARTING
  val progressVisible = status == NodeStatus.STARTING && phase == ProvisionPhase.DOWNLOADING && downloadTotalBytes > 0
  val thermalShed = status == NodeStatus.LIVE && thermalShedding
  return RelaisControlPanelState(
    status = status,
    statusWord = status.name,
    detailLine = controlPanelDetailLine(status, failed, modelDisplayName, thermalShedding, phase, downloadReceivedBytes, downloadTotalBytes),
    detailLineBright = thermalShed || failed,
    primaryAction = when (status) {
      NodeStatus.LIVE -> PrimaryAction.STOP
      NodeStatus.STARTING -> PrimaryAction.CANCEL
      NodeStatus.OFFLINE -> PrimaryAction.START // also the retry action for the failed sub-state
    },
    modelRowEnabled = !nodeBusy,
    modelLockedCaption = if (nodeBusy) "model locked while starting" else null,
    showLocalEndpoint = status == NodeStatus.LIVE,
    lanEndpointLive = status == NodeStatus.LIVE,
    showProgressBar = progressVisible,
    progressFraction = if (progressVisible) downloadProgressFraction(downloadReceivedBytes, downloadTotalBytes) else null,
  )
}

/** The one-line elaboration under the header (Caption tier) — merges old STATUS row + tagline (P8, P12). */
internal fun controlPanelDetailLine(
  status: NodeStatus,
  failed: Boolean,
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
): String =
  when {
    // Checked first: an in-flight (still "running") attempt that already failed renders OFFLINE
    // above, but must never be confused with a plain, intentional stop.
    // The "START again" promise is only honest because RelaisNodeService.onStartCommand
    // re-dispatches the init attempt against an already-alive service — don't drop that dispatch
    // guard without also revisiting this copy.
    status == NodeStatus.OFFLINE && failed -> "start failed · check model/token, then START again"
    // Thermal shed is expressed in-line, in Paper, rather than a new color (§4.4) — the Compose
    // layer is responsible for the color choice; this function only owns the text.
    status == NodeStatus.LIVE && thermalShedding -> "thermal · shedding load"
    status == NodeStatus.LIVE -> "engine resident · $modelDisplayName"
    status == NodeStatus.STARTING -> provisionPhaseLine(phase, downloadReceivedBytes, downloadTotalBytes)
    else -> "node stopped · $modelDisplayName"
  }

/** One of resolve / download(+progress) / engine-load — never a bare "starting…" (P6). */
internal fun provisionPhaseLine(phase: ProvisionPhase, downloadReceivedBytes: Long, downloadTotalBytes: Long): String =
  when (phase) {
    ProvisionPhase.DOWNLOADING ->
      if (downloadTotalBytes > 0) {
        val pct = ((downloadReceivedBytes * 100) / downloadTotalBytes).coerceIn(0, 100)
        "downloading model · $pct% · ${formatGigabytes(downloadReceivedBytes)}/${formatGigabytes(downloadTotalBytes)} GB"
      } else {
        "downloading model…" // total unknown (e.g. HF didn't report a size) — phase name alone, still non-bare.
      }
    ProvisionPhase.LOADING_ENGINE -> "loading engine…"
    ProvisionPhase.RESOLVING, ProvisionPhase.IDLE -> "resolving model…"
  }

internal fun downloadProgressFraction(receivedBytes: Long, totalBytes: Long): Float? {
  if (totalBytes <= 0) return null
  return (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

internal fun formatGigabytes(bytes: Long): String =
  String.format(Locale.US, "%.1f", bytes / 1_000_000_000.0)

/**
 * Masks [key] as `••••…last-4` (Q2) for the control-panel access-key chip: a fixed 4-bullet run, an
 * ellipsis, then the last four characters — never a length-revealing bullet count. Keys of 4
 * characters or fewer are masked fully (no "last 4" would be safe to reveal). Distinct from
 * [maskApiKey] in RelaisDashboard.kt (first4…last4), which serves the read-only web dashboard.
 */
fun maskAccessKey(key: String): String =
  if (key.length <= 4) "•".repeat(key.length) else "••••…${key.takeLast(4)}"

/** The access-key chip's rendered text: full key when [revealed] (SHOW toggle), else [maskAccessKey]. */
fun displayApiKey(key: String, revealed: Boolean): String = if (revealed) key else maskAccessKey(key)
