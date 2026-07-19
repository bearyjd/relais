/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import cc.grepon.relais.nodetools.NodeToolArgs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays frozen fixtures under `test/resources/fixtures/tool-calls/` through the same
 * parsing/shaping functions [RelaisToolParsingTest] and [ToolConversationParseTest] exercise with
 * inline literals — [NodeToolArgs] (node_tools typed-argument unwrap) and [buildPromptParts]
 * (client-side `tool_calls` history parsing).
 *
 * Device-free JVM test: no Context, no Android SDK, no Robolectric — runs in the same suite as
 * `testFullOpenDebugUnitTest`.
 *
 * IMPORTANT — provenance: the fixtures loaded here are hand-authored surrogates matching
 * documented model behavior (see the `provenance`/`note` fields in each fixture file and
 * `fixtures/tool-calls/README.md`), not captured real litertlm output. This test proves the
 * parsing/shaping *code paths* handle the documented shapes correctly; it does not substitute for
 * on-device model-output-quality verification (`ToolCallingProbe`/`MultiTurnReplayProbe`, hardware
 * only, not CI).
 */
class RelaisToolFixtureReplayTest {

  private fun loadFixture(name: String): JSONObject {
    val stream =
      checkNotNull(javaClass.getResourceAsStream("/fixtures/tool-calls/$name")) {
        "Fixture not found on classpath: fixtures/tool-calls/$name"
      }
    return JSONObject(stream.bufferedReader().readText())
  }

  @Test
  fun `node-tool typed-argument fixture unwraps to plain values via NodeToolArgs`() {
    val fixture = loadFixture("node-tool-typed-argument.json")
    val args = fixture.getJSONObject("arguments")
    val expected = fixture.getJSONObject("expected")

    // The documented quirk: {"type":"STRING","value":"2+2"} unwraps to "2+2".
    assertEquals(expected.getString("expression"), NodeToolArgs.str(args, "expression"))

    // A plain (unwrapped) string value must also pass through unchanged.
    assertEquals(
      "plain string values (no wrapper) must also pass through unchanged",
      NodeToolArgs.str(args, "note"),
    )
  }

  @Test
  fun `multi-turn tool-call roundtrip fixture parses into history via buildPromptParts`() {
    val fixture = loadFixture("multi-turn-toolcall-roundtrip.json")
    val messages = fixture.getJSONArray("messages") as JSONArray
    val expected = fixture.getJSONObject("expected")

    val result = buildPromptParts(messages)

    assertEquals(expected.getString("lastUserText"), result.lastUserText)

    val assistantTurn = result.history.first { it.role == "assistant" }
    assertEquals(expected.getInt("assistantToolCallCount"), assistantTurn.toolCalls.size)
    assertEquals(expected.getString("assistantToolCallId"), assistantTurn.toolCalls[0].id)
    assertEquals(expected.getString("assistantToolCallName"), assistantTurn.toolCalls[0].name)
    assertEquals(
      expected.getString("assistantToolCallArgumentsJson"),
      assistantTurn.toolCalls[0].argumentsJson,
    )

    val toolTurn = result.history.first { it.role == "tool" }
    assertEquals(expected.getString("toolTurnName"), toolTurn.toolName)
    assertEquals(expected.getString("toolTurnText"), toolTurn.text)

    assertEquals(expected.getBoolean("liveToolResultsEmpty"), result.liveToolResults.isEmpty())
    assertTrue("expected no live tool results for this fixture", result.liveToolResults.isEmpty())
  }
}
