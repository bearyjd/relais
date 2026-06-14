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

import cc.grepon.relais.templates.TemplateMode
import cc.grepon.relais.templates.parseTemplateId
import cc.grepon.relais.templates.parseTemplateMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for parsing the OpenAI-body template selector fields. */
class TemplateParamParseTest {

  @Test fun `template field is read`() {
    assertEquals("a", parseTemplateId(JSONObject().put("template", "a")))
  }

  @Test fun `x_relais_template is read as fallback`() {
    assertEquals("b", parseTemplateId(JSONObject().put("x_relais_template", "b")))
  }

  @Test fun `template wins over x_relais_template`() {
    assertEquals("a", parseTemplateId(JSONObject().put("template", "a").put("x_relais_template", "b")))
  }

  @Test fun `absent template id is null`() {
    assertNull(parseTemplateId(JSONObject()))
  }

  @Test fun `blank template id is null`() {
    assertNull(parseTemplateId(JSONObject().put("template", "")))
  }

  @Test fun `mode defaults to prepend`() {
    assertEquals(TemplateMode.PREPEND, parseTemplateMode(JSONObject()))
  }

  @Test fun `mode replace is parsed case-insensitively`() {
    assertEquals(TemplateMode.REPLACE, parseTemplateMode(JSONObject().put("x_relais_template_mode", "replace")))
    assertEquals(TemplateMode.REPLACE, parseTemplateMode(JSONObject().put("x_relais_template_mode", "REPLACE")))
  }

  @Test fun `mode other than replace is prepend`() {
    assertEquals(TemplateMode.PREPEND, parseTemplateMode(JSONObject().put("x_relais_template_mode", "prepend")))
  }
}
