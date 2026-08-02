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
}
