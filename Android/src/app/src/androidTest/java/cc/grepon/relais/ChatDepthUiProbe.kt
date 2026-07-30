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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cc.grepon.relais.chat.ChatMessageList
import cc.grepon.relais.chat.SpeechState
import cc.grepon.relais.data.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI probe for the chat-depth turn affordances (#144) that the on-device passes in #146 left
 * as "not yet exercised": **copy + COPIED ack**, **regenerate**, and **edit-and-resend including
 * attachment preservation**.
 *
 * Why this exists: those items were open not because they were risky but because driving them meant
 * tapping screen coordinates, which drifts badly on the foldables this repo targets. #214 established
 * that Compose probes can drive the composables directly instead, and #146's own status comment names
 * that as the route for anything that is *in-app Compose UI* rather than *system UI*. This closes the
 * in-app half; the SAF export picker, the system share sheet, and the audio-attach file picker stay
 * genuinely human-gated because they cross into system surfaces.
 *
 * Run:
 * ```
 * adb -s <serial> shell am instrument -w \
 *   -e class cc.grepon.relais.ChatDepthUiProbe \
 *   com.ventouxlabs.relais.izzy.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class ChatDepthUiProbe {

  @get:Rule val compose = createComposeRule()

  private fun turn(
    id: String = "t1",
    role: String = "user",
    content: String = "hello",
    attachmentType: String? = null,
    attachmentPath: String? = null,
  ) =
    ChatTurn(
      id = id,
      conversationId = "conv",
      role = role,
      content = content,
      attachmentType = attachmentType,
      attachmentPath = attachmentPath,
      answeredByModelId = "test-model",
      answeredByBackend = if (role == "assistant") "TPU_LITERTLM" else null,
      createdAt = 1L,
    )

  /** Renders the list with every callback recordable; speech is off so its label can't collide. */
  private fun setList(
    turns: List<ChatTurn>,
    onCopy: (String) -> Unit = {},
    onRegenerate: (ChatTurn) -> Unit = {},
    onEditResend: (ChatTurn, String) -> Unit = { _, _ -> },
  ) {
    compose.setContent {
      ChatMessageList(
        turns = turns,
        streamingText = "",
        streaming = false,
        pendingPersistedTurnId = null,
        onCopy = onCopy,
        onRegenerate = onRegenerate,
        onEditResend = onEditResend,
        speechState = SpeechState.Idle,
        speechOffered = false,
        onSpeak = {},
        onStopSpeaking = {},
        onSpeechNoticeShown = {},
      )
    }
  }

  // ---- copy + the COPIED ack (#145) ----------------------------------------------------------

  /**
   * The COPIED ack is *transient* — `CopyLabel` reverts it via `LaunchedEffect { delay(1500) }`.
   * With the test clock's default auto-advance, waiting for idle runs straight through that delay
   * and the label is back to COPY before any assertion lands. So drive the clock manually: this is
   * the difference between observing the ack and silently never seeing it.
   */
  @Test
  fun copyHandsBackTheTurnTextAndAcknowledgesThenReverts() {
    var copied: String? = null
    compose.mainClock.autoAdvance = false
    setList(listOf(turn(content = "the exact text")), onCopy = { copied = it })

    compose.onNodeWithText("COPY").performClick()
    compose.mainClock.advanceTimeByFrame() // let the recomposition apply, but not the 1500ms delay

    assertEquals("copy must hand back the turn's own text", "the exact text", copied)
    // The ack is the whole point of #145 — without it a tap on a monospace label gives no feedback.
    compose.onNodeWithText("COPIED").assertIsDisplayed()

    // ...and it must not stick, or every copied row stays visually "copied" forever.
    compose.mainClock.advanceTimeBy(1_600)
    compose.onNodeWithText("COPY").assertIsDisplayed()
  }

  @Test
  fun assistantTurnsAreAlsoCopyable() {
    var copied: String? = null
    setList(listOf(turn(role = "assistant", content = "a reply")), onCopy = { copied = it })

    compose.onNodeWithText("COPY").performClick()

    assertEquals("a reply", copied)
  }

  // ---- regenerate ------------------------------------------------------------------------------

  @Test
  fun regenPassesBackTheAssistantTurnItWasInvokedOn() {
    var regenerated: ChatTurn? = null
    val assistant = turn(id = "a1", role = "assistant", content = "first answer")
    setList(listOf(turn(id = "u1"), assistant), onRegenerate = { regenerated = it })

    compose.onNodeWithText("REGEN").performClick()

    // Identity matters: regenerating must target the turn whose row was tapped, not "the last turn".
    assertEquals("a1", regenerated?.id)
  }

  @Test
  fun userTurnsOfferNoRegen() {
    setList(listOf(turn(id = "u1")))

    // REGEN belongs to assistant rows only — offering it on a user turn would regenerate the prompt.
    compose.onNodeWithText("REGEN").assertDoesNotExist()
  }

  // ---- edit-and-resend, and the attachment that must survive it --------------------------------

  @Test
  fun editThenResendSendsTheEditedText() {
    var resent: Pair<ChatTurn, String>? = null
    setList(listOf(turn(content = "original")), onEditResend = { t, s -> resent = t to s })

    compose.onNodeWithText("EDIT").performClick()
    compose.onNode(hasSetTextAction()).performTextReplacement("edited")
    compose.onNodeWithText("RESEND").performClick()

    assertEquals("edited", resent?.second)
  }

  /**
   * The acceptance item is "edit-and-resend (attachment preserved on edit)". Preservation is
   * structural — the whole [ChatTurn] is handed back, not just its text — but that is exactly the
   * kind of thing a refactor to `onEditResend(id, text)` would quietly destroy, so pin it.
   */
  @Test
  fun editingPreservesTheAttachmentOnTheResentTurn() {
    var resent: ChatTurn? = null
    setList(
      listOf(turn(content = "look at this", attachmentType = "image", attachmentPath = "/tmp/a.png")),
      onEditResend = { t, _ -> resent = t },
    )

    compose.onNodeWithText("EDIT").performClick()
    compose.onNode(hasSetTextAction()).performTextReplacement("look at this instead")
    compose.onNodeWithText("RESEND").performClick()

    assertEquals("image", resent?.attachmentType)
    assertEquals("/tmp/a.png", resent?.attachmentPath)
  }

  @Test
  fun cancellingAnEditResendsNothingAndRestoresTheActions() {
    var resent: ChatTurn? = null
    setList(listOf(turn(content = "original")), onEditResend = { t, _ -> resent = t })

    compose.onNodeWithText("EDIT").performClick()
    compose.onNode(hasSetTextAction()).performTextReplacement("discarded")
    compose.onNodeWithText("CANCEL").performClick()

    assertNull("CANCEL must not resend", resent)
    // Back to the resting affordances rather than stranded in the editor.
    compose.onNodeWithText("EDIT").assertIsDisplayed()
    compose.onNodeWithText("COPY").assertIsDisplayed()
  }

  @Test
  fun editingOneTurnDoesNotOpenAnEditorOnAnother() {
    setList(listOf(turn(id = "u1", content = "first"), turn(id = "u2", content = "second")))

    compose.onAllNodesWithText("EDIT")[0].performClick()

    // Editor state is per-row `remember`; a shared hoist would put both rows in edit mode.
    assertTrue(
      "exactly one editor should be open",
      compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1,
    )
  }

  // ---- roles render differently (markdown vs plain) ---------------------------------------------

  /**
   * Only the *user* half is asserted here. A user turn is plain `Text`, so its raw markdown must
   * survive verbatim — that is exact and cheap to pin. The assistant half goes through `MarkdownText`
   * (commonmark → richtext), whose output nodes are an implementation detail of that library; an
   * assertion on them would pin the library, not our behaviour. Left to the eye on the device pass.
   */
  @Test
  fun userTurnsRenderTheirMarkdownLiterallyRatherThanRendered() {
    setList(listOf(turn(content = "**not bold** `raw`")))

    compose.onNodeWithText("**not bold** `raw`").assertIsDisplayed()
  }
}
