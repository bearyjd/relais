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

import cc.grepon.relais.widget.RESPONSE_CAP
import cc.grepon.relais.widget.WidgetPhase
import cc.grepon.relais.widget.WidgetUiState
import cc.grepon.relais.widget.capResponse
import cc.grepon.relais.widget.shouldRunWidgetPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM truth table for the widget's [WidgetUiState] machine, the [capResponse] bound, and the
 * cold-start [shouldRunWidgetPrompt] gate. No Android/Glance types — these run as plain unit tests.
 */
class WidgetStateTest {

  @Test fun `loading carries the prompt and drops any prior response`() {
    val state = WidgetUiState.idle().loading("status check")
    assertEquals(WidgetPhase.LOADING, state.phase)
    assertEquals("status check", state.prompt)
    assertNull("a fresh load shows no stale answer", state.response)
  }

  @Test fun `done keeps the originating prompt and carries the capped response`() {
    val state = WidgetUiState.idle().loading("ask").done("answer")
    assertEquals(WidgetPhase.DONE, state.phase)
    assertEquals("ask", state.prompt) // round-trips the prompt that produced this answer
    assertEquals("answer", state.response)
  }

  @Test fun `error keeps the prompt and carries the message as the response slot`() {
    val state = WidgetUiState.idle().loading("ask").error("node not ready")
    assertEquals(WidgetPhase.ERROR, state.phase)
    assertEquals("ask", state.prompt)
    assertEquals("node not ready", state.response)
  }

  @Test fun `idle is the cleared state — no prompt, no response`() {
    val state = WidgetUiState.idle()
    assertEquals(WidgetPhase.IDLE, state.phase)
    assertNull(state.prompt)
    assertNull(state.response)
  }

  @Test fun `clearing a done state returns to idle`() {
    val cleared = WidgetUiState.idle().loading("ask").done("answer").cleared()
    assertEquals(WidgetUiState.idle(), cleared)
  }

  @Test fun `capResponse is a no-op under the cap`() {
    val short = "x".repeat(RESPONSE_CAP - 1)
    assertEquals(short, capResponse(short))
  }

  @Test fun `capResponse is a no-op exactly at the cap`() {
    val exact = "x".repeat(RESPONSE_CAP)
    assertEquals(exact, capResponse(exact))
    assertEquals(RESPONSE_CAP, capResponse(exact).length)
  }

  @Test fun `capResponse truncates over the cap to exactly the cap`() {
    val long = "x".repeat(RESPONSE_CAP + 500)
    assertEquals(RESPONSE_CAP, capResponse(long).length)
  }

  @Test fun `done caps an oversized response`() {
    val long = "y".repeat(RESPONSE_CAP + 100)
    val state = WidgetUiState.idle().done(long)
    assertEquals(RESPONSE_CAP, state.response?.length)
  }

  @Test fun `error caps an oversized message`() {
    val long = "z".repeat(RESPONSE_CAP + 100)
    val state = WidgetUiState.idle().error(long)
    assertEquals(RESPONSE_CAP, state.response?.length)
  }

  @Test fun `shouldRunWidgetPrompt is true only when ready and a non-blank prompt is present`() {
    assertTrue(shouldRunWidgetPrompt(ready = true, prompt = "status check"))
  }

  @Test fun `shouldRunWidgetPrompt is false when the engine is not ready (cold-start guard)`() {
    // The single most important assertion: a tap can never kick off inference on a cold engine.
    assertFalse(shouldRunWidgetPrompt(ready = false, prompt = "status check"))
  }

  @Test fun `shouldRunWidgetPrompt is false for a null or blank prompt`() {
    assertFalse(shouldRunWidgetPrompt(ready = true, prompt = null))
    assertFalse(shouldRunWidgetPrompt(ready = true, prompt = ""))
    assertFalse(shouldRunWidgetPrompt(ready = true, prompt = "   "))
  }

  @Test fun `every phase round-trips through the helpers without leaking another phase's fields`() {
    // LOADING never carries a response; DONE/ERROR always do; IDLE carries neither.
    val loading = WidgetUiState.idle().loading("p")
    assertNull(loading.response)
    val done = loading.done("r")
    assertEquals("r", done.response)
    val error = loading.error("e")
    assertEquals("e", error.response)
  }
}
