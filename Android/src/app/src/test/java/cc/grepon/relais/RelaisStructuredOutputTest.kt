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

import cc.grepon.relais.RelaisStructuredOutput.ResponseFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [RelaisStructuredOutput] (feature-05). No Android/Context/Robolectric. */
class RelaisStructuredOutputTest {

  private fun body(json: String) = JSONObject(json)

  // --- Block 1: parseResponseFormat ---

  @Test fun `absent response_format is Text`() {
    assertEquals(ResponseFormat.Text, RelaisStructuredOutput.parseResponseFormat(body("""{}""")))
  }

  @Test fun `type text is Text`() {
    assertEquals(ResponseFormat.Text, RelaisStructuredOutput.parseResponseFormat(body("""{"response_format":{"type":"text"}}""")))
  }

  @Test fun `json_object is JsonObject`() {
    assertEquals(ResponseFormat.JsonObject, RelaisStructuredOutput.parseResponseFormat(body("""{"response_format":{"type":"json_object"}}""")))
  }

  @Test fun `json_schema parsed with name and schema`() {
    val f = RelaisStructuredOutput.parseResponseFormat(
      body("""{"response_format":{"type":"json_schema","json_schema":{"name":"addr","schema":{"type":"object","required":["street"]}}}}""")
    )
    assertTrue(f is ResponseFormat.JsonSchema)
    f as ResponseFormat.JsonSchema
    assertEquals("addr", f.name)
    assertFalse(f.strict)
    assertEquals("object", f.schema.optString("type"))
  }

  @Test fun `json_schema strict flag and default name`() {
    val f = RelaisStructuredOutput.parseResponseFormat(
      body("""{"response_format":{"type":"json_schema","json_schema":{"strict":true,"schema":{}}}}""")
    ) as ResponseFormat.JsonSchema
    assertTrue(f.strict)
    assertEquals("response", f.name) // default
  }

  @Test fun `unknown type returns null`() {
    assertNull(RelaisStructuredOutput.parseResponseFormat(body("""{"response_format":{"type":"future"}}""")))
  }

  @Test fun `non-object response_format returns null`() {
    assertNull(RelaisStructuredOutput.parseResponseFormat(body("""{"response_format":"json_object"}""")))
  }

  // --- Block 2: isValidOutput ---

  @Test fun `empty object is valid JsonObject`() {
    assertTrue(RelaisStructuredOutput.isValidOutput("{}", ResponseFormat.JsonObject))
  }

  @Test fun `non-json is invalid JsonObject`() {
    assertFalse(RelaisStructuredOutput.isValidOutput("not json", ResponseFormat.JsonObject))
  }

  @Test fun `object with field is valid JsonObject`() {
    assertTrue(RelaisStructuredOutput.isValidOutput("""{"a":1}""", ResponseFormat.JsonObject))
  }

  @Test fun `blank is invalid JsonObject`() {
    assertFalse(RelaisStructuredOutput.isValidOutput("   ", ResponseFormat.JsonObject))
  }

  private fun schema(json: String) = ResponseFormat.JsonSchema("s", JSONObject(json), false)

  @Test fun `required present is valid`() {
    assertTrue(RelaisStructuredOutput.isValidOutput("""{"street":"Main"}""", schema("""{"type":"object","required":["street"]}""")))
  }

  @Test fun `required missing is invalid`() {
    assertFalse(RelaisStructuredOutput.isValidOutput("""{"zip":"90210"}""", schema("""{"type":"object","required":["street"]}""")))
  }

  @Test fun `additionalProperties false rejects extra keys`() {
    val s = schema("""{"type":"object","properties":{"name":{"type":"string"}},"additionalProperties":false}""")
    assertFalse(RelaisStructuredOutput.isValidOutput("""{"name":"x","extra":1}""", s))
    assertTrue(RelaisStructuredOutput.isValidOutput("""{"name":"x"}""", s))
  }

  @Test fun `type object rejects array`() {
    assertFalse(RelaisStructuredOutput.isValidOutput("[1,2,3]", schema("""{"type":"object"}""")))
  }

  @Test fun `type array accepts array`() {
    assertTrue(RelaisStructuredOutput.isValidOutput("[1,2,3]", schema("""{"type":"array"}""")))
  }

  @Test fun `nested property type enforced`() {
    val s = schema("""{"type":"object","properties":{"age":{"type":"number"}}}""")
    assertFalse(RelaisStructuredOutput.isValidOutput("""{"age":"old"}""", s))
    assertTrue(RelaisStructuredOutput.isValidOutput("""{"age":42}""", s))
  }

  @Test fun `integer property accepts float value (engine returns 42_0)`() {
    val s = schema("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
    assertTrue(RelaisStructuredOutput.isValidOutput("""{"age":42.0}""", s))
  }

  @Test fun `boolean is not a number`() {
    val s = schema("""{"type":"object","properties":{"age":{"type":"integer"}}}""")
    assertFalse(RelaisStructuredOutput.isValidOutput("""{"age":true}""", s))
  }

  @Test fun `Text format accepts anything`() {
    assertTrue(RelaisStructuredOutput.isValidOutput("not json", ResponseFormat.Text))
  }

  // --- Block 3: repairOutput ---

  @Test fun `repair strips leading prose`() {
    assertEquals("""{"a":1}""", RelaisStructuredOutput.repairOutput("""Sure! Here you go: {"a":1}"""))
  }

  @Test fun `repair strips code fences`() {
    assertEquals("""{"b":2}""", RelaisStructuredOutput.repairOutput("```json\n{\"b\":2}\n```"))
  }

  @Test fun `repair already-clean object returns it`() {
    assertEquals("""{"c":3}""", RelaisStructuredOutput.repairOutput("""{"c":3}"""))
  }

  @Test fun `repair unsalvageable returns null`() {
    assertNull(RelaisStructuredOutput.repairOutput("no json here at all"))
  }

  @Test fun `repair extracts array from prose`() {
    assertEquals("[1,2]", RelaisStructuredOutput.repairOutput("prefix [1,2] suffix"))
  }

  @Test fun `repair respects braces inside strings`() {
    assertEquals("""{"a":"}{"}""", RelaisStructuredOutput.repairOutput("""x {"a":"}{"} y"""))
  }

  @Test fun `repair prefers the largest valid JSON over a decoy bracket`() {
    assertEquals("""{"name":"Bob"}""", RelaisStructuredOutput.repairOutput("""Here is item [1]: {"name":"Bob"}"""))
  }

  // --- Block 4: shouldRetry ---

  @Test fun `retry when attempts remain and no valid repair`() {
    assertTrue(RelaisStructuredOutput.shouldRetry(0, 2, null, ResponseFormat.JsonObject))
  }

  @Test fun `no retry when exhausted`() {
    assertFalse(RelaisStructuredOutput.shouldRetry(2, 2, null, ResponseFormat.JsonObject))
  }

  @Test fun `no retry when repaired is valid`() {
    assertFalse(RelaisStructuredOutput.shouldRetry(1, 2, "{}", ResponseFormat.JsonObject))
  }

  @Test fun `no retry when zero retries configured`() {
    assertFalse(RelaisStructuredOutput.shouldRetry(0, 0, null, ResponseFormat.JsonObject))
  }

  // --- schemaToFunctionJson ---

  @Test fun `schemaToFunctionJson carries name and parameters`() {
    val fn = JSONObject(RelaisStructuredOutput.schemaToFunctionJson("emit", JSONObject("""{"type":"object","properties":{"x":{"type":"string"}}}""")))
    assertEquals("emit", fn.optString("name"))
    assertEquals("object", fn.optJSONObject("parameters")?.optString("type"))
    assertTrue(fn.optString("description").isNotBlank())
  }
}
