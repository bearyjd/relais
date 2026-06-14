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

import cc.grepon.relais.core.NodeState
import cc.grepon.relais.core.computeNodeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM truth table for [computeNodeState] — the unified node state the QS tile/widget consume. */
class NodeStateTest {

  private fun state(
    shouldRun: Boolean = false,
    ready: Boolean = false,
    startupInProgress: Boolean = false,
    lastInitFailed: Boolean = false,
    thermalStatus: Int = 0,
  ) = computeNodeState(shouldRun, ready, startupInProgress, lastInitFailed, thermalStatus)

  @Test fun `off when nothing is running`() {
    assertEquals(NodeState.OFF, state())
  }

  @Test fun `starting when shouldRun but not ready`() {
    assertEquals(NodeState.STARTING, state(shouldRun = true))
  }

  @Test fun `starting when startupInProgress even if shouldRun is false`() {
    assertEquals(NodeState.STARTING, state(startupInProgress = true))
  }

  @Test fun `live when engine ready`() {
    assertEquals(NodeState.LIVE, state(shouldRun = true, ready = true))
  }

  @Test fun `hot when ready and thermal severe`() {
    // PowerManager.THERMAL_STATUS_SEVERE == 3.
    assertEquals(NodeState.HOT, state(shouldRun = true, ready = true, thermalStatus = 3))
  }

  @Test fun `hot takes precedence over live at critical`() {
    assertEquals(NodeState.HOT, state(shouldRun = true, ready = true, thermalStatus = 6))
  }

  @Test fun `error when shouldRun and last init failed and not retrying`() {
    assertEquals(NodeState.ERROR, state(shouldRun = true, lastInitFailed = true))
  }

  @Test fun `ready beats a stale init-failed flag`() {
    assertEquals(NodeState.LIVE, state(shouldRun = true, ready = true, lastInitFailed = true))
  }

  @Test fun `stopped node never shows error even with a stale init-failed flag`() {
    // ERROR requires shouldRun; a stopped node reads OFF regardless of a leftover lastInitFailed.
    assertEquals(NodeState.OFF, state(shouldRun = false, lastInitFailed = true))
  }

  @Test fun `active retry shows starting not error`() {
    // A retry in progress (startupInProgress) after a prior failure should read STARTING, not ERROR.
    assertEquals(NodeState.STARTING, state(shouldRun = true, startupInProgress = true, lastInitFailed = true))
  }
}
