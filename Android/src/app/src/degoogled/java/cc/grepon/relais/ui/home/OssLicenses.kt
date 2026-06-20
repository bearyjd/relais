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

package cc.grepon.relais.ui.home

import android.content.Context

/**
 * De-Googled flavor: the GMS-backed OSS-licenses viewer (`play-services-oss-licenses`) is excluded,
 * so the Settings screen hides the "Third-party libraries" row ([available] is false). A self-hosted
 * FOSS license screen is a planned follow-up. The real impl lives in `src/full/`.
 */
object OssLicenses {
  /** No third-party-licenses viewer in the de-Googled flavor. */
  val available: Boolean = false

  /** Unreachable (the Settings row is hidden when [available] is false). */
  fun open(@Suppress("UNUSED_PARAMETER") context: Context) {
    // no-op: no licenses viewer in the de-Googled flavor.
  }
}
