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

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.data.Conversation

/**
 * Stateless, presentational conversation drawer (Relais Chat Depth, Task 7b). Lists stored
 * [Conversation]s with new/open/rename/delete affordances; all mutations are surfaced via
 * callbacks so the caller (Task 7c screen) can wire this to `ChatViewModel`.
 */
@Composable
fun ChatConversationList(
  conversations: List<Conversation>,
  activeId: String?,
  onOpen: (String) -> Unit,
  onNew: () -> Unit,
  onRename: (String, String) -> Unit,
  onDelete: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.background(Panel).fillMaxHeight()) {
    Text(
      text = "＋ NEW CHAT",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
      modifier = Modifier.fillMaxWidth().clickable(onClick = onNew).padding(12.dp),
    )
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
      items(conversations, key = { it.id }) { conversation ->
        ConversationRow(
          conversation = conversation,
          active = conversation.id == activeId,
          onOpen = onOpen,
          onRename = onRename,
          onDelete = onDelete,
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
      }
    }
  }
}

@Composable
private fun ConversationRow(
  conversation: Conversation,
  active: Boolean,
  onOpen: (String) -> Unit,
  onRename: (String, String) -> Unit,
  onDelete: (String) -> Unit,
) {
  var renaming by remember(conversation.id) { mutableStateOf(false) }
  var renameText by remember(conversation.id) { mutableStateOf(conversation.title) }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = { onOpen(conversation.id) })
        .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp))
  ) {
    Text(
      text = if (active) "▸ ${conversation.title}" else conversation.title,
      color = if (active) Amber else Paper,
      fontFamily = FontFamily.Monospace,
      fontSize = 14.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )

    if (renaming) {
      OutlinedTextField(
        value = renameText,
        onValueChange = { renameText = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RowActionLabel(
          text = "CONFIRM",
          onClick = {
            onRename(conversation.id, renameText)
            renaming = false
          },
        )
        RowActionLabel(text = "CANCEL", onClick = { renaming = false })
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RowActionLabel(
          text = "RENAME",
          onClick = {
            renameText = conversation.title
            renaming = true
          },
        )
        RowActionLabel(text = "DELETE", onClick = { onDelete(conversation.id) })
      }
    }
  }
}

@Composable
private fun RowActionLabel(text: String, onClick: () -> Unit) {
  Text(
    text = text,
    color = Amber,
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
  )
}
