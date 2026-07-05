/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the Experiments control panel renderer ([renderExperimentsHtml]).
 * No Context, no Android — pure functions in, strings out.
 */
class RelaisExperimentsTest {

  private val nonce = "dGVzdC1ub25jZQ=="

  private fun status(
    engineReady: Boolean = true,
    startupInProgress: Boolean = false,
    modelId: String = "gemma-3n-e4b",
    capabilities: String = "multimodal,tools,reasoning",
  ) = assembleExperimentsStatus(engineReady, startupInProgress, modelId, capabilities)

  // ---- assembler: status mapping mirrors the dashboard (DESIGN.md) ----

  @Test
  fun `assembleExperimentsStatus maps engineReady to LIVE`() {
    val s = status(engineReady = true, startupInProgress = false)
    assertEquals("LIVE", s.statusLabel)
    assertTrue(s.live)
  }

  @Test
  fun `assembleExperimentsStatus maps startup in progress to STARTING`() {
    val s = status(engineReady = false, startupInProgress = true)
    assertEquals("STARTING", s.statusLabel)
    assertFalse(s.live)
  }

  @Test
  fun `assembleExperimentsStatus maps idle to OFFLINE`() {
    val s = status(engineReady = false, startupInProgress = false)
    assertEquals("OFFLINE", s.statusLabel)
    assertFalse(s.live)
  }

  // ---- palette guards (DESIGN.md: amber-on-charcoal, no new hues) ----

  @Test
  fun `renderExperimentsHtml declares the DESIGN palette tokens`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("signal amber missing", html.contains("#FFB000"))
    assertTrue("charcoal background missing", html.contains("#0B0B0D"))
    assertTrue("surface missing", html.contains("#16171A"))
    assertTrue("hairline missing", html.contains("#2A2B30"))
    assertTrue("paper text missing", html.contains("#EDEAE3"))
    assertTrue("muted missing", html.contains("#8A8780"))
    assertTrue("monospace missing", html.contains("monospace"))
  }

  @Test
  fun `renderExperimentsHtml uses no off-palette hues`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertFalse("stop red is Stop-button-only, never on this page", html.contains("#FF5247"))
    assertFalse("off-palette warn hue", html.contains("#FFCC44"))
  }

  @Test
  fun `link result starts as the muted quiet baseline`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("""<td class="value muted" id="link-result">not verified</td>"""))
  }

  // ---- status beacon mapping ----

  @Test
  fun `beacon is full amber when LIVE and pulses`() {
    val html = renderExperimentsHtml(status(engineReady = true), nonce)
    assertTrue(html.contains("background: #FFB000;"))
    assertTrue(html.contains("beacon dot-pulse"))
  }

  @Test
  fun `beacon is amber 60 when STARTING and muted when OFFLINE, no pulse`() {
    val starting = renderExperimentsHtml(status(engineReady = false, startupInProgress = true), nonce)
    assertTrue(starting.contains("background: rgba(255,176,0,0.6);"))
    assertFalse(starting.contains("dot-pulse\"></span>"))
    val offline = renderExperimentsHtml(status(engineReady = false, startupInProgress = false), nonce)
    assertTrue(offline.contains("background: #8A8780;"))
  }

  // ---- script hygiene: nonce on every script, no storage, no key echo ----

  @Test
  fun `every script tag carries the CSP nonce`() {
    val html = renderExperimentsHtml(status(), nonce)
    val scriptCount = Regex("<script").findAll(html).count()
    val noncedCount = Regex("""<script nonce="${Regex.escape(nonce)}">""").findAll(html).count()
    assertTrue("page must contain at least one script", scriptCount > 0)
    assertEquals("every <script> must carry the request nonce", scriptCount, noncedCount)
  }

  @Test
  fun `nonce is HTML-escaped before interpolation`() {
    val html = renderExperimentsHtml(status(), "abc\"><script>alert(1)</script>")
    assertFalse(html.contains("\"><script>alert(1)</script>"))
    assertTrue(html.contains("abc&quot;&gt;&lt;script&gt;"))
  }

  @Test
  fun `api key stays in memory only`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("key field must be a password input", html.contains("""<input type="password" id="api-key""""))
    assertTrue("no autofill of the node key", html.contains("""autocomplete="off""""))
    assertFalse("never persist the key", html.contains("localStorage"))
    assertFalse("never persist the key", html.contains("sessionStorage"))
    assertFalse("never cookie the key", html.contains("document.cookie"))
  }

  // ---- endpoint wiring ----

  @Test
  fun `verify link calls v1 models with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/models'"))
    assertTrue(html.contains("'Authorization': 'Bearer ' + key"))
  }

  // ---- XSS guard: dynamic values are escaped ----

  @Test
  fun `model id and capabilities are HTML-escaped`() {
    val payload = "</td><script>alert('x')</script>"
    val html = renderExperimentsHtml(
      status(modelId = payload, capabilities = payload),
      nonce,
    )
    assertFalse(html.contains(payload))
    assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
  }

  // ---- field pass-through ----

  @Test
  fun `model id and capabilities render into the Node panel`() {
    val html = renderExperimentsHtml(status(modelId = "gemma-3n-e4b", capabilities = "tools,reasoning"), nonce)
    assertTrue(html.contains("gemma-3n-e4b"))
    assertTrue(html.contains("tools,reasoning"))
    assertTrue(html.contains("RELAIS"))
    assertTrue(html.contains("EXPERIMENTS"))
  }

  // ---- audio transcription module ----

  @Test
  fun `audio module renders a file picker and transcribe control`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue("audio file input", html.contains("""<input type="file" accept="audio/*" id="audio-file">"""))
    assertTrue("transcribe button", html.contains("""id="transcribe""""))
    assertTrue("result starts as the muted idle baseline",
      html.contains("""<div class="result muted" id="transcribe-result">idle</div>"""))
  }

  @Test
  fun `audio module posts multipart to the transcriptions endpoint with a bearer header`() {
    val html = renderExperimentsHtml(status(), nonce)
    assertTrue(html.contains("'/v1/audio/transcriptions'"))
    assertTrue("uses FormData so the browser sets the multipart boundary", html.contains("new FormData()"))
    assertTrue("file field name is 'file'", html.contains("fd.append('file', file)"))
    assertTrue("sends the model id", html.contains("fd.append('model'"))
    assertTrue(html.contains("'Authorization': 'Bearer ' + key"))
  }

  @Test
  fun `audio module never sets a Content-Type header manually`() {
    val html = renderExperimentsHtml(status(), nonce)
    // A manual multipart Content-Type would omit the boundary and break the upload; the fetch must
    // let the browser set it. Guard on the header KEY the fetch would use, not prose in comments.
    assertFalse("browser must set Content-Type + boundary itself", html.contains("'Content-Type'"))
  }
}
