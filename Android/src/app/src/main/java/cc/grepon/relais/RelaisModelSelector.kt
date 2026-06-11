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

package cc.grepon.relais

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.data.RelaisModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet model selector for the node control panel. Lists the curated, node-runnable models
 * from the allowlist (tap to select → persists a [RelaisModelRef]) and offers a manual repo-id
 * fallback for power users. Self-contained: no Gallery ViewModel / Hilt — it fetches the catalog
 * itself on a background dispatcher. Styled per DESIGN.md (amber on charcoal, monospace, Panel
 * surface, 6dp radius).
 *
 * Phase A is curated-only; a HUGGINGFACE search section slots in below CURATED in Phase B.
 *
 * @param currentModelId the active model id, to mark the selected curated row.
 * @param onPickRef a curated model was tapped — persist its ref and apply on next start.
 * @param onPickManualId a raw repo id was entered — persist it (resolves via the allowlist/HF later).
 * @param onDismiss the sheet was dismissed without a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaisModelSelectorSheet(
  currentModelId: String,
  onPickRef: (RelaisModelRef) -> Unit,
  onPickManualId: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var loading by remember { mutableStateOf(true) }
  var curated by remember { mutableStateOf<List<RelaisModelRef>>(emptyList()) }
  var manualId by remember { mutableStateOf("") }

  // Fetch the curated list off the main thread once when the sheet opens. curatedModels() returns
  // an empty list on offline rather than throwing, so the worst case is the "enter an id" fallback.
  LaunchedEffect(Unit) {
    loading = true
    curated = withContext(Dispatchers.IO) { RelaisModelCatalog.curatedModels() }
    loading = false
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Panel,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "SELECT MODEL",
        color = Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 2.sp,
      )

      SectionLabel("CURATED")
      when {
        loading -> LoadingRow()
        curated.isEmpty() ->
          Text(
            "Allowlist unreachable. Enter a model id below.",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
          )
        else ->
          curated.forEach { ref ->
            CuratedRow(ref = ref, selected = ref.modelId == currentModelId) { onPickRef(ref) }
          }
      }

      Spacer(Modifier.height(2.dp))
      Hairline()
      SectionLabel("OR ENTER A MODEL ID")
      OutlinedTextField(
        value = manualId,
        onValueChange = { manualId = it },
        label = {
          Text("huggingface repo id", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      val trimmed = manualId.trim()
      AmberAction(label = "USE THIS ID ›", enabled = trimmed.isNotEmpty()) {
        onPickManualId(trimmed)
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    color = Muted,
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 1.5.sp,
  )
}

@Composable
private fun CuratedRow(ref: RelaisModelRef, selected: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable { onClick() }
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
        ref.displayName,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (looksGated(ref.modelId)) {
        Spacer(Modifier.width(8.dp))
        Text("token", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
      }
      Spacer(Modifier.width(12.dp))
      Text(
        formatSize(ref.sizeInBytes),
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
      )
      if (selected) {
        Spacer(Modifier.width(10.dp))
        Text("✓", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
      }
    }
  }
}

@Composable
private fun LoadingRow() {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
    CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(12.dp))
    Text("fetching allowlist…", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
  }
}

@Composable
private fun Hairline() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Amber tap affordance mirroring the control panel's "… ›" idiom; dims when disabled. */
@Composable
private fun AmberAction(label: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp))
      .clickable(enabled = enabled) { onClick() }
      .padding(vertical = 4.dp)
  ) {
    Text(
      label,
      color = if (enabled) Amber else Muted,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
    )
  }
}

/**
 * Whether a repo is likely license-gated and so needs a pre-set HF token to download. Heuristic: the
 * codebase treats `litert-community` repos as open and `google/`-prefixed repos as gated (see
 * [RelaisConfig.DEFAULT_MODEL_ID]). Surfacing it on the row keeps a headless operator from picking a
 * model that then silently 401s with no in-UI signal that the HF token field had to be filled first.
 */
private fun looksGated(modelId: String): Boolean = modelId.startsWith("google/")

/** Human-readable file size for a selector row, e.g. `3.6GB`. `-1`/unknown renders as a dash. */
private fun formatSize(bytes: Long): String {
  if (bytes <= 0L) return "—"
  val gb = bytes.toDouble() / 1_000_000_000.0
  if (gb >= 1.0) return "%.1fGB".format(gb)
  val mb = bytes.toDouble() / 1_000_000.0
  return "%.0fMB".format(mb)
}
