/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.grepon.relais

/** Route keys for the unified shell NavHost destinations. */
object RelaisRoutes {
  const val DASHBOARD = "dashboard"
  const val CHAT = "chat"
  const val MODELS = "models"
  const val CONFIGURE = "configure"
  const val BENCHMARK = "benchmark"
}

/** A top-level destination shown in the DESIGN.md bottom nav. */
data class BottomNavItem(val route: String, val label: String)

/** The three top-level destinations, in display order. */
val RELAIS_BOTTOM_NAV: List<BottomNavItem> =
    listOf(
        BottomNavItem(RelaisRoutes.DASHBOARD, "DASHBOARD"),
        BottomNavItem(RelaisRoutes.CHAT, "CHAT"),
        BottomNavItem(RelaisRoutes.MODELS, "MODELS"),
    )

/** The shell always opens on the node dashboard. */
fun startDestinationRoute(): String = RelaisRoutes.DASHBOARD

/**
 * Map a `com.ventouxlabs.relais` deep link (its scheme + host, already parsed from
 * `Intent.data` by the caller) to a shell route, else `null`.
 *
 * Kept as a pure `String?` function (not `android.net.Uri`) so it stays a device-free JVM
 * unit test rather than requiring Robolectric.
 *  - host `global_model_manager` -> MODELS
 *  - any other host under the relais scheme -> CHAT
 *  - null scheme, or a non-relais scheme -> null
 */
fun resolveShellDeepLink(scheme: String?, host: String?): String? {
  if (scheme != "com.ventouxlabs.relais") return null
  return when (host) {
    "global_model_manager" -> RelaisRoutes.MODELS
    else -> RelaisRoutes.CHAT
  }
}
