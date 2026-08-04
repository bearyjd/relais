/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Relais.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The URL-keyed half of the #220 compat gate — the seam the **legacy upstream download stack** uses.
 *
 * ### Why this exists
 * The provisioner gates ([RelaisModelProvisioner.ensureModel] / `.resolveModel`) cover the node's
 * own lane. They do not cover upstream Gallery's lane, which never calls them: every `MainActivity`
 * launch runs `ModelManagerViewModel.loadModelAllowlist()`, whose `processPendingDownloads()` calls
 * `DownloadRepository.downloadModel` **directly** to resume any `PARTIALLY_DOWNLOADED` model. That
 * view model reads the RAW allowlist — it never consults [RelaisModelCatalog] — so a device holding
 * a partial pre-#220 `Qwen2.5-1.5B` download resumed that multi-GB transfer on every cold start, for
 * a model the engine can never create against.
 *
 * [Model] carries no model id, only a display name and a download URL, so the gate at that layer has
 * to recover the repo id from the URL. These tests pin that recovery, because a parser that silently
 * returns null turns the gate into a no-op that still looks installed.
 */
class RelaisDownloadCompatGateTest {

  private val knownBad = "litert-community/Qwen2.5-1.5B-Instruct"

  @Test
  fun `recovers the repo id from a real allowlist download url`() {
    // Exactly the shape AllowedModel.toModel() builds.
    val url = "https://huggingface.co/$knownBad/resolve/abc123/model.litertlm?download=true"
    assertEquals(knownBad, RelaisRuntimeCompat.repoIdFromDownloadUrl(url))
  }

  @Test
  fun `refuses the known-bad model by its download url alone`() {
    val url = "https://huggingface.co/$knownBad/resolve/abc123/model.litertlm?download=true"
    val why = RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(url)
    assertNotNull("the legacy download lane must refuse the measured failure", why)
    assertEquals(RelaisRuntimeCompat.incompatibleReason(knownBad), why)
  }

  @Test
  fun `allows a verified model's download url`() {
    val url =
      "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/361a401/g.litertlm"
    assertNull(RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(url))
  }

  @Test
  fun `an unidentifiable url is ALLOWED, never blocked`() {
    // Only MEASURED failures are withheld. A URL we cannot key on must fall through, or a custom or
    // self-hosted model would be blocked by a table that has no opinion about it.
    listOf(
        "https://example.com/some/model.litertlm", // not HF
        "https://huggingface.co/just-an-owner", // no repo, no /resolve/
        "https://huggingface.co/a/b/c/resolve/x/f.bin", // nested path, not owner/repo
        "not a url at all",
        "",
      )
      .forEach { url ->
        assertNull("must not block an unidentifiable url: $url", RelaisRuntimeCompat.repoIdFromDownloadUrl(url))
        assertNull(
          "must not block an unidentifiable url: $url",
          RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(url),
        )
      }
  }

  @Test
  fun `the parser is not fooled by a lookalike host`() {
    // huggingface.co.evil.example would otherwise substring-match a naive contains() check and let
    // an attacker-shaped URL be keyed as a trusted repo id.
    assertNull(
      RelaisRuntimeCompat.repoIdFromDownloadUrl(
        "https://huggingface.co.evil.example/$knownBad/resolve/abc/f.bin"
      )
    )
  }

  /**
   * Host case must not decide whether the gate engages.
   *
   * Hosts are case-insensitive (RFC 3986 §3.2.2), so `HuggingFace.co` is the same host — but an
   * exact `!=` compare keyed it as unidentifiable, and unidentifiable means **allow**. That is the
   * one direction where the fail-open default is wrong: it silently converts the gate into a no-op
   * for a measured-incompatible model. Not reachable from `AllowedModel.toModel()`, which builds the
   * host as a lowercase literal, but an `AllowedModel.url` override in the upstream allowlist JSON
   * can carry any spelling.
   */
  @Test
  fun `host casing does not decide whether the gate engages`() {
    val url = "https://HuggingFace.CO/$knownBad/resolve/abc123/model.litertlm"

    // The repo id must come back with its OWN case intact: HF repo ids are case-sensitive and the
    // compat table is keyed on the exact id, so lowercasing the whole URL would "fix" the host and
    // break the lookup — a gate that engages and then matches nothing.
    assertEquals(knownBad, RelaisRuntimeCompat.repoIdFromDownloadUrl(url))
    assertNotNull(
      "a measured-incompatible model must be refused regardless of host casing",
      RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(url),
    )
  }

  /**
   * Siblings of the casing bug: everything else the URL *authority* can legally carry.
   *
   * `substringBefore('/')` returns the whole authority, not the host — so an explicit port or a
   * userinfo prefix made the compare fail and the URL read as unidentifiable, which means ALLOW.
   * Each of these is the same silent no-op as the casing case, reached the same way (an
   * `AllowedModel.url` override in the upstream allowlist JSON).
   */
  @Test
  fun `port and userinfo in the authority do not hide the host`() {
    listOf(
        "https://huggingface.co:443/$knownBad/resolve/abc/f.litertlm",
        "https://user@huggingface.co/$knownBad/resolve/abc/f.litertlm",
        "https://user:pw@HuggingFace.co:443/$knownBad/resolve/abc/f.litertlm",
      )
      .forEach { url ->
        assertEquals("host must be recovered from: $url", knownBad, RelaisRuntimeCompat.repoIdFromDownloadUrl(url))
        assertNotNull(
          "a measured-incompatible model must be refused regardless of authority shape: $url",
          RelaisRuntimeCompat.incompatibleReasonForDownloadUrl(url),
        )
      }
  }

  /**
   * The flip side, and the reason this cannot just strip everything before an `@`: userinfo that
   * *looks* like the trusted host must not be mistaken for it. `huggingface.co@evil.example` has
   * host `evil.example` and must stay unidentifiable (→ allowed, since it is not an HF repo URL).
   */
  @Test
  fun `a trusted-looking userinfo does not impersonate the host`() {
    assertNull(
      RelaisRuntimeCompat.repoIdFromDownloadUrl(
        "https://huggingface.co@evil.example/$knownBad/resolve/abc/f.bin"
      )
    )
  }
}
