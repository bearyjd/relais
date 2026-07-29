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

package cc.grepon.relais

/**
 * State + copy for downloading a model straight from the MODELS screen (#217).
 *
 * Before this, picking a model only persisted the selection ([ModelSwitch.applyRef] is a one-line
 * `setModelRef`) and the bytes were fetched later as a side effect of the node **starting**. With no
 * progress and no message, a pick looked like nothing happened — and if the node was also stalled,
 * there was no reachable path to download a model at all.
 *
 * Pure (no Android types) so every line below is unit-tested rather than eyeballed on-device.
 */
sealed interface ModelDownloadState {
  /** Nothing in flight; the screen shows its normal affordances. */
  data object Idle : ModelDownloadState

  /** Kicked off, but the provisioner hasn't reported a byte count yet (resolve / allowlist fetch). */
  data object Preparing : ModelDownloadState

  /** Bytes are moving. [percent] is 0..100 as reported by `ensureModel`'s progress callback. */
  data class Downloading(val percent: Int) : ModelDownloadState

  /** The model is on disk and ready to serve. */
  data class Ready(val modelId: String) : ModelDownloadState

  /** The download failed; [message] is the provisioner's own error text. */
  data class Failed(val message: String) : ModelDownloadState
}

/** True while a download occupies the screen — used to block a second concurrent kick. */
fun ModelDownloadState.isInFlight(): Boolean =
  this is ModelDownloadState.Preparing || this is ModelDownloadState.Downloading

/**
 * The status line under the model row, or null when there is nothing to say.
 *
 * [ModelDownloadState.Ready] deliberately still renders: "already on disk" is exactly the case a
 * user re-tapping DOWNLOAD needs confirmed, and silence there reads as another dead button.
 */
fun modelDownloadLine(state: ModelDownloadState): String? =
  when (state) {
    is ModelDownloadState.Idle -> null
    is ModelDownloadState.Preparing -> "preparing download…"
    is ModelDownloadState.Downloading -> "downloading · ${state.percent.coerceIn(0, 100)}%"
    is ModelDownloadState.Ready -> "model ready on device"
    is ModelDownloadState.Failed -> "download failed · ${state.message}"
  }

/**
 * An actionable hint for a failed download, or null when the raw message is enough.
 *
 * The overwhelmingly common failure is a **license-gated repo** — `google/gemma-3n-*` in the shipped
 * allowlist 401s without a Hugging Face token whose account has accepted the Gemma license. The raw
 * "HTTP 401" tells an operator nothing about the fix, and the fix isn't on this screen, so name it.
 */
fun modelDownloadHint(state: ModelDownloadState): String? {
  if (state !is ModelDownloadState.Failed) return null
  val m = state.message.lowercase()
  return when {
    "401" in m || "unauthorized" in m || "403" in m || "forbidden" in m ->
      "this model's repo is license-gated — accept its license on huggingface.co, then save an HF token in CONFIGURE"
    "offline" in m || "could not fetch" in m || "unable to resolve" in m ->
      "couldn't reach the model catalog — check the network, then retry"
    "space" in m || "enospc" in m ->
      "not enough free storage for this model"
    else -> null
  }
}
