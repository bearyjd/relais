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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure `model`-field decision in [RelaisModelSwap.kt] (#180, full
 * feature). No Context, no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisIdleTtlTest].
 *
 * The real concurrency/lock-ordering safety (never swapping mid-inference, the watchdog not
 * mistaking the swap's not-ready window for a crash) is NOT covered here — it lives in
 * [RelaisEngine.ensureModelSwapInBackground] and needs an on-device probe instead.
 */
class RelaisModelSwapTest {

  private val resident = "litert-community/gemma-4-E4B-it-litert-lm"
  private val configured = "litert-community/qwen3-4b-it-litert-lm"
  private val alsoOnDisk = "litert-community/Qwen2.5-1.5B-Instruct"
  private val onDisk = setOf(resident, configured, alsoOnDisk)

  private fun outcome(
    requested: String?,
    residentId: String? = resident,
    configuredId: String = configured,
    provisioned: Set<String> = onDisk,
    isReady: Boolean = true,
    incompatibleReason: (String) -> String? = { null },
  ) = resolveModelRequest(residentId, requested, configuredId, provisioned, isReady, incompatibleReason)

  // ---- serve the resident model ----

  @Test fun `an omitted model field serves the resident model`() {
    // The single most common request shape. Under a 404-on-unknown policy this MUST stay
    // ServeResident or every client that omits `model` breaks.
    assertEquals(ModelRequestOutcome.ServeResident, outcome(null))
    assertEquals(ModelRequestOutcome.ServeResident, outcome(""))
    assertEquals(ModelRequestOutcome.ServeResident, outcome("   "))
  }

  @Test fun `requesting the already-resident model serves it without a swap`() {
    assertEquals(ModelRequestOutcome.ServeResident, outcome(resident))
  }

  @Test fun `nothing is decided while the engine is not ready`() {
    // Refusing here would 404 a model the node may well have, purely because it is still coming up —
    // the existing 503 not-ready path owns this window.
    assertEquals(ModelRequestOutcome.ServeResident, outcome("anything-at-all", isReady = false))
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(configured, residentId = null, isReady = false),
    )
  }

  // ---- swap ----

  @Test fun `the operator's configured model is swap-eligible`() {
    assertEquals(ModelRequestOutcome.SwapThenRetry(configured), outcome(configured))
  }

  @Test fun `any OTHER model already on disk is swap-eligible`() {
    // The whole point of the full feature: the first cut could only swap to the configured id, so a
    // client naming a different downloaded model was silently answered by the wrong one.
    assertEquals(ModelRequestOutcome.SwapThenRetry(alsoOnDisk), outcome(alsoOnDisk))
  }

  @Test fun `the configured model is swap-eligible even before it reaches the registry`() {
    // A fresh selection may not be recorded yet; the operator's own choice must still work.
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, provisioned = emptySet()),
    )
  }

  // ---- refuse: measured-incompatible with the pinned runtime (#220) ----

  /** A stand-in table, so these assertions don't depend on what the shipped one happens to say. */
  private val brokenOnThisRuntime = { id: String ->
    if (id == alsoOnDisk) "not loadable by this node's runtime (engine-create fails)" else null
  }

  @Test fun `a model on disk that cannot load is refused instead of swapped to`() {
    // Without this the client gets 503 + Retry-After, waits, retries, and the swap dies deep in
    // engine init — the #220 experience, just relocated from the download to the request.
    assertEquals(
      ModelRequestOutcome.Incompatible(
        alsoOnDisk,
        "not loadable by this node's runtime (engine-create fails)",
      ),
      outcome(alsoOnDisk, incompatibleReason = brokenOnThisRuntime),
    )
  }

  @Test fun `an incompatible verdict does not leak onto other models`() {
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, incompatibleReason = brokenOnThisRuntime),
    )
  }

  @Test fun `a resident model answering requests outranks the compatibility table`() {
    // Observed reality beats a static table: the table exists to stop us LOADING something, not to
    // refuse something that is demonstrably already serving.
    assertEquals(
      ModelRequestOutcome.ServeResident,
      outcome(resident, residentId = resident, incompatibleReason = { "claims to be broken" }),
    )
  }

  @Test fun `an unprovisioned model is NotProvisioned even when also flagged incompatible`() {
    // Not-on-disk is the more actionable diagnosis, and it is checked against real state rather
    // than a table — so it must not be masked by the compat verdict.
    val absent = "meta-llama/Llama-3-70B"
    assertEquals(
      ModelRequestOutcome.NotProvisioned(absent),
      outcome(absent, provisioned = emptySet(), configuredId = configured, incompatibleReason = { null }),
    )
  }

  @Test fun `by default nothing is treated as incompatible`() {
    // The parameter defaults to "nothing is known-bad" so every pre-#220 caller is unaffected.
    assertEquals(ModelRequestOutcome.SwapThenRetry(alsoOnDisk), outcome(alsoOnDisk))
  }

  // ---- refuse ----

  @Test fun `a model that is not on this device is refused, not silently substituted`() {
    assertEquals(
      ModelRequestOutcome.NotProvisioned("meta-llama/Llama-3-70B"),
      outcome("meta-llama/Llama-3-70B"),
    )
  }

  @Test fun `an unprovisioned model is refused even when it looks plausible`() {
    // Near-miss on the resident id: still not on disk, still a refusal. No fuzzy matching.
    assertEquals(
      ModelRequestOutcome.NotProvisioned("litert-community/gemma-4-E4B-it"),
      outcome("litert-community/gemma-4-E4B-it"),
    )
  }

  @Test fun `an empty registry still refuses an unknown model rather than serving the resident one`() {
    assertEquals(
      ModelRequestOutcome.NotProvisioned("something-else"),
      outcome("something-else", provisioned = emptySet()),
    )
  }

  // ---- the security boundary ----

  @Test fun `a client-named model never becomes a swap target unless it is already on disk`() {
    // The safety property the first cut's narrow guard provided, now carried by the registry: an
    // arbitrary LAN client cannot make the node fetch anything.
    listOf("../../etc/passwd", "http://evil/model", "org/enormous-70b", "").forEach { id ->
      val result = outcome(id, provisioned = setOf(resident))
      assertTrue(
        "must never swap to unprovisioned id '$id' (got $result)",
        result !is ModelRequestOutcome.SwapThenRetry,
      )
    }
  }

  @Test fun `a null resident id before first init does not break the decision`() {
    assertEquals(
      ModelRequestOutcome.SwapThenRetry(configured),
      outcome(configured, residentId = null),
    )
  }
}
