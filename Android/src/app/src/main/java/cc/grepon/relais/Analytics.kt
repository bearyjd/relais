/*
 * Copyright 2025 Google LLC
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

import android.os.Bundle

/**
 * No-op analytics. Relais is a private, cloud-free appliance and ships **no telemetry** — Firebase
 * Analytics was removed. This is kept as a typed no-op so the existing `firebaseAnalytics?.logEvent`
 * call sites compile unchanged: [firebaseAnalytics] is always null, so every logEvent is a no-op
 * (the body below never runs and exists only to type-check the calls).
 */
object NoopAnalytics {
  fun logEvent(name: String, params: Bundle?) {
    // Intentionally does nothing — Relais collects and transmits no analytics.
  }
}

/** Always null: analytics is disabled. The `?.` at every call site makes logEvent a no-op. */
val firebaseAnalytics: NoopAnalytics? = null

enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT(id = "capability_select"),
  MODEL_DOWNLOAD(id = "model_download"),
  GENERATE_ACTION(id = "generate_action"),
  BUTTON_CLICKED(id = "button_clicked"),
  SKILL_MANAGEMENT(id = "skill_management"),
  SKILL_EXECUTION(id = "skill_execution"),
  CHAT_HISTORY(id = "chat_history"),
  MCP_MANAGEMENT(id = "mcp_management"),
  MCP_EXECUTION(id = "mcp_execution"),
}
