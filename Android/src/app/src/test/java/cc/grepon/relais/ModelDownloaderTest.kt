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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the redirect bearer-token one-way-drop invariant (#174): the HF token must be sent only
 * while on the ORIGINAL host and must NEVER be re-attached once dropped on a cross-host hop.
 */
class ModelDownloaderTest {

  private val hf = "huggingface.co"

  @Test fun `same original host keeps auth`() {
    assertTrue(ModelDownloader.redirectKeepsAuth(currentlyAuthing = true, nextHost = "huggingface.co", originalHost = hf))
  }

  @Test fun `host match is case-insensitive`() {
    assertTrue(ModelDownloader.redirectKeepsAuth(true, "HuggingFace.co", hf))
  }

  @Test fun `cross host drops auth`() {
    assertFalse(ModelDownloader.redirectKeepsAuth(true, "cdn-lfs.hf.co", hf))
  }

  @Test fun `once dropped, a later same-CDN hop does NOT re-attach the token`() {
    // hf.co -> cdn (dropped) -> cdn/signed (must STAY dropped, even though nextHost == prev hop).
    val afterCrossHost = ModelDownloader.redirectKeepsAuth(true, "cdn.example", hf) // false
    assertFalse(afterCrossHost)
    // The chained call feeds the running flag back in — the bug was comparing to the previous hop.
    assertFalse(ModelDownloader.redirectKeepsAuth(afterCrossHost, "cdn.example", hf))
    assertFalse(ModelDownloader.redirectKeepsAuth(afterCrossHost, "huggingface.co", hf)) // even returning to origin
  }
}
