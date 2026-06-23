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

package cc.grepon.relais.nfc

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.grepon.relais.Amber
import cc.grepon.relais.Charcoal
import cc.grepon.relais.Line
import cc.grepon.relais.Muted
import cc.grepon.relais.Panel
import cc.grepon.relais.Paper
import cc.grepon.relais.StopRed
import cc.grepon.relais.templates.WorkflowRegistry

/**
 * Internal writer screen for NFC workflow tags (#15). Pick a #12 prompt template, then hold a blank
 * (or rewritable) NFC tag to the phone — it writes `com.ventouxlabs.relais://workflow/<id>`. Uses
 * foreground dispatch so taps land here while this screen is visible. Reachable from the control panel.
 */
class NfcWriteActivity : ComponentActivity() {
  private val status = mutableStateOf("Pick a workflow, then hold a tag to the phone.")
  private val selectedId = mutableStateOf<String?>(null)
  private var adapter: NfcAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    adapter = NfcAdapter.getDefaultAdapter(this)
    if (adapter == null) status.value = "This device has no NFC."
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
          WriteScreen(
            selectedId = selectedId.value,
            status = status.value,
            onSelect = { id, name ->
              selectedId.value = id
              status.value = "Selected \"$name\" — hold a tag to write."
            },
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val nfc = adapter ?: return
    val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    // Foreground dispatch requires a MUTABLE PendingIntent (the system injects EXTRA_TAG). minSdk 31.
    val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
    runCatching { nfc.enableForegroundDispatch(this, pi, null, null) }
  }

  override fun onPause() {
    super.onPause()
    runCatching { adapter?.disableForegroundDispatch(this) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val id = selectedId.value ?: run { status.value = "Pick a workflow first."; return }
    val tag = extractTag(intent) ?: run { status.value = "No tag detected — try again."; return }
    val uri = NfcWorkflowParser.buildUri(id)
    status.value =
      when (val r = NfcTagWriter.write(tag, uri)) {
        WriteResult.Success -> "✓ Wrote $uri"
        WriteResult.ReadOnly -> "Tag is read-only."
        is WriteResult.TooSmall -> "Tag too small: needs ${r.needed} B, has ${r.capacity} B."
        is WriteResult.Failed -> "Write failed: ${r.message}"
      }
  }

  private fun extractTag(intent: Intent): Tag? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    } else {
      @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }
}

@Composable
private fun WriteScreen(selectedId: String?, status: String, onSelect: (String, String) -> Unit) {
  val ctx = LocalContext.current
  val templates = remember { WorkflowRegistry.templates(ctx) }
  Column(
    modifier =
      Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      "WRITE NFC TAG",
      color = Amber,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 18.sp,
      letterSpacing = 3.sp,
    )
    Text(
      "Tap-to-run: writes com.ventouxlabs.relais://workflow/<id> to a tag.",
      color = Muted,
      fontFamily = FontFamily.Monospace,
      fontSize = 11.sp,
    )
    Divider()
    Text("WORKFLOW", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp)
    templates.forEach { t ->
      val selected = t.id == selectedId
      Row(
        modifier =
          Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSelect(t.id, t.name) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (selected) "▸" else " ",
          color = Amber,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(t.name, color = if (selected) Amber else Paper, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
          Text(t.id, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
      }
    }
    Divider()
    Text(status, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
  }
}

@Composable
private fun Divider() {
  Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}
