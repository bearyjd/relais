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

/**
 * Pure, Android-free decision logic for the in-app prompt-template editor
 * ([PromptTemplateEditorActivity]). Everything that can be decided without Compose or a [Context]
 * lives here so it's unit-tested on the JVM (no instrumentation). The caps mirror
 * [PromptTemplateStore]'s own enforcement exactly — they reference the store's constants, so the UI
 * fails the same input the store would silently truncate/reject, rather than drifting.
 */

/** Editor form fields. A null [id] means "new template"; a non-null [id] means "editing this row". */
data class TemplateFormState(
  val id: String?,
  val name: String,
  val system: String,
)

/** Outcome of validating the editor form before an upsert. */
enum class TemplateFormValidation {
  Ok,
  NameBlank,
  NameTooLong,
  SystemTooLong,
  AtCapacity,
}

/** Bound for the safe fallback slug and the suffixed-id length guard. */
private const val ID_MAX_LENGTH = PromptTemplateStore.MAX_NAME // 80

/** Id used when a name has no slug-able characters at all (blank / symbols / emoji only). */
private const val FALLBACK_ID = "template"

/**
 * Validates the editor form against the store's caps. A new template is rejected once the user
 * already holds [PromptTemplateStore.CUSTOM_CAP] customs; editing an existing row replaces in place,
 * so it never trips the cap regardless of [currentCustomCount].
 */
fun validateTemplateForm(
  name: String,
  system: String,
  isNew: Boolean,
  currentCustomCount: Int,
): TemplateFormValidation = when {
  name.isBlank() -> TemplateFormValidation.NameBlank
  name.length > PromptTemplateStore.MAX_NAME -> TemplateFormValidation.NameTooLong
  system.length > PromptTemplateStore.MAX_SYSTEM -> TemplateFormValidation.SystemTooLong
  isNew && currentCustomCount >= PromptTemplateStore.CUSTOM_CAP -> TemplateFormValidation.AtCapacity
  else -> TemplateFormValidation.Ok
}

/**
 * Derives a stable, unique id from [name]: lowercased, every non-alphanumeric run collapsed to a
 * single `-`, edge dashes trimmed, length-bounded. Guarantees the result is absent from
 * [existingIds] AND never collides with a built-in id (built-ins are authoritative and can't be
 * shadowed), suffixing `-2`, `-3`, … on collision. A name with nothing slug-able falls back to
 * [FALLBACK_ID].
 */
fun slugifyTemplateId(name: String, existingIds: Set<String>): String {
  val base = name.lowercase()
    .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("")
    .trim('-')
    .replace(Regex("-+"), "-")
    .take(ID_MAX_LENGTH)
    .ifEmpty { FALLBACK_ID }

  val reserved = existingIds + PromptTemplateStore.BUILTINS.map { it.id }
  if (base !in reserved) return base

  var suffix = 2
  while (true) {
    // Keep room for the "-N" suffix without exceeding the bound.
    val candidate = base.take(ID_MAX_LENGTH - "-$suffix".length).trimEnd('-') + "-$suffix"
    if (candidate !in reserved) return candidate
    suffix++
  }
}

/** Built-ins ship with the app and are read-only; only user templates can be edited or deleted. */
fun isEditable(template: PromptTemplate): Boolean = !template.builtin
