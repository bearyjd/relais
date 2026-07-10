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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app chat surface — a minimal client for the resident engine, styled per DESIGN.md (amber on
 * charcoal, monospace). Unlike the OpenAI HTTP endpoint, this calls [RelaisEngine.generate]
 * in-process: no node start, no key, no LAN. Multi-turn history is seeded via [RelaisRequest.history]
 * so each send costs one decode. A trailing readout shows the resolved backend + decode tok/s — the
 * same numbers the benchmark screen reports, so this doubles as a quick on-device sanity check that
 * the model is live and which accelerator served it.
 */
class RelaisChatActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { ChatScreen() }
      }
    }
  }
}

private data class ChatMsg(val role: String, val text: String)

@Composable
private fun ChatScreen() {
  val ctx = LocalActivityContext()
  val scope = rememberCoroutineScope()
  val messages = remember { mutableStateListOf<ChatMsg>() }
  var draft by remember { mutableStateOf("") }
  var streaming by remember { mutableStateOf(false) }
  var readout by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Keep the newest message in view as tokens stream in.
  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
  }

  fun send() {
    val prompt = draft.trim()
    if (prompt.isEmpty() || streaming) return
    draft = ""
    // Prior turns become history; the live prompt is the only one that decodes.
    val history = messages.map { ParsedTurn(role = it.role, text = it.text) }
    messages.add(ChatMsg("user", prompt))
    messages.add(ChatMsg("assistant", ""))
    val replyIndex = messages.lastIndex
    streaming = true
    readout = "generating…"
    scope.launch {
      val result =
        withContext(Dispatchers.IO) {
          runCatching {
              RelaisEngine.generate(
                context = ctx,
                request = RelaisRequest(text = prompt, history = history),
                onToken = { tok ->
                  val cur = messages[replyIndex]
                  messages[replyIndex] = cur.copy(text = cur.text + tok)
                },
              )
            }
            .getOrElse { err ->
              messages[replyIndex] =
                messages[replyIndex].copy(text = "⚠ ${err.message ?: err.javaClass.simpleName}")
              null
            }
        }
      readout =
        result?.let { "${it.backend.name.lowercase()} · ${"%.1f".format(it.decodeTokensPerSec)} tok/s" }
          ?: "error"
      streaming = false
    }
  }

  Column(Modifier.fillMaxSize().systemBarsPadding()) {
    // Header — wordmark + CHAT label + streaming/backend readout.
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(Amber, RoundedCornerShape(5.dp)))
        Spacer(Modifier.size(10.dp))
        Text("RELAIS", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 4.sp)
        Spacer(Modifier.weight(1f))
        Text("CHAT", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
      }
      if (readout.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(readout, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))

    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
      items(messages) { msg -> Bubble(msg) }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Row(
      Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message…", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Paper),
        singleLine = false,
        maxLines = 4,
        shape = RoundedCornerShape(6.dp),
        colors =
          TextFieldDefaults.colors(
            focusedContainerColor = Panel,
            unfocusedContainerColor = Panel,
            focusedIndicatorColor = Amber.copy(alpha = 0.5f),
            unfocusedIndicatorColor = Line,
            cursorColor = Amber,
          ),
        keyboardActions = KeyboardActions(onSend = { send() }),
      )
      Button(
        onClick = { send() },
        enabled = draft.isNotBlank() && !streaming,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Charcoal, disabledContainerColor = Line, disabledContentColor = Muted),
      ) {
        Text("SEND", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
      }
    }
  }
}

@Composable
private fun Bubble(msg: ChatMsg) {
  val isUser = msg.role == "user"
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
    Box(
      Modifier.widthIn(max = 320.dp)
        .background(if (isUser) Amber.copy(alpha = 0.14f) else Panel, RoundedCornerShape(12.dp))
        .then(if (isUser) Modifier else Modifier.border(1.dp, Line, RoundedCornerShape(12.dp)))
        .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
      Text(
        msg.text.ifEmpty { "…" },
        color = Paper,
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
      )
    }
  }
}

/** The hosting activity's Context, for the in-process engine call. */
@Composable private fun LocalActivityContext() = androidx.compose.ui.platform.LocalContext.current
