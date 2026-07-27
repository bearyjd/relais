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

package cc.grepon.relais.tts

/**
 * Turns an assistant turn's **markdown** into something worth listening to (issue #211, in-app
 * playback). Chat renders markdown visually; handing that same string to Piper would have it read
 * the syntax out loud ("star star relais star star"), and read whole code blocks token by token.
 *
 * Pure (no Android types) so every rule below is covered by fast JVM unit tests.
 *
 * Deliberate non-rule: `_` is **not** treated as an emphasis marker. In this codebase's chat traffic
 * `snake_case` identifiers vastly outnumber `_underscore emphasis_`, and stripping `_` would mangle
 * them into separate words. `*`/`**` emphasis is stripped; `_` is left alone.
 */

/**
 * Characters of speakable text synthesized for one in-app playback. Well under the endpoint's 4096
 * ([TTS_LIMITS]) — this bounds *latency*, not correctness: at the measured RTF 0.12 a full 1200
 * characters is already ~10 s of audio and a couple of seconds of synthesis before playback starts.
 */
const val SPEECH_TEXT_MAX_CHARS = 1200

private val FENCED_CODE = Regex("(?s)```.*?```")
private val UNTERMINATED_FENCE = Regex("(?s)```.*$")
private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
private val BLOCKQUOTE = Regex("(?m)^\\s{0,3}>\\s?")
private val RULE = Regex("(?m)^\\s{0,3}(?:[-*_]\\s*){3,}$")
private val BULLET = Regex("(?m)^\\s*[-*+]\\s+")
private val ORDERED = Regex("(?m)^\\s*\\d+[.)]\\s+")
private val BOLD_ITALIC = Regex("\\*{1,3}")
private val WHITESPACE = Regex("\\s+")

/**
 * A markdown table's alignment row (`|---|---|`, `|:--|--:|`). It carries no words, so it must be
 * removed *before* pipes become commas — otherwise it reads out as a run of bare pauses. [RULE]
 * can't catch it: the pipes stop that pattern matching.
 */
private val TABLE_DIVIDER = Regex("(?m)^\\s*\\|?[\\s:|-]*\\|[\\s:|-]*$")

// Turning `|` into `, ` leaves punctuation a human would never write: a space before every comma,
// and a stray comma at each end of a row. Piper voices those as pauses, so a table row becomes
// "(pause) a (pause) b (pause)". These three normalise it back to "a, b".
private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.!?])")
private val REPEATED_COMMA = Regex("(?:,\\s*){2,}")
private val EDGE_COMMA = Regex("^\\s*,\\s*|\\s*,\\s*$")

/**
 * Reduce [markdown] to plain prose Piper can read, capped at [maxChars].
 *
 * Fenced code is dropped entirely rather than read aloud; inline-code *content* is kept (identifiers
 * like `RelaisEngine` carry the meaning — only the backticks go). Link text survives, URLs do not.
 * Table pipes become commas so rows don't run together into one unpunctuated sentence.
 *
 * Returns `""` when nothing speakable remains (e.g. a turn that was only a code block) — callers
 * should treat that as "nothing to play" rather than synthesizing silence.
 */
fun speakableText(markdown: String, maxChars: Int = SPEECH_TEXT_MAX_CHARS): String {
  val stripped =
    markdown
      .replace(FENCED_CODE, " ")
      // A turn captured mid-stream can end inside an open fence; drop the dangling remainder too.
      .replace(UNTERMINATED_FENCE, " ")
      .replace(IMAGE, " ")
      .replace(LINK, "$1")
      .replace(RULE, " ")
      .replace(TABLE_DIVIDER, " ")
      .replace(HEADING, "")
      .replace(BLOCKQUOTE, "")
      .replace(BULLET, "")
      .replace(ORDERED, "")
      .replace("|", ", ")
      .replace("`", "")
      .replace(BOLD_ITALIC, "")
      .replace(WHITESPACE, " ")
      // Punctuation cleanup runs last, once the text is a single collapsed line.
      .replace(SPACE_BEFORE_PUNCT, "$1")
      .replace(REPEATED_COMMA, ", ")
      .replace(EDGE_COMMA, "")
      .trim()

  return truncateForSpeech(stripped, maxChars)
}

/**
 * Cap [text] at [maxChars] on the friendliest boundary available — a sentence end if there is one in
 * the last quarter of the budget, otherwise a word boundary, otherwise a hard cut. Cutting
 * mid-syllable is the one artifact a listener always notices.
 */
fun truncateForSpeech(text: String, maxChars: Int = SPEECH_TEXT_MAX_CHARS): String {
  if (maxChars <= 0) return ""
  if (text.length <= maxChars) return text
  val window = text.substring(0, maxChars)

  val sentenceEnd = window.indexOfLast { it == '.' || it == '!' || it == '?' }
  if (sentenceEnd >= maxChars * 3 / 4) return window.substring(0, sentenceEnd + 1).trim()

  val wordEnd = window.lastIndexOf(' ')
  return if (wordEnd > 0) window.substring(0, wordEnd).trim() else window.trim()
}
