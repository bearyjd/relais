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

import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.grepon.relais.RelaisHuggingFace.HfInfo
import cc.grepon.relais.RelaisHuggingFace.HfTreeEntry
import cc.grepon.relais.RelaisHuggingFace.InfoStep
import cc.grepon.relais.RelaisHuggingFace.ResolveResult
import cc.grepon.relais.common.HttpJsonResult
import cc.grepon.relais.common.JsonObjAndTextContent
import cc.grepon.relais.data.RelaisModelRef
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HuggingFace resolve guard. Drives the network-free seam [RelaisHuggingFace.buildRef] (plus
 * [RelaisHuggingFace.isGated]) with committed fixtures shaped like real Hub responses, so the
 * file-pick + commit-pin + size-extraction logic is locked independently of the live API. Hermetic:
 * parses JSON with Gson, never touches the network.
 *
 * Also pins the DTO null-tolerance contract: Gson does not honor Kotlin non-null types, so a partial
 * response must decode to nulls and degrade to an Error/NoLiteRtLm result — never throw the non-null
 * intrinsic NPE (the class of bug that bit [RelaisModelRef.fromJson]).
 */
@RunWith(AndroidJUnit4::class)
class RelaisHuggingFaceTest {

  private val gson = Gson()

  private fun info(json: String): HfInfo = gson.fromJson(json, HfInfo::class.java)

  private fun tree(json: String): List<HfTreeEntry> =
    gson.fromJson(json, Array<HfTreeEntry>::class.java).toList()

  @Test
  fun buildRefPicksLiteRtLmPinsCommitAndPrefersLfsSize() {
    val info =
      info(
        """{"sha":"abc123","gated":false,
            "siblings":[{"rfilename":"README.md"},{"rfilename":"model.litertlm"},{"rfilename":"config.json"}]}"""
      )
    // The .litertlm file is a multi-GB LFS object: its true byte count is lfs.size, not the size stub.
    val tree =
      tree(
        """[{"path":"model.litertlm","size":135,"lfs":{"size":3600000000}},
            {"path":"README.md","size":42}]"""
      )

    val result = RelaisHuggingFace.buildRef("litert-community/demo-litert-lm", info, tree)

    assertTrue(result is ResolveResult.Success)
    val ref = (result as ResolveResult.Success).ref
    assertEquals("litert-community/demo-litert-lm", ref.modelId)
    assertEquals("model.litertlm", ref.modelFile)
    assertEquals("abc123", ref.commitHash)
    assertEquals("lfs.size is the true byte count", 3_600_000_000L, ref.sizeInBytes)
    assertEquals("demo-litert-lm", ref.displayName)
    assertEquals(RelaisModelRef.SOURCE_HUGGINGFACE, ref.source)
  }

  @Test
  fun buildRefFallsBackToPlainSizeWhenNoLfs() {
    val info = info("""{"sha":"c0ffee","siblings":[{"rfilename":"small.litertlm"}]}""")
    val tree = tree("""[{"path":"small.litertlm","size":5000}]""")

    val ref = (RelaisHuggingFace.buildRef("acme/small", info, tree) as ResolveResult.Success).ref
    assertEquals(5000L, ref.sizeInBytes)
  }

  @Test
  fun buildRefSizeUnknownWhenTreeMissingTheFile() {
    // Sibling lists the file but the (best-effort) tree fetch returned nothing → size is -1, which
    // the download tolerates (it streams to EOF; only the progress % degrades).
    val info = info("""{"sha":"deadbeef","siblings":[{"rfilename":"m.litertlm"}]}""")

    val ref = (RelaisHuggingFace.buildRef("acme/notree", info, emptyList()) as ResolveResult.Success).ref
    assertEquals("m.litertlm", ref.modelFile)
    assertEquals(-1L, ref.sizeInBytes)
  }

  @Test
  fun buildRefPicksDeterministicallyAmongMultipleLiteRtLm() {
    val info =
      info(
        """{"sha":"s1","siblings":[{"rfilename":"q8-model.litertlm"},{"rfilename":"a-model.litertlm"},{"rfilename":"m.litertlm"}]}"""
      )
    val ref = (RelaisHuggingFace.buildRef("acme/multi", info, emptyList()) as ResolveResult.Success).ref
    // Shortest name → the canonical build (suffixed variants are longer); deterministic run-to-run.
    assertEquals("m.litertlm", ref.modelFile)
  }

  @Test
  fun pickPrefersCanonicalBuildAndRejectsSubdirectories() {
    // '-' (0x2D) sorts before '.' (0x2E), so a plain lexicographic pick would grab the "-web" variant
    // — shortest-then-lexicographic picks the canonical base file the node actually runs.
    assertEquals(
      "gemma-4-E4B-it.litertlm",
      RelaisHuggingFace.pickLiteRtLm(listOf("gemma-4-E4B-it-web.litertlm", "gemma-4-E4B-it.litertlm")),
    )
    // A nested .litertlm is rejected (DownloadWorker can't write into an un-mkdir'd subdir) → the
    // top-level file wins, or null when only nested ones exist (→ resolve returns NoLiteRtLm).
    assertEquals("root.litertlm", RelaisHuggingFace.pickLiteRtLm(listOf("weights/deep.litertlm", "root.litertlm")))
    assertNull(RelaisHuggingFace.pickLiteRtLm(listOf("nested/only.litertlm")))
    // Equal length → lexicographic tiebreak keeps it deterministic.
    assertEquals("a.litertlm", RelaisHuggingFace.pickLiteRtLm(listOf("b.litertlm", "a.litertlm")))
  }

  @Test
  fun buildRefFallsBackToTreeFileListWhenSiblingsAbsent() {
    // No siblings on the model-info: the file list (and size) must come from the repo tree instead.
    val info = info("""{"sha":"treesha"}""")
    val tree =
      tree(
        """[{"path":"docs/readme.md","size":10},
            {"path":"weights.litertlm","size":7,"lfs":{"size":2900000000}}]"""
      )

    val ref = (RelaisHuggingFace.buildRef("someone/tree-only", info, tree) as ResolveResult.Success).ref
    assertEquals("weights.litertlm", ref.modelFile)
    assertEquals(2_900_000_000L, ref.sizeInBytes)
    assertEquals("treesha", ref.commitHash)
  }

  @Test
  fun buildRefReturnsNoLiteRtLmWhenRepoHasNone() {
    val info =
      info("""{"sha":"x","siblings":[{"rfilename":"README.md"},{"rfilename":"pytorch_model.bin"}]}""")
    val tree = tree("""[{"path":"README.md","size":1},{"path":"pytorch_model.bin","size":2}]""")

    assertEquals(ResolveResult.NoLiteRtLm, RelaisHuggingFace.buildRef("org/not-litert", info, tree))
  }

  @Test
  fun buildRefReturnsErrorWhenCommitMissing() {
    // No sha → can't pin a stable URL/version dir. Must be a clean Error, not a crash.
    val info = info("""{"siblings":[{"rfilename":"m.litertlm"}]}""")
    assertTrue(RelaisHuggingFace.buildRef("org/no-sha", info, emptyList()) is ResolveResult.Error)
  }

  @Test
  fun dtosAreNullTolerant() {
    // Empty / garbage objects decode to all-null fields (Gson ignores Kotlin non-null types) and must
    // degrade gracefully rather than throw. An all-null HfInfo has no sha → Error.
    assertTrue(RelaisHuggingFace.buildRef("org/empty", info("{}"), emptyList()) is ResolveResult.Error)
    // A tree of partial entries (missing path/size/lfs) must not crash the size lookup.
    val info = info("""{"sha":"s","siblings":[{"rfilename":"m.litertlm"}]}""")
    val partialTree = tree("""[{"size":1},{"path":"m.litertlm"},{"lfs":{}}]""")
    val ref = (RelaisHuggingFace.buildRef("org/partial", info, partialTree) as ResolveResult.Success).ref
    assertEquals("m.litertlm", ref.modelFile)
    assertEquals("no usable size in the tree entry → -1", -1L, ref.sizeInBytes)
  }

  @Test
  fun isGatedRecognizesTriStateAndCoercedBoolean() {
    // String forms from the wire.
    assertTrue(RelaisHuggingFace.isGated("manual"))
    assertTrue(RelaisHuggingFace.isGated("auto"))
    assertFalse(RelaisHuggingFace.isGated("false"))
    assertFalse(RelaisHuggingFace.isGated(null))
    // HF also sends `gated` as a JSON boolean; Gson coerces it into the String? field as "true"/"false".
    assertFalse("boolean false decodes to not-gated", RelaisHuggingFace.isGated(info("""{"gated":false}""").gated))
    assertTrue("boolean true decodes to gated", RelaisHuggingFace.isGated(info("""{"gated":true}""").gated))
    assertTrue("string \"manual\" is gated", RelaisHuggingFace.isGated(info("""{"gated":"manual"}""").gated))
  }

  // ---- interpretInfo: the HTTP-status → ResolveResult mapping (resolve's network arms) -----------
  // Hermetic stand-in for a MockWebServer test: drives the pure mapping with constructed
  // HttpJsonResult values so the gated/error/offline branches are pinned without a live server.

  private fun okInfo(json: String): HttpJsonResult<HfInfo> =
    HttpJsonResult.Ok(JsonObjAndTextContent(jsonObj = info(json), textContent = json))

  @Test
  fun interpretInfo401And403MapToGated() {
    assertEquals(
      InfoStep.Stop(ResolveResult.Gated),
      RelaisHuggingFace.interpretInfo("org/x", null, HttpJsonResult.HttpError(401)),
    )
    assertEquals(
      InfoStep.Stop(ResolveResult.Gated),
      RelaisHuggingFace.interpretInfo("org/x", "tok", HttpJsonResult.HttpError(403)),
    )
  }

  @Test
  fun interpretInfoOtherHttpErrorsMapToErrorWithCode() {
    val r404 = RelaisHuggingFace.interpretInfo("org/missing", null, HttpJsonResult.HttpError(404))
    assertTrue(r404 is InfoStep.Stop && r404.result is ResolveResult.Error)
    assertTrue(
      "404 message carries the code",
      ((r404 as InfoStep.Stop).result as ResolveResult.Error).message.contains("404"),
    )
    val r500 = RelaisHuggingFace.interpretInfo("org/x", null, HttpJsonResult.HttpError(500))
    assertTrue(r500 is InfoStep.Stop && r500.result is ResolveResult.Error)
  }

  @Test
  fun interpretInfoTransportMapsToOfflineError() {
    val r = RelaisHuggingFace.interpretInfo("org/x", null, HttpJsonResult.Transport)
    assertTrue(r is InfoStep.Stop && r.result is ResolveResult.Error)
  }

  @Test
  fun interpretInfoOkGatedWithoutTokenStopsButWithTokenProceeds() {
    // 200 + gated metadata + no token → stop early with Gated (pre-empts a later download 401).
    assertEquals(
      InfoStep.Stop(ResolveResult.Gated),
      RelaisHuggingFace.interpretInfo("org/g", null, okInfo("""{"sha":"s","gated":"manual"}""")),
    )
    // Same repo but a token is set → proceed (the token presumably grants access).
    val withTok = RelaisHuggingFace.interpretInfo("org/g", "tok", okInfo("""{"sha":"s","gated":"manual"}"""))
    assertTrue(withTok is InfoStep.Proceed)
  }

  @Test
  fun interpretInfoOkNotGatedProceeds() {
    val r = RelaisHuggingFace.interpretInfo("org/open", null, okInfo("""{"sha":"s","gated":false}"""))
    assertTrue(r is InfoStep.Proceed)
    assertEquals("s", (r as InfoStep.Proceed).info.sha)
  }
}
