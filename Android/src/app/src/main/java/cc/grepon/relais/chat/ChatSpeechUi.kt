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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.grepon.relais.Amber
import cc.grepon.relais.Muted

/**
 * Screen-level speech affordances for chat (#211), kept out of `RelaisChatActivity` so they can be
 * driven directly by `ChatSpeechUiProbe` instead of by tap coordinates — adb taps drift badly on the
 * foldables this repo targets.
 */

/** testTag for the screen-level STOP strip, so the probe can find it without matching on copy. */
const val SPEAKING_STRIP_TAG = "speaking-stop-strip"

/**
 * The "speaking… STOP" strip shown while any turn is being read aloud.
 *
 * Exists because the per-row STOP label lives inside a `LazyColumn` item: once the speaking turn
 * scrolls out of view its row leaves composition, and without this strip the audio would keep
 * playing with no way to stop it.
 *
 * Renders nothing unless [speechState] is [SpeechState.Speaking].
 */
@Composable
fun SpeakingStopStrip(speechState: SpeechState, onStop: () -> Unit) {
  if (speechState !is SpeechState.Speaking) return
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(SPEAKING_STRIP_TAG),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "speaking…",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      modifier = Modifier.weight(1f),
    )
    Text(
      "STOP",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.clickable(onClick = onStop),
    )
  }
}

/**
 * Invokes [onRefresh] when this composable enters composition and on every subsequent `ON_RESUME`.
 *
 * Needed because the TTS engine registers at **node** startup (`TtsRegistration` ←
 * `RelaisNodeService`), not app startup — so speech availability can change after the ViewModel was
 * constructed, e.g. the operator starts the node from DASHBOARD and returns to CHAT. Without this,
 * SPEAK stays hidden until the app is restarted.
 *
 * [onRefresh] is captured via [rememberUpdatedState] so a recomposition with a new lambda doesn't
 * tear down and re-register the observer.
 */
@Composable
fun RefreshOnResume(onRefresh: () -> Unit) {
  val currentOnRefresh by rememberUpdatedState(onRefresh)
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    currentOnRefresh()
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) currentOnRefresh()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}
