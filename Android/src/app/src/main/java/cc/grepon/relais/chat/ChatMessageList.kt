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

package cc.grepon.relais.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.ui.common.BufferedFadingMarkdownText
import cc.grepon.relais.ui.common.MarkdownText
import kotlinx.coroutines.delay

/**
 * Stateless, presentational chat message list (Relais Chat Depth, Task 7a). Renders stored
 * [ChatTurn]s plus an optional in-progress streaming bubble. All mutations (copy, regenerate,
 * edit+resend) are surfaced via callbacks so the caller (Task 7c screen) can wire this to
 * `ChatViewModel`.
 */
@Composable
fun ChatMessageList(
  turns: List<ChatTurn>,
  streamingText: String,
  streaming: Boolean,
  pendingPersistedTurnId: String?,
  onCopy: (String) -> Unit,
  onRegenerate: (ChatTurn) -> Unit,
  onEditResend: (ChatTurn, String) -> Unit,
  // Required, not defaulted: a no-op default would let a caller silently drop speech entirely and
  // still compile, ship, and pass CI with the feature inert.
  speechState: SpeechState,
  speechOffered: Boolean,
  onSpeak: (ChatTurn) -> Unit,
  onStopSpeaking: () -> Unit,
  onSpeechNoticeShown: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  // ChatViewModel holds the streaming bubble up briefly after persisting the assistant turn, until
  // that turn is reflected in [turns] (avoids a flicker frame with neither visible). That leaves a
  // narrow window where both `turns` and `streaming` could be true for the *same* content — guard
  // against rendering the bubble a second time once the persisted turn already covers it. Matched
  // by turn id (not content) so identical consecutive assistant replies can't mis-suppress.
  val showStreamingBubble =
    shouldShowStreamingBubble(streaming, streamingText, turns.lastOrNull()?.id, pendingPersistedTurnId)

  LazyColumn(modifier = modifier, state = listState) {
    items(turns, key = { it.id }) { turn ->
      if (turn.role == "user") {
        UserTurnRow(turn = turn, onCopy = onCopy, onEditResend = onEditResend)
      } else {
        AssistantTurnRow(
          turn = turn,
          onCopy = onCopy,
          onRegenerate = onRegenerate,
          speechState = speechState,
          speechOffered = speechOffered,
          onSpeak = onSpeak,
          onStopSpeaking = onStopSpeaking,
          onSpeechNoticeShown = onSpeechNoticeShown,
        )
      }
    }
    if (showStreamingBubble) {
      item(key = "streaming-in-progress") {
        BufferedFadingMarkdownText(
          text = streamingText,
          inProgress = true,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
      }
    }
  }

  LaunchedEffect(turns.size, streamingText) {
    val count = turns.size + (if (showStreamingBubble) 1 else 0)
    if (count > 0) listState.animateScrollToItem(count - 1)
  }
}

@Composable
private fun UserTurnRow(
  turn: ChatTurn,
  onCopy: (String) -> Unit,
  onEditResend: (ChatTurn, String) -> Unit,
) {
  var editing by remember { mutableStateOf(false) }
  var editedText by remember { mutableStateOf(turn.content) }

  Text(
    text = turn.content,
    color = Paper,
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
  )

  if (editing) {
    OutlinedTextField(
      value = editedText,
      onValueChange = { editedText = it },
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Panel,
          unfocusedContainerColor = Panel,
          focusedIndicatorColor = Amber.copy(alpha = 0.5f),
          unfocusedIndicatorColor = Line,
          cursorColor = Amber,
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ActionLabel(
        text = "RESEND",
        onClick = {
          onEditResend(turn, editedText)
          editing = false
        },
      )
      ActionLabel(text = "CANCEL", onClick = { editing = false })
    }
  } else {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      CopyLabel(text = turn.content, onCopy = onCopy)
      ActionLabel(
        text = "EDIT",
        onClick = {
          editedText = turn.content
          editing = true
        },
      )
    }
  }
}

@Composable
private fun AssistantTurnRow(
  turn: ChatTurn,
  onCopy: (String) -> Unit,
  onRegenerate: (ChatTurn) -> Unit,
  speechState: SpeechState,
  speechOffered: Boolean,
  onSpeak: (ChatTurn) -> Unit,
  onStopSpeaking: () -> Unit,
  onSpeechNoticeShown: (String) -> Unit,
) {
  MarkdownText(
    text = turn.content,
    textColor = Paper,
    linkColor = Amber,
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
  )

  val backend = turn.answeredByBackend
  if (backend != null) {
    Text(
      text = "$backend · ${turn.answeredByModelId}",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
  }

  Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    CopyLabel(text = turn.content, onCopy = onCopy)
    ActionLabel(text = "REGEN", onClick = { onRegenerate(turn) })
    // Speech is offered only when a TTS engine is registered AND this turn is worth reading aloud
    // (an `[error]` turn is not) — otherwise the row keeps its original two actions exactly.
    if (speechOffered && turnIsSpeakable(turn.answeredByBackend, turn.content)) {
      SpeakLabel(
        turn = turn,
        state = speechState,
        onSpeak = onSpeak,
        onStopSpeaking = onStopSpeaking,
        onNoticeShown = onSpeechNoticeShown,
      )
    }
  }
}

/**
 * SPEAK / STOP / status label for one assistant turn (#211). All of the state→label/enablement logic
 * lives in the pure helpers in `ChatSpeech.kt`; this composable only renders and dispatches.
 */
@Composable
private fun SpeakLabel(
  turn: ChatTurn,
  state: SpeechState,
  onSpeak: (ChatTurn) -> Unit,
  onStopSpeaking: () -> Unit,
  onNoticeShown: (String) -> Unit,
) {
  val label = speechActionLabel(state, turn.id)
  val enabled = speechActionEnabled(state, turn.id)
  val stops = speechActionStops(state, turn.id)
  val failed = state is SpeechState.Failed && state.turnId == turn.id

  // SPEECH FAILED clears itself, like the COPIED ack. FETCHING VOICE does NOT — the download far
  // outlasts any notice delay, so it clears only when the voice actually becomes ready.
  if (failed) {
    LaunchedEffect(state) {
      delay(SPEECH_NOTICE_MS)
      onNoticeShown(turn.id)
    }
  }

  ActionLabel(
    text = label,
    color = if (failed) StopRed else if (enabled) Amber else Muted,
    onClick = {
      if (!enabled) return@ActionLabel
      if (stops) onStopSpeaking() else onSpeak(turn)
    },
  )
}

@Composable
private fun ActionLabel(text: String, onClick: () -> Unit, color: Color = Amber) {
  Text(
    text = text,
    color = color,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
  )
}

/** How long a transient speech notice stays up before clearing (matches the COPIED ack's feel). */
private const val SPEECH_NOTICE_MS = 2500L

/** Shared COPY/COPIED action label: shows "COPIED" for ~1.5s after a copy, then reverts. */
@Composable
private fun CopyLabel(text: String, onCopy: (String) -> Unit) {
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  ActionLabel(
    text = if (copied) "COPIED" else "COPY",
    onClick = {
      onCopy(text)
      copied = true
    },
  )
}
