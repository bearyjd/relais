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

import cc.grepon.relais.nodetools.NodeToolArgs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for [NodeToolArgs] — extracting node-tool args, tolerating the E2B `{type,value}` shape. */
class NodeToolArgsTest {

  @Test fun `plain values pass through`() {
    val a = JSONObject("""{"s":"hi","n":3.5,"k":7}""")
    assertEquals("hi", NodeToolArgs.str(a, "s"))
    assertEquals(3.5, NodeToolArgs.double(a, "n")!!, 1e-9)
    assertEquals(7, NodeToolArgs.int(a, "k"))
  }

  @Test fun `typed-wrapped string is unwrapped`() {
    val a = JSONObject("""{"expression":{"type":"STRING","value":"2+2"}}""")
    assertEquals("2+2", NodeToolArgs.str(a, "expression"))
  }

  @Test fun `typed-wrapped number is unwrapped`() {
    val a = JSONObject("""{"value":{"type":"NUMBER","value":3.5}}""")
    assertEquals(3.5, NodeToolArgs.double(a, "value")!!, 1e-9)
  }

  @Test fun `numeric string parses to double`() {
    assertEquals(42.0, NodeToolArgs.double(JSONObject("""{"v":"42"}"""), "v")!!, 1e-9)
  }

  @Test fun `missing key and json null are null`() {
    assertNull(NodeToolArgs.str(JSONObject("{}"), "x"))
    assertNull(NodeToolArgs.double(JSONObject("{}"), "x"))
    assertNull(NodeToolArgs.str(JSONObject("""{"x":null}"""), "x"))
  }
}
