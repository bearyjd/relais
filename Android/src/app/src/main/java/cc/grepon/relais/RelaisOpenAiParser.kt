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
  val role: String,           // "user" | "assistant" | "system"
  val text: String,
  val imagePng: ByteArray? = null,
  val audioWav: ByteArray? = null,
)

/** Result of parsing the full messages[] array. */
data class ParsedMessages(
  val systemPrompt: String?,            // null if no system message present
  val history: List<ParsedTurn>,        // prior turns, oldest first (excludes last user turn)
  val lastUserText: String,             // text of the final user turn
  val lastUserImage: ByteArray? = null, // image from final user turn
  val lastUserAudio: ByteArray? = null, // audio from final user turn
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

  var systemPrompt: String? = null
  val history = mutableListOf<ParsedTurn>()
  var lastUserText = ""
  var lastUserImage: ByteArray? = null
  var lastUserAudio: ByteArray? = null

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

      "user", "assistant" -> {
        val (text, image, audio) = when (val content = msg.opt("content")) {
          is String -> Triple(content, null, null)
          is JSONArray -> extractParts(content, dataUriBytes, decode)
          else -> Triple("", null, null)
        }

        if (role == "user" && i == lastUserIndex) {
          // This is the live user turn — goes into lastUser*, not history.
          lastUserText = text
          lastUserImage = image
          lastUserAudio = audio
        } else {
          history.add(ParsedTurn(role = role, text = text, imagePng = image, audioWav = audio))
        }
      }
      // Unknown roles are silently skipped.
    }
  }

  // Apply overflow truncation: drop oldest-first in user+assistant pairs, keep system always.
  val truncated = applyHistoryTruncation(history, maxHistoryChars)

  return ParsedMessages(
    systemPrompt = systemPrompt,
    history = truncated,
    lastUserText = lastUserText,
    lastUserImage = lastUserImage,
    lastUserAudio = lastUserAudio,
  )
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

  // If the front of remaining history is a dangling assistant turn, drop it.
  if (mutable.isNotEmpty() && mutable[0].role == "assistant") {
    totalChars -= mutable[0].text.length
    mutable.removeAt(0)
    dropped++
  }

  if (dropped > 0) {
    Log.w(TAG, "History truncated: dropped $dropped turns to fit context window ($maxHistoryChars chars)")
  }

  return mutable
}
