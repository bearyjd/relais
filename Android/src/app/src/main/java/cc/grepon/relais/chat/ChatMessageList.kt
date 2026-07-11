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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.font.FontFamily
import androidx.compose.ui.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Muted
import cc.grepon.relais.Paper
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.ui.common.BufferedFadingMarkdownText
import cc.grepon.relais.ui.common.MarkdownText

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
  onCopy: (String) -> Unit,
  onRegenerate: (ChatTurn) -> Unit,
  onEditResend: (ChatTurn, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(modifier = modifier) {
    items(turns, key = { it.id }) { turn ->
      if (turn.role == "user") {
        UserTurnRow(turn = turn, onCopy = onCopy, onEditResend = onEditResend)
      } else {
        AssistantTurnRow(turn = turn, onCopy = onCopy, onRegenerate = onRegenerate)
      }
    }
    if (streaming) {
      item(key = "streaming-in-progress") {
        BufferedFadingMarkdownText(
          text = streamingText,
          inProgress = true,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
      }
    }
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
      ActionLabel(text = "COPY", onClick = { onCopy(turn.content) })
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
    ActionLabel(text = "COPY", onClick = { onCopy(turn.content) })
    ActionLabel(text = "REGEN", onClick = { onRegenerate(turn) })
  }
}

@Composable
private fun ActionLabel(text: String, onClick: () -> Unit) {
  Text(
    text = text,
    color = Amber,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
  )
}
