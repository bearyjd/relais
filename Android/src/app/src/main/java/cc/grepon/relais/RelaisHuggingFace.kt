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

import android.util.Log
import cc.grepon.relais.common.HttpJsonResult
import cc.grepon.relais.common.getJsonResponseAuthed
import cc.grepon.relais.data.RelaisModelRef
import com.google.gson.annotations.SerializedName
import java.net.URLEncoder

private const val TAG = "RelaisHuggingFace"

/** HuggingFace Hub API base. The same host [RelaisModelRef] builds resolve URLs against. */
private const val API_BASE = "https://huggingface.co"

/** Keep result lists modest — the selector shows them in a bottom sheet, not a paged screen. */
private const val SEARCH_LIMIT = 25

/**
 * The models-list endpoint omits `gated` (and would omit `downloads`/`likes`) unless explicitly
 * expanded, so the selector's gated badge / download count stay null without this. Verified against
 * the live API: without it every hit's `gated` is null; with it, gated repos report "manual"/"auto".
 */
private const val SEARCH_EXPAND = "&expand[]=gated&expand[]=downloads&expand[]=likes"

/**
 * Minimal HuggingFace Hub client for the model selector: free-text [search] over node-candidate
 * repos and [resolve] of a repo id into a self-contained [RelaisModelRef] (commit + `.litertlm`
 * file + size) the node can provision without the allowlist.
 *
 * Self-contained and headless-safe:
 * - **No `huggingface_hub` SDK** — three plain GETs through [getJsonResponseAuthed] (timeouts +
 *   optional Bearer), mirroring the rest of the node's transport.
 * - **DTOs are null-tolerant.** Gson fills fields by reflection and does NOT honor Kotlin non-null
 *   types, so every wire field is declared nullable and validated — a partial/garbage response
 *   decodes to nulls and degrades gracefully instead of throwing the non-null intrinsic NPE (the
 *   exact class of bug that bit [RelaisModelRef.fromJson]).
 * - **Network and logic are split** ([resolve] vs the pure [buildRef]) so the file-pick + size
 *   extraction is unit-testable against committed fixtures with no network, like
 *   [RelaisModelCatalog.curatedModelsFrom].
 *
 * All entry points are **blocking** — call off the main thread (the selector uses `Dispatchers.IO`).
 */
object RelaisHuggingFace {

  // ---- Wire DTOs (null-tolerant; see class KDoc) ------------------------------------------------

  /** A models-list hit. `gated` is HF's tri-state: `false` (boolean) | `"auto"` | `"manual"`. */
  data class HfHit(
    @SerializedName("id") val id: String?,
    @SerializedName("downloads") val downloads: Long?,
    @SerializedName("likes") val likes: Long?,
    @SerializedName("gated") val gated: String?,
  )

  /** Model-info: `sha` is the latest commit on the default branch; `siblings` lists repo files. */
  data class HfInfo(
    @SerializedName("sha") val sha: String?,
    @SerializedName("gated") val gated: String?,
    @SerializedName("siblings") val siblings: List<HfSibling>?,
  )

  data class HfSibling(@SerializedName("rfilename") val rfilename: String?)

  /** A repo-tree entry. For a multi-GB LFS file the true byte count is in [lfs]; [size] is a stub. */
  data class HfTreeEntry(
    @SerializedName("path") val path: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("lfs") val lfs: HfLfs?,
  )

  data class HfLfs(@SerializedName("size") val size: Long?)

  // ---- Resolve outcome --------------------------------------------------------------------------

  /** The outcome of resolving a repo id; the selector maps each arm to an inline message or a pick. */
  sealed interface ResolveResult {
    /** Resolved to a provisionable ref. */
    data class Success(val ref: RelaisModelRef) : ResolveResult

    /** Repo is license-gated and the request had no (or an unauthorized) token — set the HF token. */
    object Gated : ResolveResult

    /** Repo exists but exposes no `.litertlm` file — it isn't a node-runnable LiteRT-LM model. */
    object NoLiteRtLm : ResolveResult

    /** Couldn't resolve for another reason (offline, repo not found, no commit, parse failure). */
    data class Error(val message: String) : ResolveResult
  }

  // ---- Public API -------------------------------------------------------------------------------

  /**
   * Searches the Hub for candidate repos, most-downloaded first. A blank [query] biases to the
   * `litert-community` org (the known source of node-runnable LiteRT-LM builds) instead of spamming
   * the API with an empty search. Returns hits with a usable id; [emptyList] on any failure (the
   * selector then shows "no results / offline"). Resolve-on-tap is what validates a `.litertlm`.
   */
  fun search(query: String, token: String?): List<HfHit> {
    val q = query.trim()
    val url =
      if (q.isEmpty()) {
        "$API_BASE/api/models?author=litert-community&sort=downloads&direction=-1&limit=$SEARCH_LIMIT$SEARCH_EXPAND"
      } else {
        "$API_BASE/api/models?search=${URLEncoder.encode(q, "UTF-8")}" +
          "&sort=downloads&direction=-1&limit=$SEARCH_LIMIT$SEARCH_EXPAND"
      }
    return when (val r = getJsonResponseAuthed<Array<HfHit>>(url, token)) {
      is HttpJsonResult.Ok -> r.body.jsonObj.toList().filter { !it.id.isNullOrBlank() }
      is HttpJsonResult.HttpError -> {
        Log.w(TAG, "HF search '$q' returned HTTP ${r.code}")
        emptyList()
      }
      HttpJsonResult.Transport -> emptyList()
    }
  }

  /**
   * Resolves [modelId] into a [RelaisModelRef] via two round-trips (model-info, then the repo tree
   * for the file size). Best-effort on size — a tree-fetch failure yields `-1` (download streams to
   * EOF regardless; only the progress % degrades). [token] is the operator's HF token, sent as a
   * Bearer for gated repos. Blocking.
   */
  fun resolve(modelId: String, token: String?): ResolveResult {
    // Encode each path segment (keep the org/repo slash) so a pasted id with ?/#/space can't alter
    // the API request target. Real HF ids are unaffected; this only neutralizes garbage input.
    val infoUrl = "$API_BASE/api/models/${encodeRepoPath(modelId)}"
    val info =
      when (val step = interpretInfo(modelId, token, getJsonResponseAuthed<HfInfo>(infoUrl, token))) {
        is InfoStep.Proceed -> step.info
        is InfoStep.Stop -> return step.result
      }
    val sha = info.sha?.takeIf { it.isNotBlank() }
    val tree = if (sha != null) fetchTree(modelId, sha, token) else emptyList()
    return buildRef(modelId, info, tree)
  }

  /** Whether HF's tri-state `gated` flag (`false` | `"auto"` | `"manual"`, or a JSON `true`) is on. */
  fun isGated(gated: String?): Boolean = gated != null && !gated.equals("false", ignoreCase = true)

  // ---- Pure logic (network-free seam exercised by tests) ----------------------------------------

  /** Whether the model-info step yielded a usable [HfInfo] to continue with, or an early result. */
  internal sealed interface InfoStep {
    data class Proceed(val info: HfInfo) : InfoStep
    data class Stop(val result: ResolveResult) : InfoStep
  }

  /**
   * Maps the model-info fetch outcome to either the parsed [HfInfo] (continue) or an early
   * [ResolveResult] (stop). Pure — this is the HTTP-status → result mapping the network arms of
   * [resolve] hinge on, lifted out so it's hermetically testable without a live server:
   *  - 401/403 → [ResolveResult.Gated] (missing/insufficient token for a gated repo; the one
   *    status worth a specific message — everything else, 404/5xx, is a generic miss).
   *  - any other non-200 → [ResolveResult.Error] with the code.
   *  - transport failure → [ResolveResult.Error] "offline".
   *  - 200 whose public metadata says gated while we hold no token → [ResolveResult.Gated],
   *    pre-empting a later download 401 with no UI signal.
   *  - 200 otherwise → [InfoStep.Proceed].
   */
  internal fun interpretInfo(modelId: String, token: String?, r: HttpJsonResult<HfInfo>): InfoStep =
    when (r) {
      is HttpJsonResult.Ok -> {
        val info = r.body.jsonObj
        if (isGated(info.gated) && token.isNullOrBlank()) InfoStep.Stop(ResolveResult.Gated)
        else InfoStep.Proceed(info)
      }
      is HttpJsonResult.HttpError ->
        if (r.code == 401 || r.code == 403) InfoStep.Stop(ResolveResult.Gated)
        else InfoStep.Stop(ResolveResult.Error("HuggingFace returned HTTP ${r.code} for $modelId"))
      HttpJsonResult.Transport -> InfoStep.Stop(ResolveResult.Error("Couldn't reach HuggingFace (offline?)"))
    }

  /**
   * Builds a [ResolveResult] from already-fetched model-info + repo tree — no network, so tests
   * drive it with committed fixtures. Picks the `.litertlm` file (preferring the info `siblings`
   * list, falling back to the [tree] when siblings are absent), pins it to the repo `sha`, and
   * takes its byte size from the tree (`lfs.size`, else plain `size`, else `-1`).
   */
  internal fun buildRef(modelId: String, info: HfInfo, tree: List<HfTreeEntry>): ResolveResult {
    val sha = info.sha?.takeIf { it.isNotBlank() } ?: return ResolveResult.Error("No commit for $modelId")

    // Prefer the model-info sibling list; fall back to the tree's paths when siblings are absent.
    val siblingFiles = info.siblings.orEmpty().mapNotNull { it.rfilename?.takeIf(String::isNotBlank) }
    val candidateFiles =
      if (siblingFiles.isNotEmpty()) siblingFiles
      else tree.mapNotNull { it.path?.takeIf(String::isNotBlank) }

    val file = pickLiteRtLm(candidateFiles) ?: return ResolveResult.NoLiteRtLm

    // Size is best-effort: lfs.size is the real byte count for LFS files, size for small ones, and
    // -1 when the tree didn't list it (a wrong/absent size only skews the download progress %).
    val size = tree.firstOrNull { it.path == file }?.let { it.lfs?.size ?: it.size } ?: -1L

    return ResolveResult.Success(
      RelaisModelRef(
        modelId = modelId,
        modelFile = file,
        commitHash = sha,
        sizeInBytes = size,
        displayName = modelId.substringAfterLast('/'),
        source = RelaisModelRef.SOURCE_HUGGINGFACE,
      )
    )
  }

  /**
   * Picks one `.litertlm` file from [files], deterministically and biased toward the **canonical**
   * build. Returns null when none qualifies (the repo isn't a node-runnable LiteRT-LM model).
   *
   * Two rules learned the hard way:
   *  - **Top-level only** (`!contains('/')`): a nested path would make [DownloadWorker] open a file
   *    in a subdir it never mkdir'd and fail a multi-GB download deep in FileOutputStream. Reject it
   *    here so resolve returns [ResolveResult.NoLiteRtLm] instead of a doomed download.
   *  - **Shortest name, then lexicographic**: the base file is usually the shortest name; backend or
   *    quant variants add suffixes (`-web`, `-seq128`). A plain lexicographic pick is actively wrong
   *    — `'-'` (0x2D) sorts before `'.'` (0x2E), so it would pick `gemma-web.litertlm` over the
   *    canonical `gemma.litertlm`. Shortest-first picks the canonical file the node actually runs.
   */
  internal fun pickLiteRtLm(files: List<String>): String? =
    files
      .filter { it.endsWith(".litertlm", ignoreCase = true) && !it.contains('/') }
      .minWithOrNull(compareBy({ it.length }, { it }))

  // ---- Network helper ---------------------------------------------------------------------------

  /**
   * Fetches the repo tree root at [sha] for the picked file's size; [emptyList] on any failure (size
   * is best-effort). Non-recursive: [pickLiteRtLm] only accepts top-level files, so the root listing
   * already contains the entry whose size we need — no point pulling a fat repo's whole tree.
   */
  private fun fetchTree(modelId: String, sha: String, token: String?): List<HfTreeEntry> {
    val url = "$API_BASE/api/models/${encodeRepoPath(modelId)}/tree/$sha"
    return when (val r = getJsonResponseAuthed<Array<HfTreeEntry>>(url, token)) {
      is HttpJsonResult.Ok -> r.body.jsonObj.toList()
      else -> {
        Log.w(TAG, "HF tree fetch failed for $modelId@$sha; size will be unknown")
        emptyList()
      }
    }
  }

  /** Percent-encodes each path segment of a repo id, preserving the `org/repo` slash. */
  private fun encodeRepoPath(modelId: String): String =
    modelId.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}
