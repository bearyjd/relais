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

package cc.grepon.relais

import cc.grepon.relais.templates.PromptTemplate
import cc.grepon.relais.templates.TemplateMode
import cc.grepon.relais.templates.resolveSystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for system-prompt precedence: an explicit system message vs. a selected template. */
class TemplatePrecedenceTest {

  private fun tmpl(system: String) = PromptTemplate(id = "t", name = "T", system = system)

  @Test fun `prepend combines template then explicit`() {
    assertEquals("PERSONA\n\nUSER", resolveSystemPrompt("USER", tmpl("PERSONA"), TemplateMode.PREPEND))
  }

  @Test fun `prepend with template only`() {
    assertEquals("PERSONA", resolveSystemPrompt(null, tmpl("PERSONA"), TemplateMode.PREPEND))
  }

  @Test fun `prepend with explicit only`() {
    assertEquals("USER", resolveSystemPrompt("USER", null, TemplateMode.PREPEND))
  }

  @Test fun `replace prefers explicit over template`() {
    assertEquals("USER", resolveSystemPrompt("USER", tmpl("PERSONA"), TemplateMode.REPLACE))
  }

  @Test fun `replace falls back to template when no explicit`() {
    assertEquals("PERSONA", resolveSystemPrompt(null, tmpl("PERSONA"), TemplateMode.REPLACE))
  }

  @Test fun `both null yields null (engine default applies)`() {
    assertNull(resolveSystemPrompt(null, null, TemplateMode.PREPEND))
  }

  @Test fun `blank explicit and blank template yield null`() {
    assertNull(resolveSystemPrompt("   ", tmpl("   "), TemplateMode.PREPEND))
  }

  @Test fun `blank explicit is ignored in prepend`() {
    assertEquals("PERSONA", resolveSystemPrompt("  ", tmpl("PERSONA"), TemplateMode.PREPEND))
  }

  @Test fun `empty-string system with no template is normalized to null`() {
    // A present-but-empty system message yields no system instruction (intentional, documented).
    assertNull(resolveSystemPrompt("", null, TemplateMode.PREPEND))
    assertNull(resolveSystemPrompt("", null, TemplateMode.REPLACE))
  }

  @Test fun `empty-string system still takes the template in prepend`() {
    assertEquals("PERSONA", resolveSystemPrompt("", tmpl("PERSONA"), TemplateMode.PREPEND))
  }
}
