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

package cc.grepon.relais

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.grepon.relais.chat.ChatStreamRequest
import cc.grepon.relais.chat.ChatTransportSelector
import cc.grepon.relais.chat.ERROR_BACKEND
import cc.grepon.relais.chat.historyForRequest
import cc.grepon.relais.data.ChatTurn
import cc.grepon.relais.data.Conversation
import cc.grepon.relais.data.RelaisDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persistence-backed streaming chat view model for the "Chat Depth" in-app chat feature. Owns
 * conversation/turn state (via [ChatRepository]), token streaming (via [ChatTransportSelector] /
 * [cc.grepon.relais.chat.ChatTransport]), and model-switch reload observation ([RelaisEngine]).
 * Not wired into any UI yet — that lands in Task 7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = ChatRepository(app, RelaisDatabase.get(app).chatDao())

  /** One selector (and one owned [HttpClient]) for the ViewModel's lifetime; closed in [onCleared]. */
  private val transportSelector = ChatTransportSelector(app)

  private val cancelled = AtomicBoolean(false)

  /**
   * Guards against overlapping streams: [send]/[regenerate]/[editAndResend] all mutate the same
   * `_streamingText` and append assistant turns, so a second trigger while one is in flight would
   * interleave tokens and corrupt history. Acquired synchronously before launching; released in the
   * launched coroutine's `finally`.
   */
  private val inFlight = AtomicBoolean(false)

  val conversations: StateFlow<List<Conversation>> =
    repo
      .observeConversations()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _activeConversationId = MutableStateFlow<String?>(null)
  val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

  val turns: StateFlow<List<ChatTurn>> =
    _activeConversationId
      .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTurns(id) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _streamingText = MutableStateFlow("")
  val streamingText: StateFlow<String> = _streamingText.asStateFlow()

  private val _streaming = MutableStateFlow(false)
  val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

  // Set to the just-persisted assistant turn's id for the brief hand-off window (see
  // streamAndPersist), so the UI can suppress the streaming bubble by id rather than by content —
  // content equality misfires when two consecutive assistant turns happen to match.
  private val _pendingPersistedTurnId = MutableStateFlow<String?>(null)
  val pendingPersistedTurnId: StateFlow<String?> = _pendingPersistedTurnId.asStateFlow()

  private val _reloadingModel = MutableStateFlow(false)
  val reloadingModel: StateFlow<Boolean> = _reloadingModel.asStateFlow()

  /** The in-flight reload-observation poll, cancelled and replaced on each model switch. */
  private var reloadJob: kotlinx.coroutines.Job? = null

  /** Clears the active conversation; a new one is created lazily on the next [send]. */
  fun newConversation() {
    _activeConversationId.value = null
  }

  fun openConversation(id: String) {
    _activeConversationId.value = id
  }

  /**
   * Persists the user turn, then streams the assistant reply on [Dispatchers.IO], persisting it on
   * completion. Creates a conversation first (title = first ~40 chars of [text]) if none is active.
   */
  fun send(text: String, attachmentType: String?, attachmentBytes: ByteArray?) {
    if (!inFlight.compareAndSet(false, true)) return
    val ctx = getApplication<Application>()
    viewModelScope.launch {
      try {
        val convId =
          _activeConversationId.value
            ?: repo
              .createConversation(
                title = text.take(40).ifBlank { "New chat" },
                modelId = RelaisConfig.modelId(ctx),
              )
              .also { _activeConversationId.value = it }

        appendUserAndStream(convId, text, attachmentType, attachmentBytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  /** Persists a new user turn, then streams and persists the assistant reply. */
  private suspend fun appendUserAndStream(
    conversationId: String,
    text: String,
    attachmentType: String?,
    attachmentBytes: ByteArray?,
  ) {
    repo.appendUserTurn(conversationId, text, attachmentType, attachmentBytes)
    streamAndPersist(conversationId, text, attachmentType, attachmentBytes)
  }

  private suspend fun streamAndPersist(
    conversationId: String,
    text: String,
    attachmentType: String?,
    attachmentBytes: ByteArray?,
  ) {
    val ctx = getApplication<Application>()
    cancelled.set(false)
    _streamingText.value = ""
    _streaming.value = true
    try {
      val persisted =
        withContext(Dispatchers.IO) {
          runCatching {
              val history = historyForRequest(repo.turnsFor(conversationId))
              val transport = transportSelector.select()
              transport.stream(
                request =
                  ChatStreamRequest(
                    history = history,
                    userText = text,
                    imagePng = if (attachmentType == "image") attachmentBytes else null,
                    audioWav = if (attachmentType == "audio") attachmentBytes else null,
                  ),
                onToken = { token -> _streamingText.value += token },
                onReasoning = {},
                shouldCancel = { cancelled.get() },
              )
            }
            .fold(
              onSuccess = { result ->
                repo.appendAssistantTurn(conversationId, result.text, result.modelId, result.backend.name)
              },
              onFailure = { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                repo.appendAssistantTurn(
                  conversationId,
                  content = "[error] ${error.message ?: error::class.simpleName}",
                  modelId = RelaisConfig.modelId(ctx),
                  backend = ERROR_BACKEND,
                )
              },
            )
        }
      // Keep the streaming bubble up until the just-persisted turn is actually reflected in [turns],
      // so there's never a frame with neither the bubble nor the persisted turn visible. Bounded by a
      // timeout so a missed/delayed Flow emission can't hang the UI in the streaming state forever.
      _pendingPersistedTurnId.value = persisted.id
      withTimeoutOrNull(TURN_PERSIST_AWAIT_TIMEOUT_MS) {
        turns.first { list -> list.any { it.id == persisted.id } }
      }
    } finally {
      // Reset in `finally` so a scope/coroutine cancel (e.g. ViewModel cleared mid-stream) still
      // clears the streaming flags rather than leaving the UI stuck in the "streaming" state.
      _streaming.value = false
      _streamingText.value = ""
      _pendingPersistedTurnId.value = null
    }
  }

  /** Flips the cancellation flag read by the in-flight transport's `shouldCancel`. */
  fun stop() {
    cancelled.set(true)
  }

  /**
   * Truncates the conversation back to just before [fromAssistantTurn]'s preceding user turn, then
   * re-streams a reply for that user turn's text/attachment.
   */
  fun regenerate(fromAssistantTurn: ChatTurn) {
    val convId = _activeConversationId.value ?: return
    val ordered = turns.value.sortedBy { it.createdAt }
    val assistantIndex = ordered.indexOfFirst { it.id == fromAssistantTurn.id }
    if (assistantIndex <= 0) return
    val precedingUserTurn =
      ordered.subList(0, assistantIndex).lastOrNull { it.role == "user" } ?: return

    if (!inFlight.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        repo.truncateAfter(convId, precedingUserTurn)
        val bytes = precedingUserTurn.attachmentPath?.let { path -> readAttachment(path) }
        val type = if (bytes != null) precedingUserTurn.attachmentType else null
        streamAndPersist(convId, precedingUserTurn.content, type, bytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  /** Removes [userTurn] and everything after it, then sends [newText] as a fresh turn. */
  fun editAndResend(userTurn: ChatTurn, newText: String) {
    val convId = _activeConversationId.value ?: return
    val ordered = turns.value.sortedBy { it.createdAt }
    val userIndex = ordered.indexOfFirst { it.id == userTurn.id }
    if (userIndex < 0) return
    val precedingTurn = ordered.getOrNull(userIndex - 1)

    if (!inFlight.compareAndSet(false, true)) return
    viewModelScope.launch {
      try {
        val bytes = userTurn.attachmentPath?.let { path -> readAttachment(path) } // read BEFORE truncation
        // Only carry the attachment type if the bytes are actually still on disk — otherwise the
        // resent turn would persist a type with no data (a "phantom attachment").
        val type = if (bytes != null) userTurn.attachmentType else null
        if (precedingTurn != null) {
          repo.truncateAfter(convId, precedingTurn)
        } else {
          repo.truncateAfter(convId, userTurn.copy(createdAt = userTurn.createdAt - 1))
        }
        appendUserAndStream(convId, newText, type, bytes)
      } finally {
        inFlight.set(false)
      }
    }
  }

  private fun readAttachment(path: String): ByteArray? {
    val file = File(path)
    return if (file.exists()) file.readBytes() else null
  }

  /** Switches to a curated ref (persisting the full ref, not just its id) and reflects the reload. */
  fun switchToRef(ref: cc.grepon.relais.data.RelaisModelRef) {
    ModelSwitch.applyRef(getApplication(), ref)
    observeReload()
  }

  /** Switches to a raw manual id (dropping any curated ref) and reflects the reload. */
  fun switchToManualId(modelId: String) {
    ModelSwitch.applyManualId(getApplication(), modelId)
    observeReload()
  }

  /** Reflects [RelaisEngine]'s lazy model reload into [reloadingModel] (see [ModelSwitch.awaitReload]). */
  private fun observeReload() {
    // Cancel any in-flight poll first so a rapid re-pick doesn't leave overlapping pollers racing to
    // write _reloadingModel (harmless final value, but avoids flicker and wasted coroutines).
    reloadJob?.cancel()
    reloadJob =
      viewModelScope.launch {
        _reloadingModel.value = true
        _reloadingModel.value = !ModelSwitch.awaitReload()
      }
  }

  fun rename(id: String, title: String) {
    viewModelScope.launch { repo.rename(id, title) }
  }

  fun delete(id: String) {
    viewModelScope.launch {
      repo.delete(id)
      if (_activeConversationId.value == id) {
        _activeConversationId.value = null
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    transportSelector.close()
  }

  private companion object {
    const val TURN_PERSIST_AWAIT_TIMEOUT_MS = 3_000L
  }
}
