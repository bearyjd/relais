# Chat Depth — Design Spec (Spec 2 of 2)

**Date:** 2026-07-11
**Status:** Approved (design); implementation plan pending
**Depends on:** Spec 1 (Unified App Shell, merged in #143) — the CHAT destination and `ChatScreen`
exist in the shell's NavHost.
**Scope:** Turn the shell's CHAT destination from a single ephemeral session into a real chat app:
persistent multi-conversation history, in-chat model switching (full switch + honest reload UX),
generation controls, markdown/code rendering, a hybrid HTTP/in-process transport, and
**share/export of conversations**. Every piece leans on infrastructure the repo already has.

---

## 1. Problem

The current in-app chat (`RelaisChatActivity.ChatScreen`, wired into the shell in Spec 1) is a
minimal throwaway surface:
- Messages are `ChatMsg(role, text)` in a `mutableStateListOf` — **no persistence**; the whole
  conversation vanishes on exit, and there is only ever one conversation.
- Assistant text renders as **raw monospace** (`Text(..., FontFamily.Monospace)`) — no markdown, no
  code formatting.
- There is **no in-chat stop button** (cancel only fires when the whole screen is disposed), no
  regenerate, no edit-resend, no per-message copy.
- The model is **fixed** to whatever the resident engine loaded (`RelaisConfig.modelId`); no in-chat
  switch, no per-turn readout of which model/accelerator answered.
- Chat calls `RelaisEngine.generate` **in-process only** — it never exercises the node's HTTP
  `/v1/chat/completions` path that real LAN clients use.
- There is **no way to share or export** a conversation.

## 2. Reused infrastructure (do NOT reinvent)

| Need | Already in the repo |
|---|---|
| Persistence | **Room** — `RelaisDatabase` (v4, migration discipline, schemas exported). `SessionTurn`/`SessionDao` are a near-drop-in template. |
| Markdown/code | **`com.halilibo.compose-richtext`** (dependency) + reusable `ui/common/MarkdownText.kt` and `BufferedFadingMarkdownText.kt` (streaming-aware). |
| HTTP client | **Ktor client** (`ktor-client-android`/`-core` 3.4.3, dependency). |
| Liveness probe | `GET /health` (unauth) on `127.0.0.1:8080` → `{ready}`; `RelaisEngine.isReady`. |
| Engine call | `RelaisEngine.generate(context, request, onToken, shouldCancel, onReasoning): RelaisResult`. |
| Shared history type | `ParsedTurn` (`RelaisOpenAiParser.kt`) — the request ↔ persisted-turn bridge. |
| Access key | `RelaisConfig.apiKey(context)` (EncryptedSharedPreferences). |
| Share pattern | `ACTION_SEND` + `Intent.createChooser` (already used by the dashboard's SHARE CONNECTION). |

## 3. Decisions (locked during brainstorming)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Persistence = Room**, new `Conversation` + `ChatTurn` tables (DB v4→v5 migration). | Room is the established on-device store; `SessionTurn`/`SessionDao` is a template. |
| D2 | **Hybrid transport**: `ChatTransport` interface, `HttpChatTransport` (Ktor→loopback) + `InProcessChatTransport` (engine); select by `GET /health`. | Dogfoods the real OpenAI path when live; always works when the node is stopped. |
| D3 | **Generation controls** on a `deleteTurnsAfter` primitive: stop, regenerate, edit-resend, copy. | One DB primitive powers regenerate + edit; stop wires to existing `shouldCancel`. |
| D4 | **Markdown via reused compose-richtext**, re-themed amber; code stays monospace. | No new dependency; `MarkdownText`/`BufferedFadingMarkdownText` already exist. |
| D5 | **Full in-chat model switch + honest reload UX** (setModelId → engine reload → "reloading model…" state). | User's explicit call; both transports serve the single resident model, so a switch is a node-level reload — surfaced honestly. |
| D6 | **Keep the amber DESIGN.md ChatScreen**; extend it. Do NOT adopt the Material gallery `ui/common/chat` UI (only theme-agnostic pieces like `MarkdownText`). | On-brand; the gallery chat is Material-themed. |
| D7 | **Assistant prose uses `FontFamily.SansSerif`** (platform, zero-bundle); monospace elsewhere. | The "prose surface added later" that `DESIGN.md §Typography` anticipated; readable without bundling a font. |
| D8 | **Share/export = Markdown to the Android share sheet** (`ACTION_SEND`), plus optional `.md` file export. | Reuses the app's existing share idiom; Markdown reuses what we already render. |

## 4. Data model (Room, DB v4 → v5)

New entities in `RelaisDatabase` (add to `@Database.entities`, bump `version = 5`, append a
`Migration(4, 5)` to the existing `MIGRATIONS` list — non-destructive, schema exported):

```kotlin
@Entity(tableName = "conversations")
data class Conversation(
  @PrimaryKey val id: String,        // UUID (generated off the main thread; not Math.random)
  val title: String,                 // derived from first user turn, editable
  val modelId: String,               // model resident when the conversation was last used
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "chat_turns",
  foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"],
    childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
  indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])],
)
data class ChatTurn(
  @PrimaryKey val id: String,        // UUID
  val conversationId: String,
  val role: String,                  // "user" | "assistant"
  val content: String,               // user text, or assistant markdown
  val attachmentType: String?,       // "image" | "audio" | null  (docs are inlined into content)
  val attachmentPath: String?,       // app-storage file path for image/audio bytes, else null
  val answeredByModelId: String?,    // assistant turns: resident modelId that answered
  val answeredByBackend: String?,    // assistant turns: RelaisResult.backend name
  val createdAt: Long,
)
```

`ChatDao`: `observeConversations(): Flow<List<Conversation>>`, `observeTurns(conversationId): Flow<List<ChatTurn>>`,
`upsertConversation`, `insertTurn`, `renameConversation`, `deleteConversation`,
`deleteTurnsAfter(conversationId, createdAt)` (powers regenerate/edit), `turnsForRequest(conversationId): List<ChatTurn>`.
`ChatRepository` wraps the DAO + attachment file IO (write image/audio bytes to
`context.filesDir/chat/<turnId>.<ext>`, delete on conversation delete).

## 5. Transport

```kotlin
data class ChatStreamRequest(
  val history: List<ParsedTurn>,     // full prior turns
  val userText: String,
  val imagePng: ByteArray?,
  val audioWav: ByteArray?,
)

interface ChatTransport {
  // streams tokens; returns the final result (backend, tok/s, finishReason) when done
  suspend fun stream(
    request: ChatStreamRequest,
    onToken: (String) -> Unit,
    onReasoning: (String) -> Unit,
    shouldCancel: () -> Boolean,
  ): ChatStreamResult
}
```

- `HttpChatTransport` — Ktor client to `http://127.0.0.1:8080/v1/chat/completions`, `stream=true`,
  `Authorization: Bearer <RelaisConfig.apiKey>`, parses the SSE-to-EOF body (read to close), maps
  deltas to `onToken`. Builds the OpenAI JSON from `ChatStreamRequest`.
- `InProcessChatTransport` — maps `ChatStreamRequest` → `RelaisRequest` and calls
  `RelaisEngine.generate(context, request, onToken, shouldCancel, onReasoning)`.
- `ChatTransportSelector.select(context): ChatTransport` — probe `GET http://127.0.0.1:8080/health`
  (short timeout); if 200 and `ready == true`, return `HttpChatTransport`; else
  `InProcessChatTransport`. The **probe→choice mapping is a pure function** (`chooseTransport(healthOk, ready): TransportKind`) and JVM-tested; the IO probe wraps it.

## 6. Generation controls

- **Stop:** a stop control replaces SEND during streaming; sets a `cancelled` flag read by
  `shouldCancel`. Honest scope: it halts streaming to the UI and marks the turn `finishReason=stopped`;
  the native decode may run to completion (litertlm has no mid-decode cancel — issue **#125**). The
  UI does not claim more than it does.
- **Regenerate:** on an assistant turn → `deleteTurnsAfter(conversationId, targetUserTurn.createdAt)`
  then re-stream from that user turn.
- **Edit-and-resend:** edit a user turn's text → `deleteTurnsAfter(...)` from that turn → re-stream.
- **Copy:** per message, copy `content` to the clipboard (`COPIED` ack, matching the app idiom).

## 7. Markdown & typography

- Assistant turns render through the reused `MarkdownText` / `BufferedFadingMarkdownText`
  (streaming variant while a turn is in flight), re-themed: prose `Paper` on `Charcoal`, links
  `Amber`, code blocks monospace on `Panel`. User turns render plain.
- **DESIGN.md deviation (D7):** assistant prose uses `FontFamily.SansSerif` (platform sans, no
  bundle) — the prose pairing `DESIGN.md §Typography` anticipated. Recorded in the Decisions Log.

## 8. Model switch in chat (full switch + reload)

- A model affordance in the chat top bar opens the existing `RelaisModelSelectorSheet`. On pick →
  `RelaisConfig.setModelId(ctx, id)` (invalidates the provisioned-path cache) → engine reload.
- The chat shows a **"reloading model — <name>…"** state (driven by
  `RelaisEngine.startupInProgress` / `isReady`), and SEND is disabled until `isReady`. Any in-flight
  turn is blocked until the reload completes.
- Each assistant turn persists + shows a small **readout**: `answeredByModelId` +
  `answeredByBackend` (from `ChatStreamResult`/`RelaisResult.backend`).

## 9. Share / export (D8)

- **Share conversation** (per conversation, from the top bar / drawer overflow): render the
  conversation to **Markdown** (`# <title>` then `**user:** …` / `**assistant:** …` turns, code
  fences preserved) and launch `Intent.ACTION_SEND` (`text/plain`) via `Intent.createChooser` — the
  same idiom as the dashboard's SHARE CONNECTION. Image/audio attachments are noted as
  `[image]`/`[audio]` placeholders in the text (bytes not inlined).
- **Export to file** (optional secondary action): write the same Markdown to a `.md` via the SAF
  `CreateDocument` picker, so the user can save it anywhere.
- The Markdown serializer (`conversationToMarkdown(conversation, turns): String`) is a **pure
  function** — JVM-tested (title, turn ordering, role labels, code-fence preservation, attachment
  placeholders).

## 10. UI & state

- Extend the amber `ChatScreen`:
  - **Top bar:** model name + reload state, a **new-conversation** action, a **share** action, and
    an overflow for rename/export/delete.
  - **Conversation drawer / side-sheet:** list of conversations (title + relative time), tap to open,
    swipe/overflow to rename/delete, "＋ New chat".
  - **Message list:** markdown assistant bubbles, plain user bubbles, per-message action row
    (copy / regenerate / edit), the answered-by readout on assistant turns.
  - **Input row:** attach (unchanged, one attachment), text field, SEND ↔ STOP.
- State moves into a **`ChatViewModel`** backed by `ChatRepository`: it observes the active
  conversation's turns (Room `Flow`), owns the streaming lifecycle + `cancelled` flag, drives the
  selected `ChatTransport`, and persists each completed turn. The ephemeral `mutableStateListOf` is
  replaced by observed Room data.

## 11. Testing

- **Pure logic → device-free JVM tests** (repo convention, one `*Test.kt` per subject):
  - `chooseTransport(healthOk, ready)` (transport selection).
  - `conversationToMarkdown(...)` (share/export serialization).
  - Any turn-history mapping (`ChatTurn` ↔ `ParsedTurn`) and title derivation.
- **Room:** `ChatDao` tested with `Room.inMemoryDatabaseBuilder` (in the existing Room test style),
  incl. the `deleteTurnsAfter` behavior and the v4→v5 migration (Room `MigrationTestHelper`).
- **No new on-device capability** beyond the HTTP loopback client — an optional `*Probe.kt` can
  verify `HttpChatTransport` against a live node on hardware, but it is not required for CI.
- CI gate: `testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`.

## 12. DESIGN.md deviations to bless

- **Assistant prose font** (D7): `FontFamily.SansSerif` for chat markdown prose; monospace elsewhere.
- Decisions-Log entry:
  > | 2026-07-11 | Chat depth: persistent conversations, hybrid HTTP/in-process transport,
  > generation controls, reused compose-richtext markdown (assistant prose in platform SansSerif —
  > the prose pairing anticipated in §Typography), in-chat model switch with reload, Markdown
  > share/export | docs/superpowers/specs/2026-07-11-chat-depth-design.md |

## 13. Scope boundaries

**In:** §4–§10 above (persistence, hybrid transport, generation controls, markdown, model switch,
share/export, conversation UI).
**Out (YAGNI):** tool-calling UI, in-chat RAG toggle, multi-attachment per message (stays one),
voice input, cross-device sync, cloud backup.

## 14. Success criteria

- Conversations persist across app restart; multiple conversations are listable and switchable.
- Assistant replies render markdown + code; user can copy, regenerate, edit-and-resend, and stop.
- The chat uses the node's HTTP API when the node is live (verifiable in logs), and the in-process
  engine when it is stopped — both work.
- Switching the model in chat reloads the engine with a clear reload state and per-turn answered-by
  readout.
- A conversation can be shared as Markdown via the system share sheet and exported to a `.md` file.
- All pure logic (transport choice, markdown export, mapping) is JVM-tested; the v4→v5 migration is
  tested; CI unit-test job is green.

## 15. Plan phasing (for the implementation plan)

1. Room entities + `ChatDao` + `ChatRepository` + v4→v5 migration (with tests).
2. Transport: `ChatTransport` + selector (pure `chooseTransport` test) + both impls.
3. `ChatViewModel` + wire the existing `ChatScreen` to observed persistence (replace the ephemeral list).
4. Generation controls (stop/regenerate/edit/copy) on the `deleteTurnsAfter` primitive.
5. Markdown rendering + SansSerif prose (DESIGN.md log entry).
6. Model switch in chat + reload state + answered-by readout.
7. Conversation drawer + top bar.
8. Share/export (pure `conversationToMarkdown` + share sheet + SAF file export).
