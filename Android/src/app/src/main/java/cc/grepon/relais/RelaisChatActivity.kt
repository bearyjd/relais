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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
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
 *
 * Attachments map to what the engine can actually ingest ([RelaisRequest] carries ONE imagePng and
 * ONE audioWav; LiteRT-LM has no document encoder):
 *  - images           → downscaled PNG → vision encoder (multimodal models only)
 *  - PDF              → first page rendered via [PdfRenderer] → vision encoder (single-image cap)
 *  - audio (WAV only) → raw bytes → audio encoder, same pass-through as /v1/audio/transcriptions
 *  - text-ish files   → inlined above the prompt, capped hard: the resident engine runs
 *    MAX_NUM_TOKENS=1024 total, so docs are trimmed to [DOC_CHAR_CAP] chars (works on any model)
 * (Mime names spelled out in prose here because Kotlin block comments NEST — a literal
 * slash-star wildcard inside a KDoc opens a nested comment and eats the rest of the file.)
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

/** One staged attachment, already converted to an engine-ingestible form. */
private sealed interface Attachment {
  val label: String

  data class Image(val png: ByteArray, override val label: String) : Attachment
  data class Audio(val wav: ByteArray, override val label: String) : Attachment
  data class Doc(val name: String, val text: String, val truncated: Boolean) : Attachment {
    override val label: String
      get() = if (truncated) "$name (truncated)" else name
  }
}

/** Hard cap for inlined documents: the resident engine runs MAX_NUM_TOKENS=1024 total. */
private const val DOC_CHAR_CAP = 2000

private const val CHAT_TAG = "RelaisChat"

@Composable
private fun ChatScreen() {
  val ctx = LocalActivityContext()
  val scope = rememberCoroutineScope()
  val messages = remember { mutableStateListOf<ChatMsg>() }
  var draft by remember { mutableStateOf("") }
  var streaming by remember { mutableStateOf(false) }
  var readout by remember { mutableStateOf("") }
  var pending by remember { mutableStateOf<Attachment?>(null) }
  val listState = rememberLazyListState()

  val pickFile =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      android.util.Log.i(CHAT_TAG, "picker returned uri=$uri")
      if (uri != null) {
        scope.launch {
          // Read the multimodal flag at attach time, NOT at composition: the screen can be opened
          // while the engine is still initializing (flag false), and it flips true when init lands.
          val multimodal = RelaisEngine.isMultimodal
          val result =
            withContext(Dispatchers.IO) {
              runCatching { stageAttachment(ctx, uri, multimodal) }
                .getOrElse { t ->
                  android.util.Log.e(CHAT_TAG, "stageAttachment threw", t)
                  StageResult.Err(t.message ?: t.javaClass.simpleName)
                }
            }
          android.util.Log.i(CHAT_TAG, "stage result: $result (multimodal=$multimodal)")
          when (result) {
            is StageResult.Ok -> pending = result.attachment
            is StageResult.Err -> readout = "⚠ attach failed: ${result.message}"
          }
        }
      }
    }

  // Keep the newest message in view as tokens stream in.
  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
  }

  fun send() {
    val prompt = draft.trim()
    val attachment = pending
    if ((prompt.isEmpty() && attachment == null) || streaming) return
    draft = ""
    pending = null
    // Prior turns become history; the live prompt is the only one that decodes.
    val history = messages.map { ParsedTurn(role = it.role, text = it.text) }
    val (requestText, shownText) = composeUserText(prompt, attachment)
    messages.add(ChatMsg("user", shownText))
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
                request =
                  RelaisRequest(
                    text = requestText,
                    imagePng = (attachment as? Attachment.Image)?.png,
                    audioWav = (attachment as? Attachment.Audio)?.wav,
                    history = history,
                  ),
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

  // imePadding() is load-bearing: on targetSdk 35 the app is edge-to-edge, so the manifest's
  // adjustResize no longer shrinks the window for the keyboard. Without this, the input row sits
  // behind the IME. systemBarsPadding keeps content clear of the status/nav bars.
  Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
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
    // Attachment preview chip — shows what is staged for the next send.
    pending?.let { att ->
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val glyph = when (att) {
          is Attachment.Image -> "🖼"
          is Attachment.Audio -> "🎙"
          is Attachment.Doc -> "📄"
        }
        Text("$glyph ${att.label}", color = Amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { pending = null }.padding(4.dp)) {
          Text("REMOVE", color = StopRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Attach affordance. Text docs work on any model; image/PDF/audio additionally need the
      // multimodal encoders — stageAttachment() rejects those with a clear message when text-only.
      Box(
        Modifier.clip(RoundedCornerShape(6.dp))
          .clickable(enabled = !streaming) { pickFile.launch(ATTACH_MIME_TYPES) }
          .padding(horizontal = 8.dp, vertical = 12.dp)
      ) {
        Text("＋", color = if (streaming) Muted else Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
      }
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
        enabled = (draft.isNotBlank() || pending != null) && !streaming,
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

// ---------------------------------------------------------------------------------------------
// Attachment staging — pure-ish helpers, all run on Dispatchers.IO.
// ---------------------------------------------------------------------------------------------

/** Broad picker filter; stageAttachment() does the real triage by mime + content sniffing. */
private val ATTACH_MIME_TYPES = arrayOf("image/*", "audio/*", "application/pdf", "text/*", "application/json", "application/xml", "application/octet-stream")

private sealed interface StageResult {
  data class Ok(val attachment: Attachment) : StageResult
  data class Err(val message: String) : StageResult
}

/** Converts a picked document into an engine-ingestible [Attachment], or a readable error. */
private fun stageAttachment(context: Context, uri: Uri, multimodal: Boolean): StageResult {
  val mime = context.contentResolver.getType(uri) ?: ""
  val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
  android.util.Log.i(CHAT_TAG, "staging uri=$uri mime='$mime' name='$name'")
  return when {
    mime.startsWith("image/") -> {
      if (!multimodal) return StageResult.Err("model is text-only — images unsupported")
      decodeToPng(context, uri)?.let { StageResult.Ok(Attachment.Image(it, name)) }
        ?: StageResult.Err("couldn't decode image")
    }
    mime == "application/pdf" -> {
      if (!multimodal) return StageResult.Err("model is text-only — PDF pages unsupported")
      pdfFirstPageToPng(context, uri)?.let { StageResult.Ok(Attachment.Image(it, "$name · page 1/…")) }
        ?: StageResult.Err("couldn't render PDF")
    }
    mime.startsWith("audio/") -> {
      if (!multimodal) return StageResult.Err("model is text-only — audio unsupported")
      val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return StageResult.Err("couldn't read audio")
      // The engine's audio encoder takes WAV bytes (same pass-through as /v1/audio/transcriptions).
      if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") {
        return StageResult.Err("only WAV audio supported (got $mime)")
      }
      StageResult.Ok(Attachment.Audio(bytes, name))
    }
    else -> {
      // Text-ish: accept if it decodes as UTF-8 without NUL bytes (covers code files that come
      // through as application/octet-stream). Cap hard — the engine has 1024 tokens total.
      val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return StageResult.Err("couldn't read file")
      if (bytes.any { it == 0.toByte() }) return StageResult.Err("unsupported binary file ($mime)")
      val text = bytes.toString(Charsets.UTF_8)
      val truncated = text.length > DOC_CHAR_CAP
      StageResult.Ok(Attachment.Doc(name, text.take(DOC_CHAR_CAP), truncated))
    }
  }
}

/** Builds (engine prompt text, bubble display text) for a message with an optional attachment. */
private fun composeUserText(prompt: String, attachment: Attachment?): Pair<String, String> =
  when (attachment) {
    is Attachment.Doc -> {
      val header = "Attached file ${attachment.name}" + if (attachment.truncated) " (truncated)" else ""
      val request = "$header:\n```\n${attachment.text}\n```\n\n$prompt".trim()
      request to "📄 ${attachment.label}\n$prompt".trim()
    }
    is Attachment.Image -> prompt to "🖼 ${attachment.label}\n$prompt".trim()
    is Attachment.Audio -> prompt to "🎙 ${attachment.label}\n$prompt".trim()
    null -> prompt to prompt
  }

/**
 * Reads [uri] and re-encodes it as a downscaled PNG (≤1024 px long edge) for [RelaisRequest.imagePng].
 * Returns null if the image can't be decoded. Downscaling caps prefill cost and memory — the vision
 * encoder resizes internally anyway, so full-res upload is wasted work.
 */
private fun decodeToPng(context: Context, uri: Uri): ByteArray? =
  runCatching {
      val bitmap =
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
          ?: return null
      bitmap.downscaled(maxEdge = 1024).toPngBytes()
    }
    .getOrNull()

/** Renders page 1 of a PDF to a PNG via the platform [PdfRenderer] (engine takes ONE image). */
private fun pdfFirstPageToPng(context: Context, uri: Uri): ByteArray? =
  runCatching {
      context.contentResolver.openFileDescriptor(uri, "r").use { pfd: ParcelFileDescriptor? ->
        if (pfd == null) return null
        PdfRenderer(pfd).use { renderer ->
          if (renderer.pageCount == 0) return null
          renderer.openPage(0).use { page ->
            // 2x page size for legibility, then the shared downscale cap keeps memory bounded.
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.downscaled(maxEdge = 1536).toPngBytes()
          }
        }
      }
    }
    .getOrNull()

private fun Bitmap.downscaled(maxEdge: Int): Bitmap {
  val longest = maxOf(width, height)
  if (longest <= maxEdge) return this
  val ratio = maxEdge.toFloat() / longest
  return Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
}

private fun Bitmap.toPngBytes(): ByteArray =
  ByteArrayOutputStream().use { out ->
    compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
  }
