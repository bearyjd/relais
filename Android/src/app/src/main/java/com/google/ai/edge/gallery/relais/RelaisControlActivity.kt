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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Human start/stop control for the node. Also honors `--es cmd start|stop` so it can be driven
 * headlessly (adb am start) for automation.
 *
 *   adb shell am start -n <appId>/com.google.ai.edge.gallery.relais.RelaisControlActivity --es cmd start
 */
class RelaisControlActivity : ComponentActivity() {
  private fun handleCmd(intent: Intent?) {
    when (intent?.getStringExtra("cmd")) {
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
          var ready by remember { mutableStateOf(RelaisEngine.isReady) }
          var running by remember { mutableStateOf(RelaisConfig.shouldRun(this@RelaisControlActivity)) }
          LaunchedEffect(Unit) {
            while (true) {
              ready = RelaisEngine.isReady
              running = RelaisConfig.shouldRun(this@RelaisControlActivity)
              delay(1000)
            }
          }
          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Relais Node", style = MaterialTheme.typography.headlineSmall)
            Text("State: " + if (ready) "running · engine resident" else if (running) "starting…" else "stopped")
            Text("Endpoints: http://<device-ip>:8080  ·  https://<device-ip>:8443")
            Text("API key: ${RelaisConfig.apiKey(this@RelaisControlActivity)}")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(onClick = { RelaisNodeService.start(this@RelaisControlActivity) }) { Text("Start") }
              Button(onClick = { RelaisNodeService.stop(this@RelaisControlActivity) }) { Text("Stop") }
            }
          }
        }
      }
    }
  }
}
