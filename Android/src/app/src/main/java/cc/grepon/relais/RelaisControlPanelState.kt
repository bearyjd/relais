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
 * [ThermalGovernor.shouldShed]; [phase] and the download byte counts mirror [RelaisNodeProgress].
 */
fun computeControlPanelState(
  ready: Boolean,
  running: Boolean,
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
): RelaisControlPanelState {
  val status = when {
    ready -> NodeStatus.LIVE
    running -> NodeStatus.STARTING
    else -> NodeStatus.OFFLINE
  }
  // Blocked while the node is provisioning (running but not yet ready): a mid-download model change
  // could resurrect a superseded path once the in-flight ensureModel() resolves — see ModelRow usage.
  val nodeBusy = status == NodeStatus.STARTING
  val progressVisible = status == NodeStatus.STARTING && phase == ProvisionPhase.DOWNLOADING && downloadTotalBytes > 0
  return RelaisControlPanelState(
    status = status,
    statusWord = status.name,
    detailLine = controlPanelDetailLine(status, modelDisplayName, thermalShedding, phase, downloadReceivedBytes, downloadTotalBytes),
    primaryAction = when (status) {
      NodeStatus.LIVE -> PrimaryAction.STOP
      NodeStatus.STARTING -> PrimaryAction.CANCEL
      NodeStatus.OFFLINE -> PrimaryAction.START
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
  modelDisplayName: String,
  thermalShedding: Boolean,
  phase: ProvisionPhase,
  downloadReceivedBytes: Long,
  downloadTotalBytes: Long,
): String =
  when (status) {
    // Thermal shed is expressed in-line, in Paper, rather than a new color (§4.4) — the Compose layer
    // is responsible for the color choice; this function only owns the text.
    NodeStatus.LIVE -> if (thermalShedding) "thermal · shedding load" else "engine resident · $modelDisplayName"
    NodeStatus.STARTING -> provisionPhaseLine(phase, downloadReceivedBytes, downloadTotalBytes)
    NodeStatus.OFFLINE -> "node stopped · $modelDisplayName"
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
