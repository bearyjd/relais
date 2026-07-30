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

/**
 * Whether a curated allowlist entry can actually be **served by this node**, and whether downloading
 * it needs the operator's HuggingFace token.
 *
 * ### Why this exists (#220)
 * The curated allowlist is upstream (`google-ai-edge/gallery`) and keyed by our `versionName`, so it
 * drifts independently of the litertlm version we pin. Nothing validated that intersection, and the
 * cost of finding out was a multi-GB download followed by a dead node:
 * ```
 * E RelaisNodeService: com.google.ai.edge.litertlm.LiteRtLmJniException:
 *     Failed to create engine: INTERNAL: Failed to parse LlmMetadata
 * ```
 * This is the "allowlist of the allowlist" — a small, **measured** table pinned to
 * [PINNED_LITERTLM_VERSION].
 *
 * ### Maintenance contract
 * Every entry below is an observation from real hardware, not an inference. On a litertlm bump the
 * measurements are void until re-run: update [PINNED_LITERTLM_VERSION] **and** re-measure, rather
 * than carrying stale verdicts forward. [RelaisRuntimeCompatTest] pins the version so the bump can't
 * pass review silently.
 *
 * ### Scope limit
 * Classification is keyed by **repo id**, which is exact today because every curated repo offers a
 * single node-runnable build. A repo shipping both a working and a broken build would need
 * file-level keying — `litert-community/Gemma3-1B-IT` is the near miss: its allowlist entry is
 * license-gated, while its Relais-pinned `…_Google_Tensor_G5` AOT build
 * ([RelaisModelCatalog.G5_TPU_REFS]) is separately provisioned and serves fine. Gating applies to
 * both (same repo); loadability is not asserted for either.
 */
object RelaisRuntimeCompat {

  /**
   * The litertlm the table below was measured against — must track `litertlm` in
   * `gradle/libs.versions.toml`. See the maintenance contract above.
   */
  const val PINNED_LITERTLM_VERSION = "0.12.0"

  /** How confident we are that the pinned runtime can load a model — evidence, not vibes. */
  enum class Loadability {
    /** Measured on-device: downloads, creates an engine, and serves. */
    VERIFIED,

    /** Measured on-device: downloads, then fails engine-create. Never offer it. */
    INCOMPATIBLE,

    /** Untested, but shares a build family with a measured failure. Offer with a warning. */
    SUSPECT,

    /** No measurement either way — most of the allowlist, and every future addition. */
    UNKNOWN,
  }

  /** Measured good on litertlm 0.12.0 (#220, plus #161 for E4B on Tensor G5). */
  private val VERIFIED_LOADABLE =
    setOf(
      "litert-community/gemma-4-E2B-it-litert-lm",
      "litert-community/gemma-4-E4B-it-litert-lm",
    )

  /**
   * Measured bad on litertlm 0.12.0, mapped to the operator-facing reason. Keep the wording concrete
   * — it is shown instead of a generic init failure.
   */
  private val KNOWN_INCOMPATIBLE =
    mapOf(
      "litert-community/Qwen2.5-1.5B-Instruct" to
        "not loadable by this node's LiteRT-LM $PINNED_LITERTLM_VERSION runtime " +
        "(engine-create fails: \"Failed to parse LlmMetadata\")"
    )

  /**
   * Untested, but built the same way as a measured failure (`multi-prefill-seq_q8_ekv4096`), so it
   * is flagged rather than silently offered as if known-good. Promote to [VERIFIED_LOADABLE] or
   * [KNOWN_INCOMPATIBLE] once someone runs it on hardware.
   */
  private val SUSPECTED_INCOMPATIBLE = setOf("litert-community/DeepSeek-R1-Distill-Qwen-1.5B")

  /**
   * License-gated repos: a download without the operator's HF token gets **401**. Verified host-side
   * with a ranged GET (#220).
   *
   * `litert-community/Gemma3-1B-IT` is the reason this set exists at all — the selector previously
   * inferred gating from a `google/` prefix, so the one gated `litert-community` repo rendered with
   * no token badge and 401'd at download time with nothing pointing at the HF token field.
   */
  private val LICENSE_GATED =
    setOf(
      "litert-community/Gemma3-1B-IT",
      "google/gemma-3n-E2B-it-litert-lm",
      "google/gemma-3n-E4B-it-litert-lm",
    )

  /** Repos under this owner are Gemma-licensed as a rule — the fallback for unmeasured entries. */
  private const val GATED_OWNER_PREFIX = "google/"

  /** Classifies [modelId] against the pinned runtime. */
  fun loadability(modelId: String): Loadability =
    when (modelId) {
      in KNOWN_INCOMPATIBLE -> Loadability.INCOMPATIBLE
      in VERIFIED_LOADABLE -> Loadability.VERIFIED
      in SUSPECTED_INCOMPATIBLE -> Loadability.SUSPECT
      else -> Loadability.UNKNOWN
    }

  /**
   * The operator-facing reason a model cannot be served, or null when there is no **observed**
   * failure. Deliberately null for [Loadability.SUSPECT]: a suspicion is not a diagnosis.
   */
  fun incompatibleReason(modelId: String): String? = KNOWN_INCOMPATIBLE[modelId]

  /**
   * Whether the catalog should still offer [modelId]. Only a measured failure is withheld — gating
   * and suspicion are surfaced as badges instead, because withholding on suspicion would quietly
   * shrink the catalog every time upstream adds something we have not measured.
   */
  fun isOfferable(modelId: String): Boolean = loadability(modelId) != Loadability.INCOMPATIBLE

  /**
   * Whether downloading [modelId] needs the operator's HF token. Shown **before** the download so a
   * headless operator does not wait out a multi-GB transfer only to hit a 401.
   */
  fun requiresHfToken(modelId: String): Boolean =
    modelId in LICENSE_GATED || modelId.startsWith(GATED_OWNER_PREFIX)
}
