/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure payload shaping for the report send path (#258 gate 1). Device-free — [ContentReportDelivery]
 * only touches the network in [ContentReportDelivery.send], never in [ContentReportDelivery.buildPayload].
 */
class ContentReportDeliveryTest {

  private fun draft(
    reasonId: String = "other",
    excerpt: String = "flagged output",
    note: String? = null,
    modelId: String? = null,
    backend: String? = null,
  ) = ContentReportDraft(reasonId = reasonId, excerpt = excerpt, note = note, modelId = modelId, backend = backend)

  @Test
  fun `the payload carries exactly the six fields the Worker's schema accepts`() {
    val json =
      JSONObject(
        ContentReportDelivery.buildPayload(
          draft(note = "context", modelId = "gemma-4-E2B", backend = "GPU"),
          surface = "chat",
        )
      )
    assertEquals(
      setOf("reasonId", "surface", "excerpt", "note", "modelId", "backend"),
      json.keys().asSequence().toSet(),
    )
  }

  @Test
  fun `reasonId, surface and excerpt round-trip verbatim`() {
    val json =
      JSONObject(ContentReportDelivery.buildPayload(draft(reasonId = "harmful", excerpt = "x"), surface = "gallery_chat"))
    assertEquals("harmful", json.getString("reasonId"))
    assertEquals("gallery_chat", json.getString("surface"))
    assertEquals("x", json.getString("excerpt"))
  }

  @Test
  fun `a null note, modelId and backend are omitted rather than sent as JSON null`() {
    val json = JSONObject(ContentReportDelivery.buildPayload(draft(), surface = "chat"))
    assertFalse("note should be absent, not present-as-null", json.has("note"))
    assertFalse("modelId should be absent, not present-as-null", json.has("modelId"))
    assertFalse("backend should be absent, not present-as-null", json.has("backend"))
  }

  @Test
  fun `a present note, modelId and backend are all included`() {
    val json =
      JSONObject(
        ContentReportDelivery.buildPayload(
          draft(note = "note text", modelId = "gemma-4-E2B", backend = "GPU"),
          surface = "chat",
        )
      )
    assertTrue(json.has("note"))
    assertTrue(json.has("modelId"))
    assertTrue(json.has("backend"))
    assertEquals("note text", json.getString("note"))
    assertEquals("gemma-4-E2B", json.getString("modelId"))
    assertEquals("GPU", json.getString("backend"))
  }
}
