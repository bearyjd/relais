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

/** How a selected template's system text combines with an explicit `system` message in the request. */
enum class TemplateMode { PREPEND, REPLACE }

/**
 * Resolves the effective system prompt. PREPEND (default) stacks the template persona before an
 * explicit system message ("persona + caller refinement"); REPLACE treats the template only as a
 * fallback when the request has no explicit system. Returns null only when both are blank/absent (the
 * engine then uses its own default), so a request that sends neither is byte-for-byte unchanged. A
 * present-but-blank `system` message is normalized to "no system instruction" (intentional, benign —
 * previously it produced an empty instruction).
 */
fun resolveSystemPrompt(
  explicitSystem: String?,
  template: PromptTemplate?,
  mode: TemplateMode = TemplateMode.PREPEND,
): String? {
  val explicit = explicitSystem?.takeIf { it.isNotBlank() }
  val tmpl = template?.system?.takeIf { it.isNotBlank() }
  return when (mode) {
    TemplateMode.REPLACE -> explicit ?: tmpl
    TemplateMode.PREPEND -> if (tmpl != null && explicit != null) "$tmpl\n\n$explicit" else tmpl ?: explicit
  }
}

/** OpenAI body → template id. `template` wins over the `x_relais_template` alias; blank/absent → null. */
fun parseTemplateId(body: JSONObject): String? =
  body.optString("template").takeIf { it.isNotBlank() }
    ?: body.optString("x_relais_template").takeIf { it.isNotBlank() }

/** `x_relais_template_mode` → mode; only the literal "replace" (case-insensitive) selects REPLACE. */
fun parseTemplateMode(body: JSONObject): TemplateMode =
  if (body.optString("x_relais_template_mode").equals("replace", ignoreCase = true)) TemplateMode.REPLACE
  else TemplateMode.PREPEND
