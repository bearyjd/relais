/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This file is part of Relais.
 *
 * Relais is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Relais is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with Relais. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.grepon.relais.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cc.grepon.relais.Amber
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed

/**
 * Reason picker + optional note for reporting AI output (#258).
 *
 * Play's AI-Generated Content policy requires flagging offensive AI output **without leaving the
 * app**, so this is a local dialog, not a `mailto:` or a browser hand-off. The report is written to
 * the device and reviewed in the control panel; nothing is transmitted.
 *
 * The note field hard-stops at [MAX_REPORT_NOTE_CHARS] while typing, so the `NOTE_TOO_LONG`
 * rejection in [buildContentReportDraft] is unreachable from here — that guard remains for non-UI
 * callers. Styling follows `DESIGN.md`: amber on near-black, monospace, no second accent colour.
 */
/**
 * Saves the picked reason across configuration changes by its stable [ReportReason.id] — the same
 * value a report persists, so the saved state cannot drift from what is stored. Saving null means
 * "nothing picked", which restores as null.
 */
private val ReasonSaver: Saver<ReportReason?, String> =
  Saver(save = { it?.id }, restore = { id -> ReportReason.entries.firstOrNull { it.id == id } })

@Composable
fun ContentReportDialog(onDismiss: () -> Unit, onSubmit: (ReportReason, String) -> Unit) {
  // Saveable, not just remembered: a rotation (or the IME resizing the window, or process death
  // while the note field is focused) would otherwise silently discard a reason the operator picked
  // and a note they had typed, with the dialog still open and looking untouched.
  var selected by rememberSaveable(stateSaver = ReasonSaver) { mutableStateOf<ReportReason?>(null) }
  var note by rememberSaveable { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .background(Panel)
          .border(1.dp, Line)
          .padding(horizontal = 20.dp, vertical = 18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = "REPORT OUTPUT",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
      )
      Text(
        text = "Stays on this device. Relais has no server to send it to.",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )

      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReportReason.entries.forEach { reason ->
          ReasonRow(reason = reason, selected = reason == selected, onSelect = { selected = reason })
        }
      }

      NoteField(note = note, onNoteChange = { note = it })

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.End),
      ) {
        DialogAction(text = "CANCEL", color = Muted, onClick = onDismiss)
        // No selection → no click action at all, rather than a control that silently does nothing.
        DialogAction(
          text = "SUBMIT",
          color = if (selected == null) Muted else Amber,
          enabled = selected != null,
          onClick = { selected?.let { onSubmit(it, note) } },
        )
      }
    }
  }
}

@Composable
private fun ReasonRow(reason: ReportReason, selected: Boolean, onSelect: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .semantics { role = Role.RadioButton }
        .clickable(onClick = onSelect)
        .padding(vertical = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // A filled/hollow marker rather than a Material radio — the control panel has no Material
    // chrome and DESIGN.md allows only one accent colour.
    Text(
      text = if (selected) "[x]" else "[ ]",
      color = if (selected) Amber else Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
    )
    Text(
      text = reason.label,
      color = if (selected) Paper else Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
    )
  }
}

@Composable
private fun NoteField(note: String, onNoteChange: (String) -> Unit) {
  val remaining = MAX_REPORT_NOTE_CHARS - note.length
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    BasicTextField(
      value = note,
      // Hard stop at the cap: silently dropping the overflow keeps the field and the validator in
      // agreement, so a submit can never be refused for a length the user cannot see.
      onValueChange = { if (it.length <= MAX_REPORT_NOTE_CHARS) onNoteChange(it) },
      textStyle =
        TextStyle(color = Paper, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
      cursorBrush = SolidColor(Amber),
      modifier = Modifier.fillMaxWidth().border(1.dp, Line).padding(10.dp),
      decorationBox = { inner ->
        if (note.isEmpty()) {
          Text(
            text = "What was wrong? (optional)",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
          )
        }
        inner()
      },
    )
    Text(
      text = "$remaining left",
      color = if (remaining == 0) StopRed else Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
  }
}

@Composable
private fun DialogAction(
  text: String,
  color: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  Text(
    text = text,
    color = color,
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    modifier =
      Modifier.semantics { role = Role.Button }
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 4.dp),
  )
}
