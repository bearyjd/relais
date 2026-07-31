/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the GNU Free
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upstream catalog revision this build reads is pinned, NOT derived from our `versionName` (#227).
 *
 * Why this test exists: `allowlistUrl()` used to interpolate `BuildConfig.VERSION_NAME`, and upstream
 * `google-ai-edge/gallery/model_allowlists/` stops at `1_0_15.json`. Bumping our version therefore
 * pointed the node at a URL that 404s — and because [RelaisModelCatalog] swallows fetch failures by
 * design, the symptom was a permanently empty MODELS screen with no crash and no log line. That made
 * a routine version bump into a silent, shipping product break.
 */
class RelaisAllowlistUrlTest {

  @Test
  fun urlIsPinnedToTheRevisionUpstreamActuallyPublishes() {
    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main" +
        "/model_allowlists/1_0_15.json",
      RelaisModelProvisioner.allowlistUrl(),
    )
  }

  /**
   * The regression guard proper. A future `versionName = "1.0.16"` (or 2.0.0, or anything) must NOT
   * change this URL — upstream has no such file, and the failure is invisible at runtime.
   */
  @Test
  fun urlDoesNotTrackOurOwnVersionName() {
    val url = RelaisModelProvisioner.allowlistUrl()
    val versionSlug = BuildConfig.VERSION_NAME.replace(".", "_")

    assertEquals(
      "the pinned upstream revision must not be derived from versionName (#227)",
      "1_0_15",
      RelaisModelProvisioner.ALLOWLIST_REVISION,
    )
    // Only meaningful once the two diverge; asserted so the guard keeps working after a bump.
    if (versionSlug != "1_0_15") {
      assertFalse(
        "allowlistUrl() started tracking versionName again — upstream has no ${versionSlug}.json",
        url.contains(versionSlug),
      )
    }
  }

  @Test
  fun urlPointsAtTheUpstreamAllowlistDirectory() {
    val url = RelaisModelProvisioner.allowlistUrl()
    assertTrue(url.startsWith("https://"))
    assertTrue(url.contains("google-ai-edge/gallery"))
    assertTrue(url.endsWith(".json"))
  }
}
