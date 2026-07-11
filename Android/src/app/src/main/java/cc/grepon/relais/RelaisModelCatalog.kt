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

import android.content.Context
import android.util.Log
import cc.grepon.relais.common.getJsonResponse
import cc.grepon.relais.common.isPixel10
import java.io.File
import kotlinx.coroutines.CancellationException
import cc.grepon.relais.data.AllowedModel
import cc.grepon.relais.data.BuiltInTaskId
import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.data.RuntimeType

private const val TAG = "RelaisModelCatalog"
private const val CURATED_TTL_MS = 5 * 60 * 1_000L // 5-minute TTL for the allowlist cache

/**
 * Curated model source for the selector: the same allowlist the provisioner resolves against,
 * filtered to models the node can actually serve, mapped to self-contained [RelaisModelRef]s.
 *
 * "Node-runnable" = a LiteRT-LM model exposing an LLM-chat task. AICore / classical-ML entries and
 * non-`.litertlm` files are dropped — the node only runs LiteRT-LM chat. Pure read of the public
 * allowlist; no curation lives here.
 */
object RelaisModelCatalog {

  /**
   * Pinned Google-Tensor-G5 AOT model refs (spike plan T-4 backlog): the upstream allowlist carries
   * no TPU-compiled entries, so these are Relais-curated, offered ONLY when [tpuLaneUsable]. They
   * provision via the ref fast path ([RelaisModelProvisioner.resolveModel]) — no allowlist entry
   * needed. Values pinned from the HF API (commit + LFS byte size) like
   * [RelaisModelProvisioner.G5_DEFAULT_REF], so selection needs no network.
   *
   * Filenames intentionally keep the upstream `…_Google_Tensor_G5` convention —
   * [RelaisTpuLane.isTpuCompiledModel] keys the engine's TPU lane on it.
   * NOTE: `Gemma3-1B-IT` is a Gemma-license-GATED repo — downloading it needs the operator's HF
   * token (same as the existing int4 entry); the E2B repo is ungated (Apache-2.0).
   */
  internal val G5_TPU_REFS =
    listOf(
      RelaisModelRef(
        modelId = "litert-community/gemma-4-E2B-it-litert-lm",
        modelFile = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        commitHash = "9262660a1676eed6d0c477ab1a86344430854664",
        sizeInBytes = 3_953_110_901L,
        displayName = "Gemma 4 E2B (TPU · Tensor G5)",
        source = RelaisModelRef.SOURCE_HUGGINGFACE,
      ),
      RelaisModelRef(
        modelId = "litert-community/Gemma3-1B-IT",
        modelFile = "Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm",
        commitHash = "6d54daa71cfbffba6b2843c08eeb1a27e7430bf0",
        sizeInBytes = 1_678_542_365L,
        displayName = "Gemma 3 1B (TPU · Tensor G5)",
        source = RelaisModelRef.SOURCE_HUGGINGFACE,
      ),
    )

  /**
   * Whether this install can actually SERVE the pinned TPU models: Tensor G5 device AND the
   * dispatcher lib bundled (debug builds today — see scripts/fetch-tensor-dispatcher.sh). Gating
   * the OFFERING on this keeps a release build from listing models it would fail to initialize.
   */
  fun tpuLaneUsable(context: Context): Boolean =
    isPixel10() &&
      File(context.applicationInfo.nativeLibraryDir, RelaisTpuLane.DISPATCHER_LIB).exists()

  // TTL cache for the last successful (non-empty) allowlist fetch. Avoids a blocking network round-
  // trip on every GET /v1/models call. Only a successful non-empty result is cached: an offline/
  // unreachable result leaves cachedRefs null so the next call retries rather than serving stale
  // emptiness for up to 5 minutes.
  @Volatile private var cachedRefs: List<RelaisModelRef>? = null
  @Volatile private var cacheTimeMs: Long = 0L

  /**
   * Fetches the allowlist and returns the node-runnable curated models as refs. **Blocking** —
   * call off the main thread. Returns [emptyList] on offline / fetch failure (the caller then shows
   * an "offline — enter an id" affordance), never throwing, so opening the selector can't crash.
   *
   * Results are cached for [CURATED_TTL_MS] (5 min) so repeated calls (e.g. GET /v1/models) do not
   * incur a network round-trip per request. Only a successful non-empty fetch populates the cache —
   * an offline failure leaves the cache unpopulated and retries immediately next call.
   *
   * getJsonResponse sets connect/read timeouts, so a wedged network fails fast rather than hanging;
   * the selector additionally bounds its own fetch with withTimeoutOrNull for a snappier spinner.
   */
  fun curatedModels(): List<RelaisModelRef> = curatedModels(includeTpuModels = false)

  /**
   * As [curatedModels], with the Relais-pinned [G5_TPU_REFS] appended when [includeTpuModels]
   * (callers pass [tpuLaneUsable]). The TPU refs need no network, so they are offered even when
   * the upstream allowlist is unreachable — an offline node on a G5 still lists its TPU models.
   */
  fun curatedModels(includeTpuModels: Boolean): List<RelaisModelRef> {
    val base = allowlistModels()
    return if (includeTpuModels) base + G5_TPU_REFS else base
  }

  private fun allowlistModels(): List<RelaisModelRef> {
    val now = System.currentTimeMillis()
    cachedRefs?.let { cached ->
      if (now - cacheTimeMs < CURATED_TTL_MS) return cached
    }
    val url = RelaisModelProvisioner.allowlistUrl()
    val allowlist =
      getJsonResponse<ModelAllowlist>(url)?.jsonObj
        ?: run {
          Log.w(TAG, "Allowlist unreachable ($url); curated list empty (offline?)")
          return emptyList()
        }
    val refs = curatedModelsFrom(allowlist)
    // Only cache a successful non-empty result — do not poison the cache with an empty list,
    // so an offline response retries next call rather than serving emptiness for 5 minutes.
    if (refs.isNotEmpty()) {
      cachedRefs = refs
      cacheTimeMs = now
    }
    return refs
  }

  /** Pure filter+map over an already-fetched allowlist — the network-free seam tests exercise. */
  internal fun curatedModelsFrom(allowlist: ModelAllowlist): List<RelaisModelRef> =
    allowlist.models.mapNotNull { m ->
      // Gson ignores Kotlin non-null types: taskTypes/modelFile/name can be null at runtime.
      // Wrap the entire filter+map per entry so one malformed entry drops rather than aborting.
      runCatching { if (isNodeRunnable(m)) m.toRef() else null }
        .getOrElse { if (it is CancellationException) throw it else null }
    }

  /**
   * A model is node-runnable when it isn't disabled, serves an LLM-chat task, and runs on LiteRT-LM.
   * [AllowedModel.runtimeType] is optional in the allowlist JSON, so a null type is inferred from a
   * `.litertlm` file extension rather than excluded — older entries predate the explicit field.
   *
   * taskTypes/modelFile may be null at runtime (Gson reflection) — see [curatedModelsFrom].
   */
  private fun isNodeRunnable(m: AllowedModel): Boolean {
    if (m.disabled == true) return false
    // taskTypes can be null (Gson reflection); null means no LLM_CHAT task → not runnable.
    if (m.taskTypes?.contains(BuiltInTaskId.LLM_CHAT) != true) return false
    // A RelaisModelRef captures only the top-level file/commit/size. For a per-SOC entry,
    // AllowedModel.toModel() swaps in the device-SOC variant's file/commit/size, so a ref built
    // from the top-level fields would resolve to a different (or wrong) on-disk path + URL than the
    // allowlist path — breaking the "fetched one way, reused the other" guarantee. Exclude until the
    // ref can carry the SOC-resolved file. (No curated model uses this today; this is future-proofing.)
    if (m.socToModelFiles?.isNotEmpty() == true) return false
    // modelFile can be null (Gson reflection); a null file is not a valid .litertlm entry.
    return m.runtimeType == RuntimeType.LITERT_LM ||
      (m.runtimeType == null && m.modelFile?.endsWith(".litertlm") == true)
  }

  private fun AllowedModel.toRef(): RelaisModelRef =
    RelaisModelRef(
      modelId = modelId,
      modelFile = modelFile,
      commitHash = commitHash,
      sizeInBytes = sizeInBytes,
      displayName = name,
      source = RelaisModelRef.SOURCE_ALLOWLIST,
    )
}
