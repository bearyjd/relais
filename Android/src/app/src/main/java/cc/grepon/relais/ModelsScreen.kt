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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Full-screen MODELS destination (Unified App Shell, Task 5): shows the currently served model and
 * opens [RelaisModelSelectorSheet] to change it. Mirrors [RelaisConfigureActivity]'s
 * `showModelSheet` pattern — the selector is a `ModalBottomSheet`, so this screen is just a header
 * row plus the affordance that toggles it open. Persistence goes straight through [RelaisConfig],
 * same as Configure's `onPickRef`/`onPickManualId` bodies. Not wired into a NavHost yet — that's
 * Task 6. Deliberately not wrapped in `RelaisTheme`; the shell provides that once.
 */
@Composable
fun ModelsScreen() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var modelId by remember { mutableStateOf(RelaisConfig.modelId(ctx)) }
  var modelRef by remember { mutableStateOf(RelaisConfig.modelRef(ctx)) }
  var showSheet by remember { mutableStateOf(false) }
  var reloading by remember { mutableStateOf(false) }

  // Mirror the in-chat selector's reload feedback (both routes persist through [ModelSwitch]): after
  // a pick, show "reloading model…" until the engine settles, so this screen and the chat sheet
  // behave identically instead of ModelsScreen switching silently.
  fun observeReload() {
    reloading = true
    scope.launch { reloading = !ModelSwitch.awaitReload() }
  }

  Column(
    modifier = Modifier.systemBarsPadding().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "MODEL",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      letterSpacing = 1.5.sp,
    )
    Text(
      modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId,
      color = Paper,
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
    )
    Text(
      "CHANGE MODEL ›",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
      modifier =
        Modifier.clip(RoundedCornerShape(6.dp)).clickable { showSheet = true }.padding(vertical = 4.dp),
    )
    if (reloading) {
      Text(
        "reloading model — $modelId…",
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
      )
    }
  }

  if (showSheet) {
    RelaisModelSelectorSheet(
      currentModelId = modelId,
      // The saved token (not an editable field here): HF resolve and the later download both
      // authenticate with the persisted token, mirroring RelaisConfigureActivity.
      hfToken = RelaisConfig.hfToken(ctx),
      onPickRef = { ref ->
        ModelSwitch.applyRef(ctx, ref)
        modelRef = ref
        modelId = ref.modelId
        showSheet = false
        observeReload()
      },
      onPickManualId = { id ->
        // Entering a raw id is an explicit "resolve this via the allowlist" intent; [ModelSwitch]
        // drops any curated ref first so the pinned ref can't keep overriding allowlist resolution.
        ModelSwitch.applyManualId(ctx, id)
        modelRef = null
        modelId = id
        showSheet = false
        observeReload()
      },
      onDismiss = { showSheet = false },
    )
  }
}
