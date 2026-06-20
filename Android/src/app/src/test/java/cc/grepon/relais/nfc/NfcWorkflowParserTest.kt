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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcWorkflowParserTest {
  @Test fun `valid uri parses the template id`() {
    val tap = NfcWorkflowParser.parse("cc.grepon.relais://workflow/terse-coder")
    assertEquals("terse-coder", tap?.templateId)
    assertNull(tap?.prompt)
  }

  @Test fun `inline q is decoded into the prompt`() {
    val tap = NfcWorkflowParser.parse("cc.grepon.relais://workflow/translator-fr?q=hello%20world")
    assertEquals("translator-fr", tap?.templateId)
    assertEquals("hello world", tap?.prompt)
  }

  @Test fun `wrong scheme is rejected`() {
    assertNull(NfcWorkflowParser.parse("https://workflow/terse-coder"))
  }

  @Test fun `wrong host is rejected`() {
    assertNull(NfcWorkflowParser.parse("cc.grepon.relais://other/terse-coder"))
  }

  @Test fun `missing template id is rejected`() {
    assertNull(NfcWorkflowParser.parse("cc.grepon.relais://workflow/"))
    assertNull(NfcWorkflowParser.parse("cc.grepon.relais://workflow"))
  }

  @Test fun `null and blank are rejected`() {
    assertNull(NfcWorkflowParser.parse(null))
    assertNull(NfcWorkflowParser.parse("   "))
    assertNull(NfcWorkflowParser.parse("not a uri at all"))
  }

  @Test fun `over-long template id is rejected`() {
    val longId = "a".repeat(NfcWorkflowParser.MAX_ID + 1)
    assertNull(NfcWorkflowParser.parse("cc.grepon.relais://workflow/$longId"))
  }

  @Test fun `over-long inline prompt is capped`() {
    val longPrompt = "x".repeat(NfcWorkflowParser.MAX_PROMPT + 500)
    val tap = NfcWorkflowParser.parse(NfcWorkflowParser.buildUri("default", longPrompt))
    assertEquals(NfcWorkflowParser.MAX_PROMPT, tap?.prompt?.length)
  }

  @Test fun `scheme and host match case-insensitively`() {
    val tap = NfcWorkflowParser.parse("CC.GREPON.RELAIS://WORKFLOW/default")
    assertEquals("default", tap?.templateId)
  }

  @Test fun `build then parse round-trips id and prompt`() {
    val uri = NfcWorkflowParser.buildUri("json-only", "summarize: a & b = c")
    val tap = NfcWorkflowParser.parse(uri)
    assertEquals("json-only", tap?.templateId)
    assertEquals("summarize: a & b = c", tap?.prompt)
  }

  @Test fun `build without prompt omits the query`() {
    val uri = NfcWorkflowParser.buildUri("default")
    assertTrue(uri == "cc.grepon.relais://workflow/default")
  }
}
