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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic unit tests for the pure swap-on-mismatch decision in [RelaisModelSwap.kt] (#180). No
 * Context, no Android types, no [RelaisEngine] — pure JVM, mirrors [RelaisIdleTtlTest].
 *
 * The real concurrency/lock-ordering safety (never swapping mid-inference, the watchdog not
 * mistaking the swap's not-ready window for a crash) is NOT covered here — it lives in
 * [RelaisEngine.ensureModelSwapInBackground] and needs an on-device/instrumented probe instead; see
 * that function's KDoc for the argument.
 */
class RelaisModelSwapTest {

  private val resident = "litert-community/gemma-4-E4B-it-litert-lm"
  private val configured = "litert-community/qwen3-4b-it-litert-lm"

  // -------------------------------------------------------------------------
  // 1. not ready -> never swap (nothing resident to swap away from)
  // -------------------------------------------------------------------------

  @Test
  fun `never swaps when engine is not ready`() {
    assertFalse(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = configured,
        configuredModelId = configured,
        isReady = false,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 2. requested null or blank -> never swap
  // -------------------------------------------------------------------------

  @Test
  fun `never swaps when requested model is null`() {
    assertFalse(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = null,
        configuredModelId = configured,
        isReady = true,
      )
    )
  }

  @Test
  fun `never swaps when requested model is blank`() {
    assertFalse(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = "   ",
        configuredModelId = configured,
        isReady = true,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 3. requested == resident -> already serving it, no-op
  // -------------------------------------------------------------------------

  @Test
  fun `never swaps when requested model already matches resident`() {
    assertFalse(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = resident,
        configuredModelId = resident,
        isReady = true,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 4. the swap case: requested == configured, != resident, ready -> true
  // -------------------------------------------------------------------------

  @Test
  fun `swaps when requested matches the operator's configured model and differs from resident`() {
    assertTrue(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = configured,
        configuredModelId = configured,
        isReady = true,
      )
    )
  }

  // -------------------------------------------------------------------------
  // 5. the narrow-scope guard: requested != configured -> never swap, even if != resident.
  //    This is the most important test: it is what stops an arbitrary client-supplied model id
  //    (one the operator never staged via the app's Models UI) from forcing the node to load a
  //    model of the caller's choosing. Without this guard, any LAN client could trigger an
  //    unattended multi-GB download/load just by naming a model in its request.
  // -------------------------------------------------------------------------

  @Test
  fun `never swaps to an arbitrary client-named model the operator never configured`() {
    assertFalse(
      shouldSwapModel(
        residentModelId = resident,
        requestedModelId = "some-untrusted/arbitrary-model-id",
        configuredModelId = configured,
        isReady = true,
      )
    )
  }
}
