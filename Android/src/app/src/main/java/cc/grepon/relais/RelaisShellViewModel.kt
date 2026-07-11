/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One polling tick's worth of node-status state, snapshotted together so both derived
 * [RelaisShellViewModel.panelState] and [RelaisShellViewModel.modelDisplay] StateFlows come from
 * the same read rather than racing across two separate polling loops. */
private data class RelaisShellSnapshot(
  val panelState: RelaisControlPanelState,
  val modelDisplay: String,
)

/**
 * Hoists the node-status polling loop that used to live as a `LaunchedEffect` inside
 * `RelaisControlActivity`, so every shell destination that needs to render the control panel
 * (dashboard, and any future entry point) observes one shared, single-poll state source instead of
 * each composable running its own independent 1s timer.
 *
 * The polling loop is a cold `flow` shared via `stateIn(..., SharingStarted.WhileSubscribed(5000))`
 * rather than a `viewModelScope`-tied `while` loop: it only runs while at least one composable is
 * collecting, and pauses ~5–10s after the last collector goes away (e.g. app backgrounded) — the
 * derived flows and the shared `snapshots` flow each carry an independent 5s `WhileSubscribed`
 * grace — resuming immediately on re-subscribe. [panelState] and [modelDisplay] are seeded
 * synchronously with an initial snapshot so the first composed frame isn't empty/default.
 */
class RelaisShellViewModel(app: Application) : AndroidViewModel(app) {
  private val snapshots =
      flow {
            while (true) {
              emit(RelaisShellSnapshot(panelState = snapshotPanelState(), modelDisplay = currentModelDisplay()))
              delay(1000)
            }
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5000),
              RelaisShellSnapshot(panelState = snapshotPanelState(), modelDisplay = currentModelDisplay()),
          )

  val panelState: StateFlow<RelaisControlPanelState> =
      snapshots
          .map { it.panelState }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), snapshots.value.panelState)

  val modelDisplay: StateFlow<String> =
      snapshots
          .map { it.modelDisplay }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), snapshots.value.modelDisplay)

  /** Dispatches the single state-appropriate primary action (START / CANCEL / STOP). */
  fun onPrimaryAction() {
    val ctx = getApplication<Application>()
    when (panelState.value.primaryAction) {
      PrimaryAction.START -> RelaisNodeService.start(ctx)
      PrimaryAction.CANCEL,
      PrimaryAction.STOP -> RelaisNodeService.stop(ctx)
    }
  }

  private fun currentModelDisplay(): String {
    val ctx = getApplication<Application>()
    val modelId = RelaisConfig.modelId(ctx)
    val modelRef = RelaisConfig.modelRef(ctx)
    return modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId
  }

  private fun snapshotPanelState(): RelaisControlPanelState {
    val ctx = getApplication<Application>()
    return computeControlPanelState(
      ready = RelaisEngine.isReady,
      running = RelaisConfig.shouldRun(ctx),
      modelDisplayName = currentModelDisplay(),
      thermalShedding = ThermalGovernor.shouldShed(),
      phase = RelaisNodeProgress.phase,
      downloadReceivedBytes = RelaisNodeProgress.downloadReceivedBytes,
      downloadTotalBytes = RelaisNodeProgress.downloadTotalBytes,
      initFailed = RelaisEngine.lastInitFailed,
    )
  }
}
