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

import cc.grepon.relais.embed.RelaisEmbedderProvider
import cc.grepon.relais.nodetools.CalculatorTool
import cc.grepon.relais.nodetools.DateTimeTool
import cc.grepon.relais.nodetools.NodeTools
import cc.grepon.relais.nodetools.RagSearchTool
import cc.grepon.relais.nodetools.UnitConvertTool
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** JVM tests (Robolectric for the Context) for the node-side built-in tools. */
@RunWith(RobolectricTestRunner::class)
class NodeToolsTest {

  private val ctx get() = RuntimeEnvironment.getApplication()

  @Test fun `calculator executes and formats whole results`() = runBlocking {
    assertEquals("(2+3)*4 = 20", CalculatorTool.execute(ctx, JSONObject("""{"expression":"(2+3)*4"}""")))
  }

  @Test fun `calculator handles the typed-wrapped argument`() = runBlocking {
    assertEquals("2+2 = 4", CalculatorTool.execute(ctx, JSONObject("""{"expression":{"type":"STRING","value":"2+2"}}""")))
  }

  @Test fun `calculator returns an error string for bad input (no throw)`() = runBlocking {
    assertTrue(CalculatorTool.execute(ctx, JSONObject("""{"expression":"2 +"}""")).startsWith("error:"))
    assertTrue(CalculatorTool.execute(ctx, JSONObject("{}")).startsWith("error:"))
  }

  @Test fun `unit_convert executes`() = runBlocking {
    assertEquals("1 km = 1000 m", UnitConvertTool.execute(ctx, JSONObject("""{"value":1,"from":"km","to":"m"}""")))
    assertTrue(UnitConvertTool.execute(ctx, JSONObject("""{"value":1,"from":"m","to":"kg"}""")).startsWith("error:"))
  }

  @Test fun `unit_convert rejects a non-finite value`() = runBlocking {
    assertTrue(UnitConvertTool.execute(ctx, JSONObject("""{"value":"Infinity","from":"m","to":"km"}""")).startsWith("error:"))
  }

  @Test fun `current_datetime returns a parseable ISO string`() = runBlocking {
    ZonedDateTime.parse(DateTimeTool.execute(ctx, JSONObject("{}"))) // throws if not ISO-8601
    Unit
  }

  @Test fun `rag_search degrades gracefully when no embedder is available`() = runBlocking {
    RelaisEmbedderProvider.register(null)
    assertTrue(RagSearchTool.execute(ctx, JSONObject("""{"query":"anything"}""")).startsWith("error:"))
  }

  @Test fun `registry exposes exactly the four built-ins`() {
    assertEquals(
      setOf("calculator", "current_datetime", "unit_convert", "rag_search"),
      NodeTools.ALL.map { it.name }.toSet(),
    )
    assertTrue(NodeTools.isBuiltin("calculator"))
    assertFalse(NodeTools.isBuiltin("get_weather"))
    assertEquals(4, NodeTools.toolSpecs().size)
  }

  @Test fun `every schema is a valid OpenAI function object`() {
    for (t in NodeTools.ALL) {
      val s = t.schema()
      assertEquals(t.name, s.getString("name"))
      assertTrue("${t.name} missing description", s.has("description"))
      assertTrue("${t.name} missing parameters", s.getJSONObject("parameters").has("type"))
    }
  }
}
