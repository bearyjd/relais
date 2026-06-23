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

package cc.grepon.relais

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.nfc.NfcWriteActivity
import cc.grepon.relais.templates.PromptTemplateEditorActivity
import cc.grepon.relais.triage.TriageControlActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

private const val TAG = "RelaisControl"

// Brand palette (Amber/Charcoal/Panel/Line/Paper/Muted/StopRed) lives in RelaisPalette.kt — the
// single DESIGN.md-traceable source shared with the model selector.

/**
 * Relais node control panel. Tapping the "Relais Node" launcher icon opens this; it shows whether
 * the node is actually serving (LIVE / STARTING / OFFLINE), the real LAN endpoints + API key, and
 * lets the operator pick the model and start/stop the server.
 *
 * Also honors `--es cmd start|stop` for adb automation. The activity is exported (launchable), so
 * the `cmd` extra is gated behind the node's API key; the in-app buttons call [RelaisNodeService]
 * directly. Optional `--es modelId <id>` / `--es hfToken <t>` (also key-gated) switch the model.
 *
 *   adb shell am start -n <appId>/cc.grepon.relais.RelaisControlActivity \
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
          var modelRef by remember { mutableStateOf(RelaisConfig.modelRef(ctx)) }
          var showModelSheet by remember { mutableStateOf(false) }
          var hfToken by remember { mutableStateOf(RelaisConfig.hfToken(ctx) ?: "") }
          var shareEnabled by remember { mutableStateOf(RelaisConfig.shareEnabled(ctx)) }
          val nfcAvailable = remember { NfcAdapter.getDefaultAdapter(ctx) != null }
          var nfcEnabled by remember { mutableStateOf(RelaisConfig.nfcEnabled(ctx)) }
          var savedNote by remember { mutableStateOf("") }
          val ip = remember { lanIpv4() }
          val powerManager = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
          var batteryUnrestricted by remember {
            mutableStateOf(powerManager.isIgnoringBatteryOptimizations(ctx.packageName))
          }
          LaunchedEffect(Unit) {
            while (true) {
              ready = RelaisEngine.isReady
              running = RelaisConfig.shouldRun(ctx)
              batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
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
            Readout("POWER", if (batteryUnrestricted) "unrestricted" else "restricted")
            if (!batteryUnrestricted) {
              ActionLink("ALLOW UNRESTRICTED ›") { requestIgnoreBatteryOptimizations(ctx) }
            }
            Spacer(Modifier.height(2.dp))
            AccessKeyChip(apiKey = RelaisConfig.apiKey(ctx), baseUrl = "https://$ip:8443/v1")
            Divider()

            val modelDisplay = modelRef?.takeIf { it.modelId == modelId }?.displayName ?: modelId
            // Block model changes while the node is STARTING (running but not yet ready): ensureModel
            // may be mid-resolve/-download, and its terminal setModelPath() could resurrect the path a
            // concurrent setModelRef() just cleared — leaving the next boot serving the superseded
            // model. Changing while fully stopped or LIVE (path already settled) is safe.
            val nodeBusy = running && !ready
            ModelRow(value = modelDisplay, enabled = !nodeBusy) { showModelSheet = true }
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
                RelaisConfig.setHfToken(ctx, hfToken.trim().ifBlank { null })
                savedNote = "HF token saved. Restart to apply."
              },
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            ) {
              Text("SAVE HF TOKEN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            if (savedNote.isNotEmpty()) {
              Text(savedNote, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }

            if (showModelSheet) {
              RelaisModelSelectorSheet(
                currentModelId = modelId,
                // The saved token (not the editable field above): HF resolve and the later download
                // both authenticate with the persisted token, so a gated repo needs SAVE HF TOKEN first.
                hfToken = RelaisConfig.hfToken(ctx),
                onPickRef = { ref ->
                  RelaisConfig.setModelRef(ctx, ref)
                  modelRef = ref
                  modelId = ref.modelId
                  // Show the resolved file, not just the repo — an HF repo can hold several
                  // .litertlm variants and the operator should see which one was chosen before Start.
                  savedNote = "Selected ${ref.displayName} · ${ref.modelFile}. Restart to apply."
                  showModelSheet = false
                },
                onPickManualId = { id ->
                  // Entering a raw id is an explicit "resolve this via the allowlist" intent, so
                  // drop any curated ref first (even if the id matches the ref's repo) — otherwise
                  // the pinned ref would keep overriding allowlist resolution.
                  RelaisConfig.clearModelRef(ctx)
                  RelaisConfig.setModelId(ctx, id)
                  modelRef = null
                  modelId = id
                  savedNote = "Set model id $id. Restart to apply."
                  showModelSheet = false
                },
                onDismiss = { showModelSheet = false },
              )
            }

            Divider()
            ActionLink("PROMPT TEMPLATES ›") {
              ctx.startActivity(Intent(ctx, PromptTemplateEditorActivity::class.java))
            }
            // Notification triage is stripped from the Play (playsafe) build — its listener service +
            // control activity are removed from that manifest (notification-access policy), so hide the
            // entry there or the launch would ActivityNotFoundException. POLICY_OPEN=false in playsafe.
            if (BuildConfig.POLICY_OPEN) {
              ActionLink("NOTIFICATION TRIAGE ›") {
                ctx.startActivity(Intent(ctx, TriageControlActivity::class.java))
              }
            }

            // Share-sheet inference target (#1): on/off. The manifest entry is always present; this
            // is the runtime opt-out — when off, a share reports "disabled" instead of running.
            Readout("SHARE TARGET", if (shareEnabled) "on" else "off")
            ActionLink(if (shareEnabled) "DISABLE SHARE TARGET" else "ENABLE SHARE TARGET") {
              val next = !shareEnabled
              RelaisConfig.setShareEnabled(ctx, next)
              shareEnabled = next
            }

            // NFC workflow triggers (#15): opt-in, only when the device has NFC. When on, tapping a
            // tag that encodes cc.grepon.relais://workflow/<id> runs that prompt template.
            if (nfcAvailable) {
              Readout("NFC WORKFLOWS", if (nfcEnabled) "on" else "off")
              ActionLink(if (nfcEnabled) "DISABLE NFC" else "ENABLE NFC") {
                val next = !nfcEnabled
                RelaisConfig.setNfcEnabled(ctx, next)
                nfcEnabled = next
              }
              if (nfcEnabled) {
                ActionLink("WRITE NFC TAG ›") { ctx.startActivity(Intent(ctx, NfcWriteActivity::class.java)) }
              }
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

/** Tappable model row: muted MODEL label, current selection, amber ▸ — opens the model selector. */
@Composable
private fun ModelRow(value: String, enabled: Boolean = true, onClick: () -> Unit) {
  Box(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled) { onClick() }
      .alpha(if (enabled) 1f else 0.5f).padding(vertical = 6.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("MODEL", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
      Spacer(Modifier.width(16.dp))
      Text(
        value,
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(10.dp))
      Text("▸", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Amber tap affordance, e.g. "ALLOW UNRESTRICTED ›" — matches the "SHARE CONNECTION ›" idiom. */
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

/**
 * Opens the system "ignore battery optimizations" dialog so the always-on node survives
 * Doze/app-standby. Falls back to the battery-optimization settings list on OEMs that don't honor
 * the direct request action.
 */
@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
  val direct =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
  runCatching { ctx.startActivity(direct) }
    .onFailure {
      runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        .onFailure { e -> Log.w(TAG, "Could not open battery-optimization settings: ${e.message}") }
    }
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
