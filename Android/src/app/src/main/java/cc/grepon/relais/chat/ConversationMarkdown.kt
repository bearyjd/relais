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

import cc.grepon.relais.data.ChatTurn

/**
 * Renders a conversation as plain Markdown for share/export (Task 8, Chat Depth). Pure and
 * side-effect free: no engine metadata (model id / backend) is included, only role + content, with
 * a `[attachmentType]` placeholder appended when a turn carried an attachment. Code fences already
 * present in [ChatTurn.content] are preserved verbatim.
 */
fun conversationToMarkdown(title: String, turns: List<ChatTurn>): String {
  val body =
    turns.joinToString("\n\n") { turn ->
      val roleLabel = if (turn.role == "assistant") "**Assistant:**" else "**User:**"
      val attachmentSuffix = turn.attachmentType?.let { " [$it]" } ?: ""
      "$roleLabel ${turn.content}$attachmentSuffix"
    }
  return "# $title\n\n$body"
}
