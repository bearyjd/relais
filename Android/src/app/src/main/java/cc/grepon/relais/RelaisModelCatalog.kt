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
import cc.grepon.relais.common.getJsonResponse
import kotlinx.coroutines.CancellationException
import cc.grepon.relais.data.AllowedModel
import cc.grepon.relais.data.BuiltInTaskId
import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.data.RuntimeType

private const val TAG = "RelaisModelCatalog"

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
   * Fetches the allowlist and returns the node-runnable curated models as refs. **Blocking** —
   * call off the main thread. Returns [emptyList] on offline / fetch failure (the caller then shows
   * an "offline — enter an id" affordance), never throwing, so opening the selector can't crash.
   *
   * getJsonResponse sets connect/read timeouts, so a wedged network fails fast rather than hanging;
   * the selector additionally bounds its own fetch with withTimeoutOrNull for a snappier spinner.
   */
  fun curatedModels(): List<RelaisModelRef> {
    val url = RelaisModelProvisioner.allowlistUrl()
    val allowlist =
      getJsonResponse<ModelAllowlist>(url)?.jsonObj
        ?: run {
          Log.w(TAG, "Allowlist unreachable ($url); curated list empty (offline?)")
          return emptyList()
        }
    return curatedModelsFrom(allowlist)
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
