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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the MODELS-screen download state/copy (#217). */
class ModelDownloadTest {

  // ---- in-flight guard ----

  @Test fun `only preparing and downloading count as in flight`() {
    assertTrue(ModelDownloadState.Preparing.isInFlight())
    assertTrue(ModelDownloadState.Downloading(0).isInFlight())
    assertFalse(ModelDownloadState.Idle.isInFlight())
    assertFalse(ModelDownloadState.Ready("m").isInFlight())
    assertFalse(ModelDownloadState.Failed("boom").isInFlight())
  }

  // ---- status line ----

  @Test fun `idle says nothing`() {
    assertNull(modelDownloadLine(ModelDownloadState.Idle))
  }

  @Test fun `each active state has copy`() {
    assertEquals("preparing download…", modelDownloadLine(ModelDownloadState.Preparing))
    assertEquals("downloading · 42%", modelDownloadLine(ModelDownloadState.Downloading(42)))
    assertEquals("model ready on device", modelDownloadLine(ModelDownloadState.Ready("m")))
    assertEquals("download failed · disk on fire", modelDownloadLine(ModelDownloadState.Failed("disk on fire")))
  }

  @Test fun `a ready model still reports, so re-tapping DOWNLOAD is never silent`() {
    // Silence on "already on disk" reads as another dead button — the exact bug this screen had.
    assertEquals("model ready on device", modelDownloadLine(ModelDownloadState.Ready("gemma")))
  }

  @Test fun `percent is clamped rather than rendered nonsensically`() {
    assertEquals("downloading · 0%", modelDownloadLine(ModelDownloadState.Downloading(-5)))
    assertEquals("downloading · 100%", modelDownloadLine(ModelDownloadState.Downloading(140)))
  }

  // ---- actionable hints ----

  @Test fun `only failures produce hints`() {
    assertNull(modelDownloadHint(ModelDownloadState.Idle))
    assertNull(modelDownloadHint(ModelDownloadState.Preparing))
    assertNull(modelDownloadHint(ModelDownloadState.Downloading(10)))
    assertNull(modelDownloadHint(ModelDownloadState.Ready("m")))
  }

  @Test fun `a gated-repo 401 points at the license and the token`() {
    // The dominant real failure: google/gemma-3n-* 401s without an HF token whose account accepted
    // the Gemma license. "HTTP 401" alone tells an operator nothing about the fix.
    val hint = modelDownloadHint(ModelDownloadState.Failed("HTTP 401 Unauthorized"))
    assertTrue(hint, hint!!.contains("license-gated"))
    assertTrue(hint, hint.contains("CONFIGURE"))
  }

  @Test fun `a 403 gets the same license hint`() {
    assertTrue(modelDownloadHint(ModelDownloadState.Failed("403 Forbidden"))!!.contains("license-gated"))
  }

  @Test fun `an unreachable catalog points at the network`() {
    val hint = modelDownloadHint(ModelDownloadState.Failed("Could not fetch model allowlist (offline?)"))
    assertTrue(hint, hint!!.contains("network"))
  }

  @Test fun `a full disk says so`() {
    assertTrue(modelDownloadHint(ModelDownloadState.Failed("ENOSPC: no space left"))!!.contains("storage"))
  }

  @Test fun `an unrecognised failure gets no invented advice`() {
    // Better to show the raw provisioner message than to guess wrong about the cause.
    assertNull(modelDownloadHint(ModelDownloadState.Failed("something unexpected")))
  }

  @Test fun `hint matching is case-insensitive`() {
    assertTrue(modelDownloadHint(ModelDownloadState.Failed("unauthorized"))!!.contains("license-gated"))
    assertTrue(modelDownloadHint(ModelDownloadState.Failed("UNAUTHORIZED"))!!.contains("license-gated"))
  }
}
