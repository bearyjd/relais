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

package cc.grepon.relais.nfc

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A parsed NFC workflow tap: a prompt-template id plus an optional inline prompt. */
data class WorkflowTap(val templateId: String, val prompt: String?)

/**
 * Pure parsing + building for the Relais NFC workflow URI scheme: `com.ventouxlabs.relais://workflow/<id>`
 * with an optional `?q=<prompt>` inline prompt. The `<id>` resolves to a #12 prompt template; `q` is
 * an OPTIONAL, attacker-influenced inline prompt (anyone can write a tag) and is therefore treated as
 * untrusted DATA — length-capped here and run through plain inference (no tools, no egress) by the
 * caller. No Android types, so this is unit-testable on the JVM.
 */
object NfcWorkflowParser {
  const val SCHEME = "com.ventouxlabs.relais"
  const val HOST = "workflow"
  const val QUERY_PROMPT = "q"

  /** Template ids are short slugs (mirrors the #12 store's MAX_NAME). */
  const val MAX_ID = 80

  /** Inline prompt is untrusted tag content — bound it so a hostile tag can't flood the engine. */
  const val MAX_PROMPT = 4096

  /**
   * Parse a tag URI into a [WorkflowTap], or null if it is not a well-formed Relais workflow URI
   * (wrong scheme/host, empty/over-long id). The inline prompt is decoded and capped; a blank prompt
   * collapses to null.
   */
  fun parse(raw: String?): WorkflowTap? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
    if (!HOST.equals(uri.host, ignoreCase = true)) return null

    val id =
      uri.path?.trim('/')?.substringBefore('/')?.let { decode(it) }?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    if (id.length > MAX_ID) return null

    val prompt =
      queryParam(uri.rawQuery, QUERY_PROMPT)?.take(MAX_PROMPT)?.trim()?.takeIf { it.isNotEmpty() }
    return WorkflowTap(templateId = id, prompt = prompt)
  }

  /** Build the URI to write onto a tag for [templateId] (+ optional inline [prompt]). */
  fun buildUri(templateId: String, prompt: String? = null): String {
    val base = "$SCHEME://$HOST/${encode(templateId.trim())}"
    val p = prompt?.trim()?.takeIf { it.isNotEmpty() } ?: return base
    return "$base?$QUERY_PROMPT=${encode(p)}"
  }

  private fun queryParam(rawQuery: String?, key: String): String? {
    val q = rawQuery ?: return null
    for (pair in q.split('&')) {
      val eq = pair.indexOf('=')
      if (eq <= 0) continue
      if (decode(pair.substring(0, eq)) == key) return decode(pair.substring(eq + 1))
    }
    return null
  }

  private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

  private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
