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
import android.content.Intent
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity

/**
 * Full flavor: the third-party-licenses viewer backed by `play-services-oss-licenses`
 * ([OssLicensesMenuActivity]). The Settings screen shows the "Third-party libraries" row only when
 * [available]. The de-Googled flavor provides a GMS-free stub that reports unavailable.
 */
object OssLicenses {
  /** A third-party-licenses viewer is available in the `full` (GMS) flavor. */
  val available: Boolean = true

  /** Launches the Play-Services OSS-licenses menu. */
  fun open(context: Context) {
    context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
  }
}
