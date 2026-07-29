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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cc.grepon.relais.chat.SpeechState
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.tts.RelaisTtsEngine
import cc.grepon.relais.tts.RelaisTtsEngineProvider
import cc.grepon.relais.tts.TtsAudio
import cc.grepon.relais.tts.TtsAvailability
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Seam tests for in-app speech playback (#211) — the stateful half that the pure `SpeechText` /
 * `ChatSpeech` suites cannot reach. Both review passes on this feature found their bugs here.
 *
 * A fake engine is registered into [RelaisTtsEngineProvider], which is a mutable process-wide
 * singleton with a `register()` seam — so no constructor injection or refactor is needed. Playback
 * itself is not exercised (`AudioTrack` needs real hardware); these cover availability routing,
 * supersede/ownership, and notice lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelSpeechTest {

  private val dispatcher = StandardTestDispatcher()
  private lateinit var vm: ChatViewModel

  /**
   * Owns the ViewModel so [tearDown] can clear it: `onCleared()` is protected, and going through a
   * store is the only way to trigger it. Without this each test leaks a Ktor HttpClient and an
   * un-released TtsPlayer.
   */
  private val store = ViewModelStore()

  /** Records what the engine was asked to do, so "did we skip stale work?" is assertable. */
  private class FakeTtsEngine(
    // Named *Result, not `availability`: it would otherwise shadow the interface method one line
    // below, which gets actively confusing now that the method counts its calls.
    @Volatile var availabilityResult: TtsAvailability = TtsAvailability.READY,
    private val onSynthesize: (() -> Unit)? = null,
  ) : RelaisTtsEngine {
    val synthesizeCalls = AtomicInteger(0)
    val provisionCalls = AtomicInteger(0)

    /** Counted because `availability()` is the expensive call — it loads the ~64 MB voice model. */
    val availabilityCalls = AtomicInteger(0)

    override fun isAvailable(context: Context) = availabilityResult == TtsAvailability.READY

    override fun canProvision(context: Context) =
      availabilityResult == TtsAvailability.PROVISIONING

    override fun availability(context: Context): TtsAvailability {
      availabilityCalls.incrementAndGet()
      return availabilityResult
    }

    override fun ensureProvisioningStarted(context: Context) {
      provisionCalls.incrementAndGet()
    }

    override fun synthesize(context: Context, text: String, voice: String?, speed: Float): TtsAudio {
      synthesizeCalls.incrementAndGet()
      onSynthesize?.invoke()
      // One sample at a rate AudioTrack will reject under Robolectric, so play() fails fast rather
      // than trying to produce real audio — playback outcome is not what these tests assert.
      return TtsAudio(FloatArray(1), 22_050)
    }
  }

  private fun turn(id: String, content: String) =
    ChatTurn(
      id = id,
      conversationId = "conv",
      role = "assistant",
      content = content,
      attachmentType = null,
      attachmentPath = null,
      answeredByModelId = "test-model",
      answeredByBackend = "TPU_LITERTLM",
      createdAt = 1L,
    )

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    // Speech work runs on the test dispatcher too, so advanceUntilIdle() actually drains it.
    val app = RuntimeEnvironment.getApplication()
    val factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          ChatViewModel(app, dispatcher) as T
      }
    vm = ViewModelProvider(store, factory)[ChatViewModel::class.java]
  }

  @After
  fun tearDown() {
    store.clear() // drives onCleared(): closes the transport client, releases the player
    RelaisTtsEngineProvider.register(null)
    Dispatchers.resetMain()
  }

  // ---- availability routing ----

  @Test
  fun `no registered engine means speech is not offered`() = runTest(dispatcher) {
    RelaisTtsEngineProvider.register(null)
    vm.refreshSpeechOffered()
    assertFalse(vm.speechOffered.value)
  }

  @Test
  fun `a registered engine offers speech without loading the voice model`() = runTest(dispatcher) {
    // refreshSpeechOffered must NOT call availability() — that loads a ~64 MB model, and this runs
    // on the main thread from init and every ON_RESUME.
    val engine = FakeTtsEngine()
    RelaisTtsEngineProvider.register(engine)
    vm.refreshSpeechOffered()
    assertTrue(vm.speechOffered.value)
    // The counter that matters: availability() — NOT synthesize() — is what loads the model, and
    // this runs on the main thread. Asserting synthesizeCalls here would pass with the fix reverted.
    assertEquals(
      "refreshSpeechOffered must not call availability(): it loads a ~64 MB model on the main thread",
      0,
      engine.availabilityCalls.get(),
    )
  }

  @Test
  fun `a provisioning engine kicks the download and reports fetching`() = runTest(dispatcher) {
    val engine = FakeTtsEngine(availabilityResult = TtsAvailability.PROVISIONING)
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "hello there"))
    advanceUntilIdle()

    assertEquals(SpeechState.Fetching("a"), vm.speech.value)
    assertEquals(1, engine.provisionCalls.get())
    assertEquals(0, engine.synthesizeCalls.get())
  }

  @Test
  fun `an unavailable engine reports failure and stops offering speech`() = runTest(dispatcher) {
    val engine = FakeTtsEngine(availabilityResult = TtsAvailability.UNAVAILABLE)
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "hello there"))
    advanceUntilIdle()

    assertTrue(vm.speech.value is SpeechState.Failed)
    assertFalse(vm.speechOffered.value)
  }

  // ---- the blank-speakable-text bug (review pass 1, item 2) ----

  @Test
  fun `a code-only turn reports nothing to speak and never synthesizes`() = runTest(dispatcher) {
    val engine = FakeTtsEngine()
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "```\nval x = 1\n```"))
    advanceUntilIdle()

    val state = vm.speech.value
    assertTrue(state.toString(), state is SpeechState.Failed)
    assertEquals("nothing to speak", (state as SpeechState.Failed).message)
    assertEquals(0, engine.synthesizeCalls.get())
  }

  @Test
  fun `speaking a code-only turn supersedes a prior attempt instead of leaving it owning state`() =
    runTest(dispatcher) {
      // Regression: the blank-text early return used to sit ABOVE the supersede, so the prior
      // attempt kept the generation and later reset state to Idle, wiping this notice.
      val engine = FakeTtsEngine()
      RelaisTtsEngineProvider.register(engine)

      vm.speak(turn("a", "a real sentence"))
      vm.speak(turn("b", "```\ncode only\n```"))
      advanceUntilIdle()

      val state = vm.speech.value
      assertTrue(state.toString(), state is SpeechState.Failed)
      assertEquals("b", (state as SpeechState.Failed).turnId)
    }

  @Test
  fun `a code-only turn does not trigger a voice download`() = runTest(dispatcher) {
    // Regression: the blank check used to be gated on READY, so an unprovisioned voice meant a
    // ~64 MB download completed before the user learned there was nothing to speak.
    val engine = FakeTtsEngine(availabilityResult = TtsAvailability.PROVISIONING)
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "```\nval x = 1\n```"))
    advanceUntilIdle()

    assertEquals(0, engine.provisionCalls.get())
    assertTrue(vm.speech.value is SpeechState.Failed)
  }

  // ---- supersede / ownership ----

  @Test
  fun `a superseded attempt does not synthesize`() = runTest(dispatcher) {
    val engine = FakeTtsEngine()
    RelaisTtsEngineProvider.register(engine)

    // Three taps queued before the dispatcher runs: only the last should reach the engine, because
    // each speak() bumps the generation and synthesis is gated on still owning it.
    vm.speak(turn("a", "first turn"))
    vm.speak(turn("b", "second turn"))
    vm.speak(turn("c", "third turn"))
    advanceUntilIdle()

    assertEquals(1, engine.synthesizeCalls.get())
  }

  @Test
  fun `stopSpeaking leaves state idle and keeps a cancelled attempt from writing`() =
    runTest(dispatcher) {
      val engine = FakeTtsEngine()
      RelaisTtsEngineProvider.register(engine)

      vm.speak(turn("a", "a real sentence"))
      vm.stopSpeaking()
      advanceUntilIdle()

      assertEquals(SpeechState.Idle, vm.speech.value)
    }

  @Test
  fun `stop then re-tap of the SAME turn supersedes rather than duplicating work`() =
    runTest(dispatcher) {
      // The generation guard exists for this shape: turn ids are identical across both attempts, so
      // an id-based ownership check would let the outgoing job clobber the incoming one.
      //
      // Caveat worth stating: virtual time serialises these, so the stopped attempt never reaches
      // synthesis and the true interleaving (the outgoing job resuming from blocking playback at a
      // non-suspending point) is NOT reproduced here. That case is covered by the generation
      // invariant and by the on-device check. What this pins is the observable contract.
      val engine = FakeTtsEngine()
      RelaisTtsEngineProvider.register(engine)

      vm.speak(turn("a", "a real sentence"))
      vm.stopSpeaking()
      vm.speak(turn("a", "a real sentence"))
      advanceUntilIdle()

      // Only the surviving attempt did work, and it — not the stopped one — owns the final state.
      assertEquals(1, engine.synthesizeCalls.get())
      assertTrue(vm.speech.value.toString(), vm.speech.value !is SpeechState.Preparing)
    }

  // ---- notice lifecycle ----

  @Test
  fun `clearSpeechNotice clears a failure for the matching turn only`() = runTest(dispatcher) {
    val engine = FakeTtsEngine(availabilityResult = TtsAvailability.UNAVAILABLE)
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "hello there"))
    advanceUntilIdle()

    vm.clearSpeechNotice("other-turn")
    assertTrue(vm.speech.value is SpeechState.Failed)

    vm.clearSpeechNotice("a")
    assertEquals(SpeechState.Idle, vm.speech.value)
  }

  @Test
  fun `clearSpeechNotice does not clear a fetching notice`() = runTest(dispatcher) {
    // Fetching outlasts any UI timer — only an availability re-check should clear it.
    val engine = FakeTtsEngine(availabilityResult = TtsAvailability.PROVISIONING)
    RelaisTtsEngineProvider.register(engine)

    vm.speak(turn("a", "hello there"))
    advanceUntilIdle()

    vm.clearSpeechNotice("a")
    assertEquals(SpeechState.Fetching("a"), vm.speech.value)
  }

  @Test
  fun `refreshSpeechOffered clears a fetching notice once the voice becomes ready`() =
    runTest(dispatcher) {
      val engine = FakeTtsEngine(availabilityResult = TtsAvailability.PROVISIONING)
      RelaisTtsEngineProvider.register(engine)

      vm.speak(turn("a", "hello there"))
      advanceUntilIdle()
      assertEquals(SpeechState.Fetching("a"), vm.speech.value)

      engine.availabilityResult = TtsAvailability.READY
      vm.refreshSpeechOffered()
      advanceUntilIdle()

      assertEquals(SpeechState.Idle, vm.speech.value)
    }
}
