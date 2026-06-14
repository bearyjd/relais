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

package cc.grepon.relais.templates

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed

/**
 * In-app prompt-template editor (Feature #12). Lists the library from [PromptTemplateStore.all]:
 * built-ins are shown read-only (locked, no delete); customs are editable and deletable. The form
 * creates or edits a custom template. Styled per DESIGN.md (amber on charcoal, monospace, Panel
 * surface, 6dp radius) — mirrors `RelaisControlActivity` / `RelaisModelSelectorSheet`.
 *
 * Internal-only: launched solely from `RelaisControlActivity` (`android:exported="false"`). It is a
 * thin shell — every decision (validation, id derivation, edit-ability) goes through the pure
 * [TemplateEditorLogic]; the store enforces the same caps, so the worst a malformed input does is
 * surface an inline error, never a crash.
 */
class PromptTemplateEditorActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(
        colorScheme =
          darkColorScheme(
            primary = Amber,
            onPrimary = Charcoal,
            background = Charcoal,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            error = StopRed,
          )
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) {
          TemplateEditorScreen()
        }
      }
    }
  }
}

@Composable
private fun TemplateEditorScreen() {
  val ctx = LocalContext.current

  // The library, mirrored into a snapshot list the screen mutates after each store write so the UI
  // reflects the canonical store without a recomposition trigger of its own.
  val templates = remember { mutableStateListOf<PromptTemplate>().apply { addAll(PromptTemplateStore.all(ctx)) } }
  fun reload() {
    templates.clear()
    templates.addAll(PromptTemplateStore.all(ctx))
  }

  // Form state. A null `editingId` means "creating a new template"; a non-null id means we're editing
  // that existing custom row (so the cap doesn't apply and the id is preserved on save).
  var editingId by remember { mutableStateOf<String?>(null) }
  var name by remember { mutableStateOf("") }
  var system by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmingDelete by remember { mutableStateOf(false) }

  fun startNew() {
    editingId = null
    name = ""
    system = ""
    error = null
    confirmingDelete = false
  }

  fun startEdit(t: PromptTemplate) {
    editingId = t.id
    name = t.name
    system = t.system
    error = null
    confirmingDelete = false
  }

  fun save() {
    val isNew = editingId == null
    val customCount = templates.count { !it.builtin }
    // Validate + slug the TRIMMED name (that's what gets stored), so a name that only exceeds the
    // length cap because of trailing whitespace isn't wrongly rejected as NameTooLong.
    val trimmedName = name.trim()
    when (validateTemplateForm(trimmedName, system, isNew, customCount)) {
      TemplateFormValidation.NameBlank -> { error = "Name is required."; return }
      TemplateFormValidation.NameTooLong -> { error = "Name is too long (max ${PromptTemplateStore.MAX_NAME})."; return }
      TemplateFormValidation.SystemTooLong -> { error = "Prompt is too long (max ${PromptTemplateStore.MAX_SYSTEM})."; return }
      TemplateFormValidation.AtCapacity -> { error = "At capacity (${PromptTemplateStore.CUSTOM_CAP} custom templates)."; return }
      TemplateFormValidation.Ok -> Unit
    }
    val existingIds = templates.map { it.id }.toSet()
    val id = editingId ?: slugifyTemplateId(trimmedName, existingIds)
    // builtin=false: the store also forces this, but be explicit — the UI never claims built-in status.
    val ok = PromptTemplateStore.upsert(ctx, PromptTemplate(id = id, name = trimmedName, system = system, builtin = false))
    if (!ok) {
      error = "Could not save (built-in id or store full)."
      return
    }
    reload()
    startNew()
  }

  fun delete() {
    val id = editingId ?: return
    PromptTemplateStore.delete(ctx, id)
    reload()
    startNew()
  }

  Column(
    modifier =
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      "PROMPT TEMPLATES",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      letterSpacing = 3.sp,
    )
    Text(
      "system-prompt presets · select via the API's template field",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )

    Divider()
    SectionLabel("LIBRARY")
    templates.forEach { t ->
      TemplateRow(
        template = t,
        selected = t.id == editingId,
        onEdit = { startEdit(t) },
      )
    }

    Spacer(Modifier.height(2.dp))
    Divider()
    SectionLabel(if (editingId == null) "NEW TEMPLATE" else "EDIT TEMPLATE")

    OutlinedTextField(
      value = name,
      onValueChange = { name = it; error = null },
      label = { Text("NAME (${name.length}/${PromptTemplateStore.MAX_NAME})", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
      textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = system,
      onValueChange = { system = it; error = null },
      label = { Text("SYSTEM PROMPT (${system.length}/${PromptTemplateStore.MAX_SYSTEM})", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
      textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
      minLines = 4,
      modifier = Modifier.fillMaxWidth(),
    )

    error?.let {
      // Amber (not Muted) so a rejected save is legible; StopRed stays reserved for destructive actions.
      Text(it, color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = { save() },
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Charcoal),
        modifier = Modifier.weight(1f),
      ) {
        Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
      }
      if (editingId != null) {
        OutlinedButton(
          onClick = { confirmingDelete = true },
          shape = RoundedCornerShape(6.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = StopRed),
          modifier = Modifier.weight(1f),
        ) {
          Text("DELETE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
      }
    }
    if (editingId != null) {
      ActionLink("+ NEW TEMPLATE") { startNew() }
    }

    if (confirmingDelete) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Delete \"$name\"? This can't be undone.",
          color = Paper,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(
            onClick = { delete() },
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StopRed),
          ) {
            Text("CONFIRM DELETE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
          }
          ActionLink("CANCEL") { confirmingDelete = false }
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp)
}

/**
 * One library row. Built-ins carry a muted lock and are not tappable (read-only); customs show an
 * amber ▸ and open in the editor on tap. The selected row (being edited) is marked with an amber dot.
 */
@Composable
private fun TemplateRow(template: PromptTemplate, selected: Boolean, onEdit: () -> Unit) {
  val editable = isEditable(template)
  Box(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable(enabled = editable) { onEdit() }
      .padding(vertical = 8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        if (selected) "●" else "○",
        color = if (selected) Amber else Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
      )
      Spacer(Modifier.width(12.dp))
      Text(
        template.name,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(12.dp))
      if (editable) {
        Text("▸", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
      } else {
        // Built-in: locked badge instead of an edit affordance.
        Box(
          Modifier.clip(RoundedCornerShape(4.dp))
            .background(Charcoal)
        ) {
          Text(
            "LOCKED",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Amber tap affordance — matches the control panel's "… ›" idiom. */
@Composable
private fun ActionLink(label: String, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { onClick() }.padding(vertical = 4.dp)) {
    Text(
      label,
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
    )
  }
}
