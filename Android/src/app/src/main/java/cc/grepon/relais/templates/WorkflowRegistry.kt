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

/**
 * Shared-infra façade over [PromptTemplateStore] under the name the PLANNED QS tile (#2), widget (#3),
 * and NFC workflows (#15) will reference for their canned-prompt lists — keeping those (not-yet-landed)
 * callers decoupled from the store's internals.
 */
object WorkflowRegistry {
  fun templates(context: Context): List<PromptTemplate> = PromptTemplateStore.all(context)

  fun resolve(context: Context, id: String?): PromptTemplate? = PromptTemplateStore.resolve(context, id)
}
