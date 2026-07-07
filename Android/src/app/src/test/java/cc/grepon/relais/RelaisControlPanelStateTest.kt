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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the redesigned control-panel's pure state/formatting functions (no
 * Context, no Android, no Compose) — see AUDIT.md §4 for the spec these implement.
 */
class RelaisControlPanelStateTest {

  // ---------------------------------------------------------------------------
  // computeControlPanelState — top-level status + single primary action
  // ---------------------------------------------------------------------------

  @Test
  fun `ready true yields LIVE status and STOP primary action`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals(NodeStatus.LIVE, s.status)
    assertEquals("LIVE", s.statusWord)
    assertEquals(PrimaryAction.STOP, s.primaryAction)
  }

  @Test
  fun `running true and ready false yields STARTING status and CANCEL primary action`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals(NodeStatus.STARTING, s.status)
    assertEquals("STARTING", s.statusWord)
    assertEquals(PrimaryAction.CANCEL, s.primaryAction)
  }

  @Test
  fun `running false and ready false yields OFFLINE status and START primary action`() {
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals(NodeStatus.OFFLINE, s.status)
    assertEquals("OFFLINE", s.statusWord)
    assertEquals(PrimaryAction.START, s.primaryAction)
  }

  @Test
  fun `never more than one primary action across all three states`() {
    val actions = listOf(
      computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0).primaryAction,
      computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0).primaryAction,
      computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0).primaryAction,
    )
    assertEquals(setOf(PrimaryAction.STOP, PrimaryAction.CANCEL, PrimaryAction.START), actions.toSet())
  }

  // ---------------------------------------------------------------------------
  // Detail line — P8 (single status line) + P6 (phase line) + P7 (thermal sub-state)
  // ---------------------------------------------------------------------------

  @Test
  fun `LIVE detail line reads engine resident with model name when not shedding`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals("engine resident · Gemma 4 E2B", s.detailLine)
  }

  @Test
  fun `LIVE detail line reads thermal shedding load when thermalShedding is true`() {
    val s = computeControlPanelState(
      ready = true, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = true, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals("thermal · shedding load", s.detailLine)
    // Still LIVE — thermal shed is a sub-state, not a fourth top-level status (§4.4).
    assertEquals(NodeStatus.LIVE, s.status)
    assertEquals("LIVE", s.statusWord)
  }

  @Test
  fun `OFFLINE detail line reads node stopped with model name`() {
    val s = computeControlPanelState(
      ready = false, running = false, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.IDLE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals("node stopped · Gemma 4 E2B", s.detailLine)
  }

  @Test
  fun `STARTING detail line during RESOLVING phase reads resolving model`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.RESOLVING,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals("resolving model…", s.detailLine)
  }

  @Test
  fun `STARTING detail line during LOADING_ENGINE phase reads loading engine`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.LOADING_ENGINE,
      downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
    )
    assertEquals("loading engine…", s.detailLine)
  }

  @Test
  fun `STARTING detail line during DOWNLOADING with known total shows percent and GB fraction`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.DOWNLOADING,
      downloadReceivedBytes = 1_200_000_000L, downloadTotalBytes = 2_800_000_000L,
    )
    assertEquals("downloading model · 42% · 1.2/2.8 GB", s.detailLine)
  }

  @Test
  fun `STARTING detail line during DOWNLOADING with unknown total omits percent and GB`() {
    val s = computeControlPanelState(
      ready = false, running = true, modelDisplayName = "Gemma 4 E2B",
      thermalShedding = false, phase = ProvisionPhase.DOWNLOADING,
      downloadReceivedBytes = 500_000_000L, downloadTotalBytes = 0L,
    )
    assertEquals("downloading model…", s.detailLine)
  }

  @Test
  fun `STARTING detail line is never a bare starting ellipsis regardless of phase`() {
    ProvisionPhase.entries.forEach { phase ->
      val s = computeControlPanelState(
        ready = false, running = true, modelDisplayName = "m",
        thermalShedding = false, phase = phase,
        downloadReceivedBytes = 0L, downloadTotalBytes = 0L,
      )
      assertFalse("phase=$phase must not render bare starting text", s.detailLine == "starting…")
      assertTrue("phase=$phase detail line must be non-blank", s.detailLine.isNotBlank())
    }
  }

  // ---------------------------------------------------------------------------
  // Model-row lockout (P6) — nodeBusy = running && !ready
  // ---------------------------------------------------------------------------

  @Test
  fun `model row is enabled and uncaptioned when OFFLINE`() {
    val s = computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0)
    assertTrue(s.modelRowEnabled)
    assertNull(s.modelLockedCaption)
  }

  @Test
  fun `model row is enabled and uncaptioned when LIVE`() {
    val s = computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0)
    assertTrue(s.modelRowEnabled)
    assertNull(s.modelLockedCaption)
  }

  @Test
  fun `model row is disabled with explanation caption when STARTING`() {
    val s = computeControlPanelState(false, true, "m", false, ProvisionPhase.RESOLVING, 0, 0)
    assertFalse(s.modelRowEnabled)
    assertEquals("model locked while starting", s.modelLockedCaption)
  }

  // ---------------------------------------------------------------------------
  // Endpoint row visibility (§4.1-4.3) — LOCAL only LIVE; LAN Paper only LIVE
  // ---------------------------------------------------------------------------

  @Test
  fun `LOCAL endpoint row is hidden unless LIVE`() {
    assertFalse(computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0).showLocalEndpoint)
    assertFalse(computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0).showLocalEndpoint)
    assertTrue(computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0).showLocalEndpoint)
  }

  @Test
  fun `LAN endpoint renders Paper only when LIVE, Muted otherwise`() {
    assertFalse(computeControlPanelState(false, false, "m", false, ProvisionPhase.IDLE, 0, 0).lanEndpointLive)
    assertFalse(computeControlPanelState(false, true, "m", false, ProvisionPhase.IDLE, 0, 0).lanEndpointLive)
    assertTrue(computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0, 0).lanEndpointLive)
  }

  // ---------------------------------------------------------------------------
  // Determinate download progress bar (§5) — DOWNLOADING phase + known total only
  // ---------------------------------------------------------------------------

  @Test
  fun `progress bar is visible only during DOWNLOADING with a known total`() {
    val downloading = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 1_000_000_000L, 2_000_000_000L,
    )
    assertTrue(downloading.showProgressBar)
    assertEquals(0.5f, requireNotNull(downloading.progressFraction), 0.0001f)

    val unknownTotal = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 1_000_000_000L, 0L,
    )
    assertFalse(unknownTotal.showProgressBar)
    assertNull(unknownTotal.progressFraction)

    val resolving = computeControlPanelState(false, true, "m", false, ProvisionPhase.RESOLVING, 0L, 0L)
    assertFalse(resolving.showProgressBar)

    val live = computeControlPanelState(true, true, "m", false, ProvisionPhase.IDLE, 0L, 0L)
    assertFalse(live.showProgressBar)
  }

  @Test
  fun `progress fraction is coerced into 0 to 1`() {
    val overReceived = computeControlPanelState(
      false, true, "m", false, ProvisionPhase.DOWNLOADING, 5_000_000_000L, 2_000_000_000L,
    )
    assertEquals(1f, requireNotNull(overReceived.progressFraction), 0.0001f)
  }

  // ---------------------------------------------------------------------------
  // formatGigabytes / provisionPhaseLine — direct unit coverage of the helpers
  // ---------------------------------------------------------------------------

  @Test
  fun `formatGigabytes renders one decimal place`() {
    assertEquals("1.2", formatGigabytes(1_200_000_000L))
    assertEquals("2.8", formatGigabytes(2_800_000_000L))
    assertEquals("0.0", formatGigabytes(0L))
  }

  @Test
  fun `provisionPhaseLine covers every phase explicitly`() {
    assertEquals("resolving model…", provisionPhaseLine(ProvisionPhase.IDLE, 0, 0))
    assertEquals("resolving model…", provisionPhaseLine(ProvisionPhase.RESOLVING, 0, 0))
    assertEquals("loading engine…", provisionPhaseLine(ProvisionPhase.LOADING_ENGINE, 0, 0))
    assertEquals("downloading model…", provisionPhaseLine(ProvisionPhase.DOWNLOADING, 10, 0))
    assertEquals(
      "downloading model · 50% · 1.0/2.0 GB",
      provisionPhaseLine(ProvisionPhase.DOWNLOADING, 1_000_000_000L, 2_000_000_000L),
    )
  }

  // ---------------------------------------------------------------------------
  // Access-key masking (Q2) — masked by default, SHOW toggle reveals full key
  // ---------------------------------------------------------------------------

  @Test
  fun `maskAccessKey shows a bullet run then ellipsis then the last four characters`() {
    val masked = maskAccessKey("deadbeefcafef00d1234567890abcdef")
    assertEquals("••••…cdef", masked)
  }

  @Test
  fun `maskAccessKey fully masks a key of four characters or fewer`() {
    assertEquals("••••", maskAccessKey("abcd"))
    assertEquals("•••", maskAccessKey("abc"))
    assertEquals("", maskAccessKey(""))
  }

  @Test
  fun `displayApiKey reveals the full key only when revealed is true`() {
    val key = "deadbeefcafef00d1234567890abcdef"
    assertEquals(key, displayApiKey(key, revealed = true))
    assertEquals(maskAccessKey(key), displayApiKey(key, revealed = false))
  }
}
