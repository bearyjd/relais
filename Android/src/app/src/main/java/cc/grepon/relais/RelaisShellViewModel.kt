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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Hoists the node-status polling loop that used to live as a `LaunchedEffect` inside
 * `RelaisControlActivity`, so every shell destination that needs to render the control panel
 * (dashboard, and any future entry point) observes one shared, single-poll state source instead of
 * each composable running its own independent 1s timer.
 *
 * [panelState] and [modelDisplay] are seeded synchronously in `init` (before the polling coroutine's
 * first tick) so the first composed frame isn't empty/default.
 */
class RelaisShellViewModel(app: Application) : AndroidViewModel(app) {
  private val _panelState = MutableStateFlow(snapshotPanelState())
  val panelState: StateFlow<RelaisControlPanelState> = _panelState.asStateFlow()

  private val _modelDisplay = MutableStateFlow(currentModelDisplay())
  val modelDisplay: StateFlow<String> = _modelDisplay.asStateFlow()

  init {
    viewModelScope.launch {
      while (isActive) {
        _modelDisplay.value = currentModelDisplay()
        _panelState.value = snapshotPanelState()
        delay(1000)
      }
    }
  }

  /** Dispatches the single state-appropriate primary action (START / CANCEL / STOP). */
  fun onPrimaryAction() {
    val ctx = getApplication<Application>()
    when (_panelState.value.primaryAction) {
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
