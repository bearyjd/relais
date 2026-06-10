/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.relais

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

private const val TAG = "RelaisControl"

// --- Relais brand palette: amber signal relay on near-black ---
private val Amber = Color(0xFFFFB000)
private val Charcoal = Color(0xFF0B0B0D)
private val Panel = Color(0xFF16171A)
private val Line = Color(0xFF2A2B30)
private val Paper = Color(0xFFEDEAE3)
private val Muted = Color(0xFF8A8780)
private val StopRed = Color(0xFFFF5247)

/**
 * Relais node control panel. Tapping the "Relais Node" launcher icon opens this; it shows whether
 * the node is actually serving (LIVE / STARTING / OFFLINE), the real LAN endpoints + API key, and
 * lets the operator pick the model and start/stop the server.
 *
 * Also honors `--es cmd start|stop` for adb automation. The activity is exported (launchable), so
 * the `cmd` extra is gated behind the node's API key; the in-app buttons call [RelaisNodeService]
 * directly. Optional `--es modelId <id>` / `--es hfToken <t>` (also key-gated) switch the model.
 *
 *   adb shell am start -n <appId>/com.google.ai.edge.gallery.relais.RelaisControlActivity \
 *     --es cmd start --es token <apiKey> [--es modelId <allowlistId>] [--es hfToken <hfToken>]
 */
class RelaisControlActivity : ComponentActivity() {
  private fun handleCmd(intent: Intent?) {
    val cmd = intent?.getStringExtra("cmd") ?: return
    // Gate external start/stop: reject unless the caller presents the node's API key. Prevents a
    // co-installed app from toggling the service (loading a multi-GB model, binding the endpoint).
    if (intent.getStringExtra("token") != RelaisConfig.apiKey(this)) {
      Log.w(TAG, "Ignoring cmd=$cmd from intent: missing/invalid token")
      return
    }
    intent.getStringExtra("modelId")?.takeIf { it.isNotBlank() }?.let { RelaisConfig.setModelId(this, it) }
    intent.getStringExtra("hfToken")?.let { RelaisConfig.setHfToken(this, it.ifBlank { null }) }
    when (cmd) {
      "start" -> RelaisNodeService.start(this)
      "stop" -> RelaisNodeService.stop(this)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleCmd(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleCmd(intent)
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
          val ctx = this@RelaisControlActivity
          var ready by remember { mutableStateOf(RelaisEngine.isReady) }
          var running by remember { mutableStateOf(RelaisConfig.shouldRun(ctx)) }
          var modelId by remember { mutableStateOf(RelaisConfig.modelId(ctx)) }
          var hfToken by remember { mutableStateOf(RelaisConfig.hfToken(ctx) ?: "") }
          var savedNote by remember { mutableStateOf("") }
          val ip = remember { lanIpv4() }
          LaunchedEffect(Unit) {
            while (true) {
              ready = RelaisEngine.isReady
              running = RelaisConfig.shouldRun(ctx)
              delay(1000)
            }
          }

          val statusText = if (ready) "LIVE" else if (running) "STARTING" else "OFFLINE"
          val statusColor = if (ready) Amber else if (running) Amber.copy(alpha = 0.6f) else Muted

          // Beacon pulse only while live.
          val pulse =
            if (ready) {
              val t = rememberInfiniteTransition(label = "beacon")
              t.animateFloat(
                  initialValue = 0.3f,
                  targetValue = 1f,
                  animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                  label = "alpha",
                )
                .value
            } else 1f

          Column(
            modifier =
              Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            // Wordmark + live status
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier =
                  Modifier.size(11.dp).clip(CircleShape)
                    .background(statusColor.copy(alpha = pulse))
              )
              Spacer(Modifier.width(10.dp))
              Text(
                "RELAIS",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 5.sp,
              )
              Spacer(Modifier.weight(1f))
              Text(
                statusText,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
              )
            }
            Text(
              "on-device relay · OpenAI-compatible LAN endpoint",
              color = Muted,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
            )

            Divider()
            Readout("STATUS", if (ready) "engine resident" else if (running) "starting…" else "stopped")
            Readout("LAN (https)", "$ip:8443")
            Readout("LOCAL (http)", "127.0.0.1:8080")
            Spacer(Modifier.height(2.dp))
            AccessKeyChip(apiKey = RelaisConfig.apiKey(ctx), baseUrl = "https://$ip:8443/v1")
            Divider()

            OutlinedTextField(
              value = modelId,
              onValueChange = { modelId = it; savedNote = "" },
              label = { Text("MODEL ID", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
              textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
              value = hfToken,
              onValueChange = { hfToken = it; savedNote = "" },
              label = { Text("HF TOKEN (gated repos only)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
              textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
              onClick = {
                RelaisConfig.setModelId(ctx, modelId.trim())
                RelaisConfig.setHfToken(ctx, hfToken.trim().ifBlank { null })
                savedNote = "Saved. Restart to apply (an un-downloaded model downloads on Start)."
              },
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
              Text("SAVE MODEL", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            if (savedNote.isNotEmpty()) {
              Text(savedNote, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(
                onClick = { RelaisNodeService.start(ctx) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Charcoal),
                modifier = Modifier.weight(1f),
              ) {
                Text("START", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
              }
              OutlinedButton(
                onClick = { RelaisNodeService.stop(ctx) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StopRed),
                modifier = Modifier.weight(1f),
              ) {
                Text("STOP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
              }
            }
          }
        }
      }
    }
  }
}

/** A control-panel readout row: muted mono label on the left, mono value on the right. */
@Composable
private fun Readout(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
    Spacer(Modifier.width(16.dp))
    Text(
      value,
      color = Paper,
      fontFamily = FontFamily.Monospace,
      fontSize = 13.sp,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/**
 * The node's bearer key as a tap-to-copy chip, plus a share action. Tapping the chip copies the
 * raw key to the clipboard; "SHARE CONNECTION" opens the system share sheet with the base URL +
 * key (a bare key is useless without the endpoint).
 */
@Composable
private fun AccessKeyChip(apiKey: String, baseUrl: String) {
  val clipboard = LocalClipboardManager.current
  val ctx = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("ACCESS KEY", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(6.dp))
          .border(BorderStroke(1.dp, Amber.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
          .clickable {
            clipboard.setText(AnnotatedString(apiKey))
            copied = true
          }
          .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(apiKey, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
          if (copied) "COPIED" else "TAP TO COPY",
          color = Amber,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
        )
      }
    }
    Box(
      modifier =
        Modifier.clip(RoundedCornerShape(6.dp))
          .clickable {
            val text = "Relais node\nBase URL: $baseUrl\nAPI key: $apiKey"
            val send =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
              }
            ctx.startActivity(Intent.createChooser(send, "Share Relais connection"))
          }
          .padding(vertical = 4.dp)
    ) {
      Text("SHARE CONNECTION ›", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
  }
}

/** Best-effort LAN IPv4 (prefers wlan), for showing the real endpoint URLs. */
private fun lanIpv4(): String {
  return runCatching {
    val nis = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    val ordered = nis.sortedByDescending { it.name.startsWith("wlan") }
    for (ni in ordered) {
      for (addr in ni.inetAddresses) {
        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: continue
      }
    }
    "0.0.0.0"
  }.getOrDefault("0.0.0.0")
}
