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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay

private const val TAG = "RelaisControl"

/**
 * Human start/stop control for the node. Also honors `--es cmd start|stop` so it can be driven
 * headlessly (adb am start) for automation. The activity is exported (launchable), so the `cmd`
 * extra is gated behind the node's API key: any app can open the screen, but only a caller that
 * knows the key (i.e. the operator via adb) can start/stop the service. The in-app buttons call
 * [RelaisNodeService] directly — they are a trusted in-process user action and need no token.
 *
 * Optional `--es modelId <id>` / `--es hfToken <t>` (also key-gated) switch the model the node
 * serves before starting; the id must be in Gallery's allowlist, and gated repos need the token.
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
    // Optional model switch before (re)starting. Takes effect when the node next provisions.
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
      MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val ctx = this@RelaisControlActivity
          var ready by remember { mutableStateOf(RelaisEngine.isReady) }
          var running by remember { mutableStateOf(RelaisConfig.shouldRun(ctx)) }
          var modelId by remember { mutableStateOf(RelaisConfig.modelId(ctx)) }
          var hfToken by remember { mutableStateOf(RelaisConfig.hfToken(ctx) ?: "") }
          var savedNote by remember { mutableStateOf("") }
          LaunchedEffect(Unit) {
            while (true) {
              ready = RelaisEngine.isReady
              running = RelaisConfig.shouldRun(ctx)
              delay(1000)
            }
          }
          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Relais Node", style = MaterialTheme.typography.headlineSmall)
            Text("State: " + if (ready) "running · engine resident" else if (running) "starting…" else "stopped")
            Text("Endpoints: http://<device-ip>:8080  ·  https://<device-ip>:8443")
            Text("API key: ${RelaisConfig.apiKey(ctx)}")

            OutlinedTextField(
              value = modelId,
              onValueChange = { modelId = it; savedNote = "" },
              label = { Text("Model ID (from Gallery allowlist)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
              value = hfToken,
              onValueChange = { hfToken = it; savedNote = "" },
              label = { Text("HuggingFace token (only for gated repos)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
              RelaisConfig.setModelId(ctx, modelId.trim())
              RelaisConfig.setHfToken(ctx, hfToken.trim().ifBlank { null })
              savedNote = "Saved. Restart the node to apply (an un-downloaded model downloads on Start)."
            }) { Text("Save model") }
            if (savedNote.isNotEmpty()) Text(savedNote, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(onClick = { RelaisNodeService.start(ctx) }) { Text("Start") }
              Button(onClick = { RelaisNodeService.stop(ctx) }) { Text("Stop") }
            }
          }
        }
      }
    }
  }
}
