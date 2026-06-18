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

package cc.grepon.relais.nodetools

import org.json.JSONObject

/**
 * Extracts node-tool arguments from the model's emitted JSON, tolerating the typed-wrapper shape small
 * models (e.g. Gemma-4 E2B) sometimes emit: `{"expression": {"type":"STRING","value":"2+2"}}` instead
 * of `{"expression":"2+2"}`. Each accessor [unwrap]s a `{type,value}` object to its `value` first.
 */
object NodeToolArgs {

  /** `{type,value}` → its `value`; otherwise the value itself. `JSONObject.NULL` → null. */
  fun unwrap(value: Any?): Any? {
    if (value == null || value == JSONObject.NULL) return null
    if (value is JSONObject && value.has("value")) {
      val inner = value.opt("value")
      return if (inner == JSONObject.NULL) null else inner
    }
    return value
  }

  fun str(args: JSONObject, key: String): String? = unwrap(args.opt(key))?.toString()

  fun double(args: JSONObject, key: String): Double? = when (val u = unwrap(args.opt(key))) {
    is Number -> u.toDouble()
    is String -> u.trim().toDoubleOrNull()
    else -> null
  }

  fun int(args: JSONObject, key: String): Int? = double(args, key)?.toInt()
}
