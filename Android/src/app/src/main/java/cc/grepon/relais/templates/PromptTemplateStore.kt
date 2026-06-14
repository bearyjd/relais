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

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray

/**
 * Canonical, user-editable prompt-template library. Static `object` accessed with a [Context] (the
 * node-layer idiom). Persists a JSON array to `filesDir/relais_templates.json`; seeds built-ins on
 * first run and always re-merges them so a corrupt/edited file can never strip the default personas.
 * Bounded ([MAX_TEMPLATES] count, [MAX_SYSTEM]/[MAX_NAME] content) on BOTH the write ([upsert]) and the
 * read ([load]) paths, so neither the API nor a hand-edited file can grow a per-request prompt past
 * the cap (DoS guard).
 *
 * Read today by the API (`/v1/chat/completions`, `/generate`). [WorkflowRegistry] is the façade the
 * PLANNED tile (#2) / widget (#3) / NFC (#15) surfaces will use — not yet on this branch. [upsert] /
 * [delete] back the deferred in-app editor; until that UI lands, only the seeded built-ins are
 * reachable to end users (custom templates are still createable via these APIs + fully tested).
 * Single-process, so the in-memory cache is coherent across surfaces.
 */
object PromptTemplateStore {
  private const val TAG = "PromptTemplateStore"
  private const val FILE = "relais_templates.json"
  private const val MAX_TEMPLATES = 64
  // Content caps are `internal` so the in-app editor's pure logic ([TemplateEditorLogic]) validates
  // against the same numbers the store enforces — one source of truth, no drift.
  internal const val MAX_NAME = 80
  internal const val MAX_SYSTEM = 8_192 // bounds file size + per-request prompt growth

  internal val BUILTINS = listOf(
    PromptTemplate("default", "Default", "", builtin = true),
    PromptTemplate(
      "terse-coder", "Terse Coder",
      "You are a terse senior engineer. Answer with code first, minimal prose.", builtin = true,
    ),
    PromptTemplate(
      "json-only", "JSON Only",
      "Respond with ONLY valid JSON. No prose, no markdown fences.", builtin = true,
    ),
    PromptTemplate(
      "translator-fr", "Translate → French",
      "Translate the user's message into natural French. Output only the translation.", builtin = true,
    ),
  )

  /** How many user templates fit alongside the always-present built-ins (the editor's create cap). */
  internal val CUSTOM_CAP = MAX_TEMPLATES - BUILTINS.size

  @Volatile private var cache: List<PromptTemplate>? = null
  private val lock = Any()

  fun all(context: Context): List<PromptTemplate> =
    cache ?: synchronized(lock) { cache ?: load(context.applicationContext).also { cache = it } }

  fun resolve(context: Context, id: String?): PromptTemplate? {
    val key = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return all(context).firstOrNull { it.id == key }
  }

  /** True only when a non-blank id was supplied AND it doesn't exist — lets the HTTP layer 400. */
  fun isUnknown(context: Context, id: String?): Boolean {
    val key = id?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return all(context).none { it.id == key }
  }

  /** Adds/updates a user template. Returns false (no-op) if [PromptTemplate.id] collides with a built-in. */
  fun upsert(context: Context, template: PromptTemplate): Boolean {
    if (BUILTINS.any { it.id == template.id }) return false
    synchronized(lock) {
      val sanitized = template.copy(
        name = template.name.take(MAX_NAME).ifBlank { template.id },
        system = template.system.take(MAX_SYSTEM),
        builtin = false, // user edits never claim built-in status
      )
      val next = (all(context).filterNot { it.id == sanitized.id } + sanitized).take(MAX_TEMPLATES)
      persist(context.applicationContext, next)
      cache = next
    }
    return true
  }

  /** Deletes a user template. Returns false if it's a built-in or doesn't exist. */
  fun delete(context: Context, id: String): Boolean {
    if (BUILTINS.any { it.id == id }) return false
    synchronized(lock) {
      val current = all(context)
      if (current.none { it.id == id }) return false
      val next = current.filterNot { it.id == id }
      persist(context.applicationContext, next)
      cache = next
    }
    return true
  }

  private fun load(ctx: Context): List<PromptTemplate> {
    val file = File(ctx.filesDir, FILE)
    if (!file.exists()) {
      persist(ctx, BUILTINS)
      return BUILTINS
    }
    val parsed = runCatching {
      val arr = JSONArray(file.readText())
      (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(PromptTemplate::fromJson) }
    }.getOrElse {
      Log.e(TAG, "templates file unreadable; reseeding built-ins", it)
      emptyList()
    }
    // Built-ins are authoritative and always present (a persisted/tampered entry can't override a
    // built-in id). Custom entries are preserved, de-duped, content-bounded on the READ path too — so
    // a hand-edited file cannot smuggle an oversized system/name past the DoS caps — and limited to
    // the remaining budget (drops are logged, never silent).
    val builtinIds = BUILTINS.mapTo(HashSet()) { it.id }
    val customs = parsed
      .filterNot { it.id in builtinIds }
      .distinctBy { it.id }
      .map {
        it.copy(
          name = it.name.take(MAX_NAME).ifBlank { it.id },
          system = it.system.take(MAX_SYSTEM),
          builtin = false,
        )
      }
    val customCap = MAX_TEMPLATES - BUILTINS.size
    if (customs.size > customCap) {
      Log.w(TAG, "dropping ${customs.size - customCap} template(s) over the custom cap ($customCap)")
    }
    return BUILTINS + customs.take(customCap)
  }

  private fun persist(ctx: Context, list: List<PromptTemplate>) {
    val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
    runCatching { File(ctx.filesDir, FILE).writeText(arr.toString()) }
      .onFailure { Log.e(TAG, "failed to persist templates", it) }
  }

  internal fun resetCacheForTest() {
    cache = null
  }
}
