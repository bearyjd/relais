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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bottom-sheet model selector for the node control panel. Three ways to choose a model: the curated
 * node-runnable models from the allowlist, a free-text **HuggingFace search** that resolves any
 * `.litertlm` repo to a provisionable ref, and a manual repo-id fallback for power users. A curated
 * or HF pick persists a self-contained [RelaisModelRef]. Self-contained: no Gallery ViewModel / Hilt
 * — it does its own fetches on a background dispatcher. Styled per DESIGN.md (amber on charcoal,
 * monospace, Panel surface, 6dp radius).
 *
 * @param currentModelId the active model id, to mark the selected curated row.
 * @param hfToken the saved HuggingFace token (for gated repos); passed as a Bearer to HF resolve.
 * @param onPickRef a curated or resolved-HF model was chosen — persist its ref and apply on restart.
 * @param onPickManualId a raw repo id was entered — persist it (resolves via the allowlist later).
 * @param onDismiss the sheet was dismissed without a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaisModelSelectorSheet(
  currentModelId: String,
  hfToken: String?,
  onPickRef: (RelaisModelRef) -> Unit,
  onPickManualId: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(true) }
  var curated by remember { mutableStateOf<List<RelaisModelRef>>(emptyList()) }
  var manualId by remember { mutableStateOf("") }

  // HuggingFace search state. `searching`/`hfSearched` drive the spinner vs "no results"; a non-null
  // `resolvingId` marks the row whose two-round-trip resolve is in flight (and blocks a second tap);
  // `hfError` carries the inline gated / no-.litertlm / offline message.
  var hfQuery by remember { mutableStateOf("") }
  var hfHits by remember { mutableStateOf<List<RelaisHuggingFace.HfHit>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  var hfSearched by remember { mutableStateOf(false) }
  var resolvingId by remember { mutableStateOf<String?>(null) }
  var hfError by remember { mutableStateOf<String?>(null) }

  // Debounced search: each keystroke restarts this effect, cancelling the prior 400ms delay before
  // it fires, so only a settled query hits the network. Deliberately does NOT auto-fire on open — a
  // sovereign/local-first node must not egress to huggingface.co just because the selector opened;
  // the operator types to opt in. A full repo id ("org/repo") is left to the RESOLVE action below,
  // so it isn't also auto-searched (one intent, one request).
  LaunchedEffect(hfQuery) {
    val q = hfQuery.trim()
    hfError = null
    if (q.length < 2 || (q.contains('/') && !q.contains(Regex("\\s")))) {
      hfHits = emptyList()
      hfSearched = false
      searching = false
      return@LaunchedEffect
    }
    searching = true // show the spinner during the debounce settle, not just the network call
    delay(400)
    hfHits = withContext(Dispatchers.IO) { RelaisHuggingFace.search(q, hfToken) }
    hfSearched = true
    searching = false
  }

  // Resolve a tapped/pasted repo id off the main thread, then either pick its ref (dismisses) or
  // surface why it can't be served. Ignores re-entrancy while a resolve is already running.
  fun resolveAndPick(id: String) {
    if (resolvingId != null) return
    hfError = null
    resolvingId = id
    scope.launch {
      val result = withContext(Dispatchers.IO) { RelaisHuggingFace.resolve(id, hfToken) }
      resolvingId = null
      when (result) {
        is RelaisHuggingFace.ResolveResult.Success -> onPickRef(result.ref)
        RelaisHuggingFace.ResolveResult.Gated -> hfError = "\"$id\" is gated — set the HF token, then retry."
        RelaisHuggingFace.ResolveResult.NoLiteRtLm -> hfError = "\"$id\" has no .litertlm file — not node-runnable."
        is RelaisHuggingFace.ResolveResult.Error -> hfError = result.message
      }
    }
  }

  // Fetch the curated list off the main thread once when the sheet opens. curatedModels() returns
  // an empty list on offline rather than throwing, so the worst case is the "enter an id" fallback.
  LaunchedEffect(Unit) {
    loading = true
    // Bound the spinner: if the allowlist is slow/unreachable, fall through to the manual-id path.
    // getJsonResponse already sets connect/read timeouts (≤30s); this tighter 8s bound just resolves
    // the spinner sooner. (withTimeoutOrNull cancels the coroutine but can't interrupt the blocking
    // socket — the connection timeouts are what ultimately free the IO thread.)
    curated =
      withTimeoutOrNull(8_000) { withContext(Dispatchers.IO) { RelaisModelCatalog.curatedModels() } }
        ?: emptyList()
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
      SectionLabel("HUGGINGFACE")
      OutlinedTextField(
        value = hfQuery,
        onValueChange = { hfQuery = it },
        label = {
          Text("search or paste a repo id", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      // A typed full repo id (`org/name`, no spaces) gets a direct "resolve this exact repo" action
      // — the "paste a repo id" affordance — distinct from tapping a search hit.
      val hfTrimmed = hfQuery.trim()
      val looksLikeId = hfTrimmed.contains('/') && !hfTrimmed.contains(Regex("\\s"))
      if (looksLikeId) {
        AmberAction(label = "RESOLVE $hfTrimmed ›", enabled = resolvingId == null) {
          resolveAndPick(hfTrimmed)
        }
      }
      hfError?.let { ErrorRow(it) }
      when {
        searching -> LoadingRow("searching huggingface…")
        // Idle (nothing typed yet, no exact id to resolve): no network has happened — invite a search.
        !hfSearched && hfError == null && !looksLikeId ->
          Text(
            "Type to search HuggingFace.",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
          )
        hfHits.isEmpty() && hfSearched && hfError == null ->
          Text(
            "No results.",
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
          )
        else ->
          hfHits.forEach { hit ->
            val id = hit.id ?: return@forEach
            HfRow(hit = hit, id = id, resolving = resolvingId == id) { resolveAndPick(id) }
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
      // Reject blank or whitespace-containing input — a malformed id only fails opaquely on a
      // headless node. Don't try to model HF's id grammar (it evolves); just keep obvious garbage out.
      AmberAction(
        label = "USE THIS ID ›",
        enabled = trimmed.isNotEmpty() && !trimmed.contains(Regex("\\s")),
      ) {
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
private fun LoadingRow(label: String = "fetching allowlist…") {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
    CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(12.dp))
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
  }
}

/** A HuggingFace search hit: repo id, a gated badge from the real `gated` field, and a download
 * count — or, while its resolve is in flight, an amber spinner in place of the count. */
@Composable
private fun HfRow(hit: RelaisHuggingFace.HfHit, id: String, resolving: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable(enabled = !resolving) { onClick() }
      .padding(vertical = 8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("○", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
      Spacer(Modifier.width(12.dp))
      Text(
        id,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      // Real HF `gated` field — retires Phase A's startsWith("google/") heuristic for HF results.
      if (RelaisHuggingFace.isGated(hit.gated)) {
        Spacer(Modifier.width(8.dp))
        Text("token", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
      }
      Spacer(Modifier.width(12.dp))
      if (resolving) {
        CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
      } else {
        Text(
          formatCount(hit.downloads),
          color = Muted,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
        )
      }
    }
  }
}

/**
 * Inline status line (gated / no-.litertlm / offline). Muted, matching the curated-offline hint —
 * DESIGN.md reserves StopRed exclusively for the Stop action, so an error here must not use it.
 */
@Composable
private fun ErrorRow(message: String) {
  Text(
    message,
    color = Muted,
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    modifier = Modifier.padding(vertical = 2.dp),
  )
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

/** Compact download count for an HF hit, e.g. `12k ↓`. Null/zero renders empty (no signal). */
private fun formatCount(downloads: Long?): String {
  val n = downloads ?: return ""
  return when {
    n <= 0L -> ""
    n >= 1_000_000L -> "%.1fM ↓".format(n / 1_000_000.0)
    n >= 1_000L -> "%.0fk ↓".format(n / 1_000.0)
    else -> "$n ↓"
  }
}
