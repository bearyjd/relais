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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.StopRed

/**
 * The chat composer's primary action, extracted from `RelaisChatActivity` (#146) for the same reason
 * [SpeakingStopStrip] was: inline in the screen it could only be driven by tap coordinates, which
 * drift badly on the foldables this repo targets, so its behaviour went unverified.
 *
 * The behaviour acceptance actually cares about is the label/enablement rule, and it is genuinely
 * non-obvious: while [streaming] the button is ALWAYS enabled regardless of [canSend], because its
 * job flips from "submit this draft" to "stop the reply in progress" — a disabled STOP would strand
 * the user in a stream they cannot cancel.
 *
 * @param streaming whether a reply is currently being generated.
 * @param canSend whether a send would be valid right now — i.e. the model is not reloading AND
 *   there is something to send (non-blank draft or a pending attachment). Ignored while [streaming].
 */
@Composable
fun SendStopButton(
  streaming: Boolean,
  canSend: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  Button(
    onClick = { if (streaming) onStop() else onSend() },
    enabled = streaming || canSend,
    shape = RoundedCornerShape(6.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = if (streaming) StopRed else Amber,
        contentColor = Charcoal,
        disabledContainerColor = Line,
        disabledContentColor = Muted,
      ),
  ) {
    Text(
      if (streaming) "STOP" else "SEND",
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
      letterSpacing = 1.sp,
    )
  }
}
