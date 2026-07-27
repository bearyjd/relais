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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for markdown→speech reduction backing in-app chat playback (#211). */
class SpeechTextTest {

  // ---- markdown stripping ----

  @Test fun `plain prose passes through unchanged`() {
    assertEquals("The node is live.", speakableText("The node is live."))
  }

  @Test fun `bold and italic markers are stripped but words survive`() {
    assertEquals("The node is live now.", speakableText("The **node** is *live* now."))
  }

  @Test fun `fenced code blocks are dropped entirely`() {
    val md = "Run this:\n\n```bash\ncurl -s localhost:8080/health\n```\n\nThen check the light."
    val spoken = speakableText(md)
    assertFalse(spoken.contains("curl"))
    assertEquals("Run this: Then check the light.", spoken)
  }

  @Test fun `an unterminated fence from a mid-stream turn drops its remainder`() {
    val spoken = speakableText("Here you go:\n\n```kotlin\nval x = 1\nval y = 2")
    assertEquals("Here you go:", spoken)
  }

  @Test fun `inline code keeps its content and loses only the backticks`() {
    assertEquals("Call RelaisEngine directly.", speakableText("Call `RelaisEngine` directly."))
  }

  @Test fun `link text is spoken and the URL is not`() {
    val spoken = speakableText("See [the docs](https://example.com/very/long/path).")
    assertEquals("See the docs.", spoken)
  }

  @Test fun `images are dropped entirely`() {
    assertEquals("Before after", speakableText("Before ![a diagram](x.png) after"))
  }

  @Test fun `headings lose their hashes`() {
    assertEquals("Setup Start the node.", speakableText("## Setup\n\nStart the node."))
  }

  @Test fun `list markers are dropped but items are kept`() {
    assertEquals("one two three", speakableText("- one\n- two\n- three"))
  }

  @Test fun `ordered list markers are dropped`() {
    assertEquals("first second", speakableText("1. first\n2. second"))
  }

  @Test fun `blockquote markers are dropped`() {
    assertEquals("quoted line", speakableText("> quoted line"))
  }

  @Test fun `horizontal rules are dropped`() {
    assertEquals("above below", speakableText("above\n\n---\n\nbelow"))
  }

  @Test fun `table pipes become commas without stray edge or spaced punctuation`() {
    // Regression: a bare `|` → `, ` swap used to yield ", a , b ," — leading/trailing commas and a
    // space before each one, which Piper voices as bare pauses around every row.
    assertEquals("a, b", speakableText("| a | b |"))
  }

  @Test fun `table alignment rows are dropped entirely`() {
    // The divider row vanishes; the comma between "size" and "voice" is the row boundary, which is
    // wanted — it gives the voice a pause between rows instead of running them together.
    val md = "| name | size |\n|---|---:|\n| voice | 64 MB |"
    assertEquals("name, size, voice, 64 MB", speakableText(md))
  }

  @Test fun `a table divider with colons and spaces is also dropped`() {
    assertEquals("a", speakableText("| :--- | ---: |\na"))
  }

  @Test fun `repeated commas from empty table cells collapse`() {
    assertEquals("a, b", speakableText("| a | | b |"))
  }

  @Test fun `snake_case identifiers keep their underscores`() {
    // Deliberate non-rule: `_` is not treated as emphasis — see SpeechText.kt's KDoc.
    assertEquals("the relais_engine field", speakableText("the relais_engine field"))
  }

  @Test fun `whitespace is collapsed`() {
    assertEquals("a b", speakableText("a\n\n\n   b"))
  }

  @Test fun `a code-only turn yields nothing speakable`() {
    assertEquals("", speakableText("```\nval x = 1\n```"))
  }

  @Test fun `blank input yields blank output`() {
    assertEquals("", speakableText("   \n  "))
  }

  // ---- truncation ----

  @Test fun `text under the cap is untouched`() {
    assertEquals("short", truncateForSpeech("short", 100))
  }

  @Test fun `truncation prefers a late sentence boundary`() {
    val text = "One two three four five. " + "x".repeat(50)
    assertEquals("One two three four five.", truncateForSpeech(text, 30))
  }

  @Test fun `truncation falls back to a word boundary when no late sentence end exists`() {
    // The only '.' is at index 2 — far below the 3/4 threshold of a 20-char budget, so the sentence
    // rule must NOT fire and the cut lands on whitespace instead.
    val out = truncateForSpeech("ab. cdef ghij klmn opqr stuv", 20)
    assertEquals("ab. cdef ghij klmn", out)
    assertFalse(out.endsWith(" "))
  }

  @Test fun `truncation hard-cuts when there is no boundary at all`() {
    assertEquals("aaaaa", truncateForSpeech("a".repeat(20), 5))
  }

  @Test fun `a non-positive cap yields empty`() {
    assertEquals("", truncateForSpeech("anything", 0))
  }

  @Test fun `speakableText applies the cap`() {
    val long = "word ".repeat(1000)
    assertTrue(speakableText(long).length <= SPEECH_TEXT_MAX_CHARS)
  }
}
