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

import org.json.JSONObject

/**
 * OpenAI `tool_choice` parameter, modeled as a closed hierarchy.
 *  - [None]: caller forbids tool use (`"none"`). Tools are not advertised to the model.
 *  - [Auto]: model decides (`"auto"`, or the field is absent — OpenAI's default).
 *  - [Required]: model MUST call a tool (`"required"`).
 *  - [Forced]: model must call the named tool (`{"type":"function","function":{"name":"x"}}`).
 *
 * The native LiteRT-LM tool path does not distinguish Auto/Required/Forced at the engine level
 * (the model is simply offered the tools); these are carried through faithfully for callers/future
 * use, but only [None] currently changes behavior (it suppresses tool advertisement in the parser).
 */
sealed interface ToolChoice {
  object None : ToolChoice
  object Auto : ToolChoice
  object Required : ToolChoice
  data class Forced(val name: String) : ToolChoice
}

/**
 * One advertised tool. [functionJson] is the OpenAI `tools[i].function` object serialized verbatim
 * (`{"name":...,"description":...,"parameters":{...}}`) — the LiteRT-LM `tool(OpenApiTool)` bridge
 * parses it directly and requires a top-level `"name"` field.
 */
data class ToolSpec(val name: String, val functionJson: String)

/**
 * Parses the OpenAI `tools` array into [ToolSpec]s. Returns `[]` when `tools` is absent or empty.
 * Skips entries whose `type` is not `"function"` and entries missing `function.name`. The function
 * object is re-serialized verbatim so the LiteRT-LM bridge receives the exact OpenAI shape.
 */
fun parseTools(body: JSONObject): List<ToolSpec> {
  val arr = body.optJSONArray("tools") ?: return emptyList()
  val out = mutableListOf<ToolSpec>()
  for (i in 0 until arr.length()) {
    val entry = arr.optJSONObject(i) ?: continue
    if (entry.optString("type") != "function") continue
    val function = entry.optJSONObject("function") ?: continue
    val name = function.optString("name")
    if (name.isEmpty()) continue
    out.add(ToolSpec(name = name, functionJson = function.toString()))
  }
  return out
}

/**
 * Parses the OpenAI `tool_choice` parameter. Absent -> [ToolChoice.Auto] (OpenAI default).
 * String forms: `"none"`/`"auto"`/`"required"`. Object form
 * `{"type":"function","function":{"name":"x"}}` -> [ToolChoice.Forced]. Unknown shapes fall back
 * to [ToolChoice.Auto].
 */
fun parseToolChoice(body: JSONObject): ToolChoice {
  val choice = body.opt("tool_choice") ?: return ToolChoice.Auto
  return when (choice) {
    is String ->
      when (choice) {
        "none" -> ToolChoice.None
        "required" -> ToolChoice.Required
        else -> ToolChoice.Auto // "auto" + any unrecognized string
      }
    is JSONObject -> {
      val name = choice.optJSONObject("function")?.optString("name")
      if (!name.isNullOrEmpty()) ToolChoice.Forced(name) else ToolChoice.Auto
    }
    else -> ToolChoice.Auto
  }
}
