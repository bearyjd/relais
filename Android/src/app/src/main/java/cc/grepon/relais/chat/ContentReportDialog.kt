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
import androidx.compose.foundation.selection.toggleable
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
 * app**, so this is a local dialog, not a `mailto:` or a browser hand-off. The report is always
 * written to the device and reviewed in the control panel; [onSubmit]'s third parameter is the
 * operator's separate, explicit opt-in to also send it to the maintainer ([ContentReportDelivery]) —
 * default off, decided per report, never automatic.
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
fun ContentReportDialog(onDismiss: () -> Unit, onSubmit: (ReportReason, String, Boolean) -> Unit) {
  // Saveable, not just remembered: a rotation (or the IME resizing the window, or process death
  // while the note field is focused) would otherwise silently discard a reason the operator picked
  // and a note they had typed, with the dialog still open and looking untouched.
  var selected by rememberSaveable(stateSaver = ReasonSaver) { mutableStateOf<ReportReason?>(null) }
  var note by rememberSaveable { mutableStateOf("") }
  // Default OFF: the send is an explicit, per-report opt-in (#258 gate 1), never automatic.
  var sendToDeveloper by rememberSaveable { mutableStateOf(false) }

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
        text = "Saved on this device. Sent to the developer only if you check the box below.",
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

      SendToggleRow(checked = sendToDeveloper, onToggle = { sendToDeveloper = !sendToDeveloper })

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
          onClick = { selected?.let { onSubmit(it, note, sendToDeveloper) } },
        )
      }
    }
  }
}

@Composable
private fun SendToggleRow(checked: Boolean, onToggle: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          // `toggleable`, not a bare `clickable` + Role.Checkbox: it announces on/off state to
          // TalkBack, not just the role. That distinction matters more here than on ReasonRow's
          // radio buttons — this control is the consent gate for sending data off-device, so a
          // screen-reader user being unable to confirm the state before SUBMIT is a materially
          // worse outcome than for picking a reason.
          .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
          .padding(vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = if (checked) "[x]" else "[ ]",
        color = if (checked) Amber else Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
      Text(
        text = "ALSO SEND TO DEVELOPER",
        color = if (checked) Paper else Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
    }
    Text(
      text = "Sends the flagged output, your note, the reason you picked, the model/mode that produced it, and which chat surface it came from.",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
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
