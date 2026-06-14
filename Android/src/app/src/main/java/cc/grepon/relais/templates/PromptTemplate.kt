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

package cc.grepon.relais.templates

import org.json.JSONObject

/** A named system-prompt preset. [builtin] presets ship with the app and cannot be deleted/shadowed. */
data class PromptTemplate(
  val id: String,
  val name: String,
  val system: String,
  val builtin: Boolean = false,
) {
  fun toJson(): JSONObject =
    JSONObject().put("id", id).put("name", name).put("system", system).put("builtin", builtin)

  companion object {
    /** Tolerant parse: a blank/missing id drops the entry (null) so one bad row can't break load. */
    fun fromJson(o: JSONObject): PromptTemplate? {
      val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
      return PromptTemplate(
        id = id,
        name = o.optString("name").ifBlank { id },
        system = o.optString("system"),
        builtin = o.optBoolean("builtin", false),
      )
    }
  }
}
