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

import cc.grepon.relais.chat.TransportKind
import cc.grepon.relais.chat.chooseTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision function: does the chat UI stream via the loopback HTTP server (matches
 * production/multi-client behavior) or fall back to an in-process call to the resident engine
 * (works even before/without the HTTP server being reachable)? See chat/ChatTransport.kt.
 */
class ChatTransportChoiceTest {
  @Test
  fun httpWhenReachableAndReady() {
    assertEquals(TransportKind.HTTP, chooseTransport(healthReachable = true, nodeReady = true))
  }

  @Test
  fun inProcessWhenNotReachable() {
    assertEquals(
      TransportKind.IN_PROCESS, chooseTransport(healthReachable = false, nodeReady = true))
  }

  @Test
  fun inProcessWhenReachableButNotReady() {
    assertEquals(
      TransportKind.IN_PROCESS, chooseTransport(healthReachable = true, nodeReady = false))
  }
}
