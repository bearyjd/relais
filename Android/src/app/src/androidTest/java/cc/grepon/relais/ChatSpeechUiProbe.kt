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

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.grepon.relais.chat.ChatMessageList
import cc.grepon.relais.chat.ERROR_BACKEND
import cc.grepon.relais.chat.RefreshOnResume
import cc.grepon.relais.chat.SPEAKING_STRIP_TAG
import cc.grepon.relais.chat.SpeakingStopStrip
import cc.grepon.relais.chat.SpeechState
import cc.grepon.relais.data.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI probe for the in-app speech affordances (#211) — the layer the JVM seam tests can't
 * reach and that `SpeechPlaybackProbe` (which drives the player directly) deliberately skips.
 *
 * Drives the composables themselves rather than tapping screen coordinates: adb taps drift badly on
 * the foldables this repo targets, and coordinate-based UI driving is exactly the flakiness the
 * handoff warns about. This is the repo's first Compose UI test.
 *
 * Run:
 * ```
 * adb -s <serial> shell am instrument -w \
 *   -e class cc.grepon.relais.ChatSpeechUiProbe \
 *   com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class ChatSpeechUiProbe {

  @get:Rule val compose = createComposeRule()

  private fun assistantTurn(id: String = "a", content: String = "The node is live.", backend: String? = "TPU_LITERTLM") =
    ChatTurn(
      id = id,
      conversationId = "conv",
      role = "assistant",
      content = content,
      attachmentType = null,
      attachmentPath = null,
      answeredByModelId = "test-model",
      answeredByBackend = backend,
      createdAt = 1L,
    )

  /** Renders the message list with speech wired; returns the recorded callback invocations. */
  private fun setList(
    turns: List<ChatTurn>,
    speechState: SpeechState = SpeechState.Idle,
    speechOffered: Boolean = true,
    onSpeak: (ChatTurn) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
  ) {
    compose.setContent {
      ChatMessageList(
        turns = turns,
        streamingText = "",
        streaming = false,
        pendingPersistedTurnId = null,
        onCopy = {},
        onRegenerate = {},
        onEditResend = { _, _ -> },
        speechState = speechState,
        speechOffered = speechOffered,
        onSpeak = onSpeak,
        onStopSpeaking = onStopSpeaking,
        onSpeechNoticeShown = {},
        onReport = {},
      )
    }
  }

  // ---- is SPEAK offered at all ----

  @Test
  fun speakIsShownForAnOrdinaryAssistantTurn() {
    setList(listOf(assistantTurn()))
    compose.onNodeWithText("SPEAK").assertIsDisplayed()
  }

  @Test
  fun speakIsHiddenWhenNoEngineIsRegistered() {
    setList(listOf(assistantTurn()), speechOffered = false)
    compose.onNodeWithText("SPEAK").assertDoesNotExist()
    // The pre-existing actions must be untouched by the feature being off.
    compose.onNodeWithText("COPY").assertIsDisplayed()
    compose.onNodeWithText("REGEN").assertIsDisplayed()
  }

  @Test
  fun speakIsHiddenOnErrorTurns() {
    setList(listOf(assistantTurn(content = "[error] connection refused", backend = ERROR_BACKEND)))
    compose.onNodeWithText("SPEAK").assertDoesNotExist()
  }

  @Test
  fun userTurnsNeverOfferSpeak() {
    val userTurn = assistantTurn().copy(role = "user", content = "hello")
    setList(listOf(userTurn))
    compose.onNodeWithText("SPEAK").assertDoesNotExist()
  }

  // ---- the label doubles as the status readout ----

  @Test
  fun labelReflectsEachSpeechState() {
    // One setContent (the rule allows only one), driving the states through RECOMPOSITION — which
    // also proves the label actually updates in place rather than only rendering correctly on first
    // composition.
    val turn = assistantTurn()
    var state by mutableStateOf<SpeechState>(SpeechState.Idle)
    compose.setContent {
      ChatMessageList(
        turns = listOf(turn),
        streamingText = "",
        streaming = false,
        pendingPersistedTurnId = null,
        onCopy = {},
        onRegenerate = {},
        onEditResend = { _, _ -> },
        speechState = state,
        speechOffered = true,
        onSpeak = {},
        onStopSpeaking = {},
        onSpeechNoticeShown = {},
        onReport = {},
      )
    }

    compose.onNodeWithText("SPEAK").assertIsDisplayed()

    state = SpeechState.Preparing(turn.id)
    compose.onNodeWithText("SYNTHESIZING").assertIsDisplayed()

    state = SpeechState.Speaking(turn.id)
    compose.onNodeWithText("STOP").assertIsDisplayed()

    state = SpeechState.Fetching(turn.id)
    compose.onNodeWithText("FETCHING VOICE").assertIsDisplayed()

    state = SpeechState.Failed(turn.id, "boom")
    compose.onNodeWithText("SPEECH FAILED").assertIsDisplayed()

    state = SpeechState.Idle
    compose.onNodeWithText("SPEAK").assertIsDisplayed()
  }

  @Test
  fun theFailureMessageIsNeverRendered() {
    // Guards the documented boundary: exception text can carry absolute storage paths, and this row
    // is what a user screenshots into a bug report.
    val turn = assistantTurn()
    setList(listOf(turn), speechState = SpeechState.Failed(turn.id, "/storage/emulated/0/secret.onnx"))
    compose.onNodeWithText("/storage/emulated/0/secret.onnx", substring = true).assertDoesNotExist()
    compose.onNodeWithText("SPEECH FAILED").assertIsDisplayed()
  }

  @Test
  fun anotherTurnStillReadsSpeakWhileOneIsSpeaking() {
    val a = assistantTurn(id = "a")
    val b = assistantTurn(id = "b", content = "A second reply.")
    setList(listOf(a, b), speechState = SpeechState.Speaking(a.id))
    compose.onNodeWithText("STOP").assertIsDisplayed()
    compose.onNodeWithText("SPEAK").assertIsDisplayed() // b's row — tapping it supersedes
  }

  // ---- taps dispatch the right callback ----

  @Test
  fun tappingSpeakRequestsThatTurn() {
    var spoken: ChatTurn? = null
    val turn = assistantTurn()
    setList(listOf(turn), onSpeak = { spoken = it })
    compose.onNodeWithText("SPEAK").performClick()
    assertEquals(turn.id, spoken?.id)
  }

  @Test
  fun tappingStopStopsPlayback() {
    var stopped = false
    val turn = assistantTurn()
    setList(listOf(turn), speechState = SpeechState.Speaking(turn.id), onStopSpeaking = { stopped = true })
    compose.onNodeWithText("STOP").performClick()
    assertEquals(true, stopped)
  }

  @Test
  fun synthesizingExposesNoClickActionAtAll() {
    // SYNTHESIZING is a status, not a button. Asserting "the tap does nothing" is not enough: a
    // no-op onClick would still be announced to TalkBack as an actionable control. It must carry no
    // click action whatsoever.
    val turn = assistantTurn()
    setList(listOf(turn), speechState = SpeechState.Preparing(turn.id))
    compose.onNodeWithText("SYNTHESIZING").assertHasNoClickAction()
  }

  @Test
  fun actionableLabelsAnnounceAsButtons() {
    // Screen readers must hear a control, not prose — this row is the whole speech affordance.
    val turn = assistantTurn()
    setList(listOf(turn))
    compose.onNodeWithText("SPEAK").assertHasClickAction()
  }

  @Test
  fun fetchingStaysTappableSoAFailedDownloadIsRecoverable() {
    var speakCalls = 0
    val turn = assistantTurn()
    setList(listOf(turn), speechState = SpeechState.Fetching(turn.id), onSpeak = { speakCalls++ })
    compose.onNodeWithText("FETCHING VOICE").performClick()
    assertEquals(1, speakCalls)
  }

  // ---- the screen-level STOP strip (audio must be stoppable when the row scrolls away) ----

  @Test
  fun theStopStripAppearsOnlyWhileSpeaking() {
    var state by mutableStateOf<SpeechState>(SpeechState.Idle)
    compose.setContent { SpeakingStopStrip(state) {} }

    compose.onNodeWithTag(SPEAKING_STRIP_TAG).assertDoesNotExist()

    state = SpeechState.Speaking("a")
    compose.onNodeWithTag(SPEAKING_STRIP_TAG).assertIsDisplayed()

    state = SpeechState.Preparing("a")
    compose.onNodeWithTag(SPEAKING_STRIP_TAG).assertDoesNotExist()
  }

  @Test
  fun theStopStripStopsPlayback() {
    var stopped = false
    compose.setContent { SpeakingStopStrip(SpeechState.Speaking("a")) { stopped = true } }
    compose.onNodeWithText("STOP").performClick()
    assertEquals(true, stopped)
  }

  // ---- availability re-check on resume (TTS registers at NODE startup, not app startup) ----

  @Test
  fun availabilityIsRecheckedOnEnterAndOnEveryResume() {
    var refreshes = 0
    lateinit var owner: TestLifecycleOwner
    // LifecycleRegistry enforces the main thread — including the CREATE/START it raises in init.
    InstrumentationRegistry.getInstrumentation().runOnMainSync { owner = TestLifecycleOwner() }

    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) { RefreshOnResume { refreshes++ } }
    }
    compose.waitForIdle()

    // Entering composition performs the initial check.
    assertEquals(1, refreshes)

    compose.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    compose.waitForIdle()
    assertEquals("ON_RESUME must re-check — the node may have started meanwhile", 2, refreshes)

    compose.runOnUiThread {
      owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
      owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    compose.waitForIdle()
    assertEquals(3, refreshes)
  }

  private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)

    init {
      registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override val lifecycle: Lifecycle
      get() = registry
  }
}
