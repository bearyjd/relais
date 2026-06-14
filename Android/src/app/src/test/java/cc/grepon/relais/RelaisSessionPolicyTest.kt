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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the session-memory policy (Feature #5): no Android types, no device. */
class RelaisSessionPolicyTest {

  @Test fun `header wins over ip hash`() {
    val key = RelaisSessionPolicy.resolveSessionKey(header = "my-chat", clientIpHash = "deadbeef")
    assertEquals("h:my-chat", key)
  }

  @Test fun `ip hash used when header absent`() {
    val key = RelaisSessionPolicy.resolveSessionKey(header = null, clientIpHash = "deadbeef")
    assertEquals("ip:deadbeef", key)
  }

  @Test fun `ip hash used when header blank`() {
    val key = RelaisSessionPolicy.resolveSessionKey(header = "   ", clientIpHash = "deadbeef")
    assertEquals("ip:deadbeef", key)
  }

  @Test fun `null when neither header nor ip hash`() {
    assertNull(RelaisSessionPolicy.resolveSessionKey(header = null, clientIpHash = null))
    assertNull(RelaisSessionPolicy.resolveSessionKey(header = "", clientIpHash = null))
  }

  @Test fun `oversized header is capped to the max length`() {
    val long = "a".repeat(500)
    val key = RelaisSessionPolicy.resolveSessionKey(header = long, clientIpHash = null)
    assertEquals("h:" + "a".repeat(RelaisSessionPolicy.MAX_SESSION_KEY_CHARS), key)
  }

  @Test fun `header sanitized to safe charset`() {
    // Spaces, slashes, quotes, and newlines are dropped — only [A-Za-z0-9_.:-] survive.
    val key = RelaisSessionPolicy.resolveSessionKey(header = "a b/c\"d\ne_1.2:3-4", clientIpHash = null)
    assertEquals("h:abcde_1.2:3-4", key)
  }

  @Test fun `header that sanitizes to empty falls back to ip hash`() {
    val key = RelaisSessionPolicy.resolveSessionKey(header = "////", clientIpHash = "cafe")
    assertEquals("ip:cafe", key)
  }

  @Test fun `key is never a raw ip`() {
    // The resolver only ever sees a hash; even if a raw-IP-looking string is passed as the hash, it
    // is prefixed ip: and never surfaced verbatim as a bare address. The HTTP layer guarantees the
    // value passed here is already SHA-256(ip+salt), not the address itself.
    val key = RelaisSessionPolicy.resolveSessionKey(header = null, clientIpHash = "192.168.0.5")
    assertTrue(key!!.startsWith("ip:"))
    assertFalse(key == "192.168.0.5")
  }

  @Test fun `stored history used only for a bare turn`() {
    assertTrue(RelaisSessionPolicy.shouldUseStoredHistory(clientHistoryTurns = 0))
    assertFalse(RelaisSessionPolicy.shouldUseStoredHistory(clientHistoryTurns = 1))
    assertFalse(RelaisSessionPolicy.shouldUseStoredHistory(clientHistoryTurns = 4))
  }

  @Test fun `merge history caps to most recent N turns keeping order`() {
    val stored = (1..6).map { ParsedTurn(role = if (it % 2 == 1) "user" else "assistant", text = "t$it") }
    val merged = RelaisSessionPolicy.mergeHistory(stored, budgetTurns = 4)
    assertEquals(listOf("t3", "t4", "t5", "t6"), merged.map { it.text })
  }

  @Test fun `merge history returns all when under budget`() {
    val stored = listOf(ParsedTurn(role = "user", text = "a"), ParsedTurn(role = "assistant", text = "b"))
    assertEquals(stored, RelaisSessionPolicy.mergeHistory(stored, budgetTurns = 10))
  }

  @Test fun `merge history with non-positive budget is empty`() {
    val stored = listOf(ParsedTurn(role = "user", text = "a"))
    assertTrue(RelaisSessionPolicy.mergeHistory(stored, budgetTurns = 0).isEmpty())
    assertTrue(RelaisSessionPolicy.mergeHistory(emptyList(), budgetTurns = 4).isEmpty())
  }
}
