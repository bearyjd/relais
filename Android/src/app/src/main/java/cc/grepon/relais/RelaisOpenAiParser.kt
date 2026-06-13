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

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64 as JvmBase64

private const val TAG = "RelaisOpenAiParser"

/**
 * Budget for prior conversation turns (history only). Must stay in sync with
 * RelaisEngine.MAX_NUM_TOKENS (1024). Using 3 chars/token as a conservative proxy
 * (1024 × 3 = 3072 chars).
 *
 * NOTE: This budget covers ONLY the history turns. The system prompt and the live user
 * message consume additional context-window space on top of this. No attempt is made here
 * to account for those — the truncation policy keeps history within this char budget and
 * leaves the remaining window headroom for system + live-user content.
 */
internal const val MAX_HISTORY_CHARS = 3 * 1024 // 3 * MAX_NUM_TOKENS

/**
 * One turn from the OpenAI messages array, after normalization.
 *
 * NOTE: [imagePng] and [audioWav] are `ByteArray` fields on a `data class`. Kotlin data-class
 * `equals`/`hashCode` use **reference identity** for arrays (not structural equality). This is
 * intentional — turns are never compared or deduped in the parser pipeline, so reference
 * equality is harmless. Do not add this class to a Set or use it as a Map key without a
 * custom comparator.
 */
data class ParsedTurn(
  val role: String,           // "user" | "assistant" | "system" | "tool"
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
  /** Tool calls emitted by an assistant turn (OpenAI `tool_calls[]`). Empty for non-assistant. */
  val toolCalls: List<ParsedToolCall> = emptyList(),
  /** For a `role:"tool"` turn: the resolved function name this result answers (null otherwise). */
  val toolName: String? = null,
)

/** One assistant tool call (OpenAI `tool_calls[i]`), flattened for seeding/round-trip. */
data class ParsedToolCall(val id: String, val name: String, val argumentsJson: String)

/** One tool result message (`role:"tool"`) that forms part of the live turn. */
data class ToolResult(val name: String, val content: String)

/** Result of parsing the full messages[] array. */
data class ParsedMessages(
  val systemPrompt: String?,            // null if no system message present
  val history: List<ParsedTurn>,        // prior turns, oldest first (excludes last user turn)
  val lastUserText: String,             // text of the final user turn
  val lastUserImage: ByteArray? = null, // image from final user turn
  val lastUserAudio: ByteArray? = null, // audio from final user turn
  /** Trailing `role:"tool"` results that drive the live turn (empty in the non-tool path). */
  val liveToolResults: List<ToolResult> = emptyList(),
)

/**
 * Extracts text + first image + first audio from a content parts array.
 * Identical extraction logic to the pre-feature-03 parseOpenAiRequest, lifted into a helper.
 * The [dataUriBytes] lambda is provided by the caller so this function remains pure / testable.
 */
private fun extractParts(
  content: JSONArray,
  dataUriBytes: (String) -> ByteArray?,
  decode: (String) -> ByteArray,
): Triple<String, ByteArray?, ByteArray?> {
  var text = ""
  var image: ByteArray? = null
  var audio: ByteArray? = null
  for (j in 0 until content.length()) {
    val part = content.optJSONObject(j) ?: continue
    when (part.optString("type")) {
      "text" -> text = part.optString("text")
      "image_url" ->
        image = part.optJSONObject("image_url")?.optString("url")?.let { dataUriBytes(it) } ?: image
      "input_audio" ->
        audio = part.optJSONObject("input_audio")?.optString("data")?.let { decode(it) } ?: audio
    }
  }
  return Triple(text, image, audio)
}

/**
 * Pure function: walks the full OpenAI [messages] array and returns a [ParsedMessages] with:
 *  - [ParsedMessages.systemPrompt]: the last `system` role content (null if absent)
 *  - [ParsedMessages.history]: all turns before the last `user` message, in order
 *  - [ParsedMessages.lastUserText]/Image/Audio: the content of the final `user` message
 *
 * Truncation policy (oldest-first drop, system prompt always kept):
 *  if history.sumOf { it.text.length } > [maxHistoryChars], drop the oldest user+assistant pair
 *  and repeat until it fits. If after pair-dropping a dangling assistant turn leads history,
 *  drop that too.
 *
 * No Android types — pure JVM, unit-testable without a device.
 *
 * @param dataUriBytes  lambda to decode a data-URI string to bytes (injected for testability;
 *                      production callers pass [RelaisHttpServer.dataUriBytes]).
 * @param decode        lambda to base64-decode a string to bytes.
 */
internal fun buildPromptParts(
  messages: JSONArray,
  maxHistoryChars: Int = MAX_HISTORY_CHARS,
  dataUriBytes: (String) -> ByteArray? = { url ->
    runCatching {
      val stripped = if (url.startsWith("data:")) url.substringAfter(",") else url
      JvmBase64.getDecoder().decode(stripped)
    }.getOrNull()
  },
  decode: (String) -> ByteArray = { b64 ->
    JvmBase64.getDecoder().decode(b64)
  },
): ParsedMessages {
  // Find the index of the last user message in a single forward scan.
  var lastUserIndex = -1
  for (i in 0 until messages.length()) {
    val msg = messages.optJSONObject(i) ?: continue
    if (msg.optString("role") == "user") lastUserIndex = i
  }

  // Map tool_call_id -> function name, scanning ALL assistant messages' tool_calls[]. A trailing
  // role:"tool" result references the call it answers by `tool_call_id`; resolve names through this.
  val toolCallNames = buildToolCallNameMap(messages)

  // Detect a contiguous TRAILING run of role:"tool" messages (the live tool-result turn). When
  // present, those become liveToolResults and NO user message is promoted to the live turn.
  val trailingToolStart = trailingToolRunStart(messages)
  val hasTrailingToolRun = trailingToolStart != -1

  var systemPrompt: String? = null
  val history = mutableListOf<ParsedTurn>()
  var lastUserText = ""
  var lastUserImage: ByteArray? = null
  var lastUserAudio: ByteArray? = null
  val liveToolResults = mutableListOf<ToolResult>()

  // Walk forwards through all messages.
  for (i in 0 until messages.length()) {
    val msg = messages.optJSONObject(i) ?: continue
    val role = msg.optString("role")

    when (role) {
      "system" -> {
        // Last system message wins (non-standard but defensive).
        systemPrompt = when (val content = msg.opt("content")) {
          is String -> content
          is JSONArray -> extractParts(content, dataUriBytes, decode).first
          else -> ""
        }
      }

      "tool" -> {
        val name = resolveToolName(msg, toolCallNames)
        if (hasTrailingToolRun && i >= trailingToolStart) {
          // Part of the live trailing tool-result turn.
          liveToolResults.add(ToolResult(name = name, content = toolContent(msg)))
        } else {
          // A tool result earlier in the conversation -> history.
          history.add(ParsedTurn(role = "tool", text = toolContent(msg), toolName = name))
        }
      }

      "user", "assistant" -> {
        val (text, image, audio) = when (val content = msg.opt("content")) {
          is String -> Triple(content, null, null)
          is JSONArray -> extractParts(content, dataUriBytes, decode)
          else -> Triple("", null, null)
        }

        if (role == "user" && i == lastUserIndex && !hasTrailingToolRun) {
          // This is the live user turn — goes into lastUser*, not history. A trailing tool run
          // suppresses user promotion: the live turn is the tool results, not the last user message.
          lastUserText = text
          lastUserImage = image
          lastUserAudio = audio
        } else if (role == "assistant") {
          history.add(
            ParsedTurn(role = role, text = text, imagePng = image, audioWav = audio,
              toolCalls = parseAssistantToolCalls(msg)))
        } else {
          history.add(ParsedTurn(role = role, text = text, imagePng = image, audioWav = audio))
        }
      }
      // Unknown roles are silently skipped.
    }
  }

  // Normalize to user-first: a leading assistant or tool turn has no preceding user turn, which
  // would make the seeded conversation (RelaisEngine ConversationConfig.initialMessages) start with
  // a non-USER message — a shape chat templates don't expect. Drop any orphan leading non-user
  // turn(s). The truncation pass below has its own dangling-leading-assistant guard; this covers the
  // non-truncated path too.
  val userFirst = history.dropWhile { it.role != "user" }

  // Apply overflow truncation: drop oldest-first in user+assistant pairs, keep system always.
  val truncated = applyHistoryTruncation(userFirst, maxHistoryChars)

  return ParsedMessages(
    systemPrompt = systemPrompt,
    history = truncated,
    lastUserText = lastUserText,
    lastUserImage = lastUserImage,
    lastUserAudio = lastUserAudio,
    liveToolResults = liveToolResults,
  )
}

/**
 * Builds a `tool_call_id -> function name` map across ALL assistant messages' `tool_calls[]`. A
 * later `role:"tool"` result references the call it answers via `tool_call_id`; this map resolves
 * that id back to the originating function name.
 */
private fun buildToolCallNameMap(messages: JSONArray): Map<String, String> {
  val map = mutableMapOf<String, String>()
  for (i in 0 until messages.length()) {
    val msg = messages.optJSONObject(i) ?: continue
    if (msg.optString("role") != "assistant") continue
    val calls = msg.optJSONArray("tool_calls") ?: continue
    for (j in 0 until calls.length()) {
      val call = calls.optJSONObject(j) ?: continue
      val id = call.optString("id")
      val name = call.optJSONObject("function")?.optString("name").orEmpty()
      if (id.isNotEmpty() && name.isNotEmpty()) map[id] = name
    }
  }
  return map
}

/**
 * Index of the first message in the contiguous trailing run of `role:"tool"` messages, or -1 if the
 * last message is not a tool result. Skips trailing entries that fail to parse as JSON objects.
 */
private fun trailingToolRunStart(messages: JSONArray): Int {
  var start = -1
  for (i in messages.length() - 1 downTo 0) {
    val msg = messages.optJSONObject(i) ?: continue
    if (msg.optString("role") == "tool") start = i else break
  }
  return start
}

/** Resolves a tool message's function name: id-map first, then its own `"name"` field, else "". */
private fun resolveToolName(msg: JSONObject, toolCallNames: Map<String, String>): String {
  val byId = msg.optString("tool_call_id").takeIf { it.isNotEmpty() }?.let { toolCallNames[it] }
  if (!byId.isNullOrEmpty()) return byId
  return msg.optString("name")
}

/** A tool message's content as a string (string content verbatim; parts arrays flattened to text). */
private fun toolContent(msg: JSONObject): String =
  when (val content = msg.opt("content")) {
    is String -> content
    is JSONArray -> {
      val sb = StringBuilder()
      for (j in 0 until content.length()) {
        val part = content.optJSONObject(j) ?: continue
        if (part.optString("type") == "text") sb.append(part.optString("text"))
      }
      sb.toString()
    }
    else -> ""
  }

/**
 * Parses an assistant message's `tool_calls[]` into [ParsedToolCall]s. The `function.arguments`
 * field is usually already a JSON string; if a client sent it as a JSON object instead, it is
 * stringified so downstream always sees a string.
 */
private fun parseAssistantToolCalls(msg: JSONObject): List<ParsedToolCall> {
  val calls = msg.optJSONArray("tool_calls") ?: return emptyList()
  val out = mutableListOf<ParsedToolCall>()
  for (j in 0 until calls.length()) {
    val call = calls.optJSONObject(j) ?: continue
    val function = call.optJSONObject("function") ?: continue
    val name = function.optString("name")
    if (name.isEmpty()) continue
    val argumentsJson = when (val args = function.opt("arguments")) {
      is String -> args
      is JSONObject -> args.toString()
      null -> "{}"
      else -> args.toString()
    }
    out.add(ParsedToolCall(id = call.optString("id"), name = name, argumentsJson = argumentsJson))
  }
  return out
}

/**
 * Drops oldest turns from [history] until the total char count fits [maxHistoryChars].
 * Always drops in user+assistant pairs from the front. If a dangling assistant turn leads
 * the list after pair removal, it is dropped too (to preserve alternation parity).
 * Returns a new list; [history] is not mutated.
 *
 * Uses a running [totalChars] counter (O(K) drops, not O(N×K)) — each removed turn's
 * text length is subtracted rather than re-summing the whole list each iteration.
 */
private fun applyHistoryTruncation(
  history: List<ParsedTurn>,
  maxHistoryChars: Int,
): List<ParsedTurn> {
  var totalChars = history.sumOf { it.text.length }
  if (totalChars <= maxHistoryChars) return history

  val mutable = history.toMutableList()
  var dropped = 0

  while (totalChars > maxHistoryChars && mutable.isNotEmpty()) {
    // Drop one user+assistant pair from the front.
    // Find the first user turn.
    val firstUserIdx = mutable.indexOfFirst { it.role == "user" }
    if (firstUserIdx == -1) {
      // Only assistant turns left — drop them all to avoid parity issues.
      for (turn in mutable) totalChars -= turn.text.length
      dropped += mutable.size
      mutable.clear()
      break
    }
    // Drop the user turn and subtract its char count.
    totalChars -= mutable[firstUserIdx].text.length
    mutable.removeAt(firstUserIdx)
    dropped++
    // Drop the immediately following assistant turn (if present) and subtract its count.
    if (firstUserIdx < mutable.size && mutable[firstUserIdx].role == "assistant") {
      totalChars -= mutable[firstUserIdx].text.length
      mutable.removeAt(firstUserIdx)
      dropped++
    }
  }

  // If any leading turns are non-user (assistant or tool), drop them all. A multi-round tool
  // history that overflows the char budget can leave an orphan assistant or tool turn at the
  // front after pair removal; the seeded conversation must start with a user turn.
  while (mutable.isNotEmpty() && mutable[0].role != "user") {
    totalChars -= mutable[0].text.length
    mutable.removeAt(0)
    dropped++
  }

  if (dropped > 0) {
    Log.w(TAG, "History truncated: dropped $dropped turns to fit context window ($maxHistoryChars chars)")
  }

  return mutable
}
