# PR Review: #214 — feat(relais): #211 in-app speech playback for assistant turns

**Reviewed**: 2026-07-26
**Author**: bearyjd
**Branch**: `feat/211-tts-in-app-playback` → `main`
**Scope**: 14 files, +2005 / -5
**Decision**: COMMENT (see "Reviewer independence" — an independent reviewer's verdict on these
findings alone would be APPROVE WITH COMMENTS: 0 critical, 0 high, 3 medium, 3 low, validation green)

## Reviewer independence — read this first

This review was produced by the same agent that authored the PR. That is a real limitation, not a
formality: three prior devil's-advocate passes over this branch produced 26 findings, and the two
most severe (a main-thread ~64 MB model load; a binder IPC held under the player lock) were both
*introduced* by the same author who later found them. Self-review has a demonstrated ceiling here.

Accordingly this is published as a **comment, not an approval**. The repo's own
`code-review.md` rule ("never self-approve in the same active context") applies, and GitHub refuses
author self-approval regardless. The findings below stand on their own; the *verdict* should come
from JD or an independent reviewer.

## Summary

Adds in-app TTS playback to assistant chat turns (`SPEAK` label per turn + a screen-level STOP
strip), backed by a new `TtsPlayer` over `AudioTrack` and a markdown→speech reducer. The feature is
well-bounded, matches DESIGN.md (decision logged), adds no new permission or network egress beyond
the already-disclosed voice download, and is covered at four layers (pure, ViewModel seam, on-device
player, on-device Compose UI) across two SoCs.

No correctness, security, or completeness defects found in this pass. The three MEDIUM items are
maintainability/process gaps — one of which (codemap drift) this repo has been bitten by before.

## Findings

### CRITICAL
None.

### HIGH
None.

### MEDIUM

**M1 — `ChatViewModel.speak()` is 76 lines, against this repo's <50-line target.**
`Android/src/app/src/main/java/cc/grepon/relais/ChatViewModel.kt`

This is not a style nit in context: `speak()` is the most concurrency-sensitive function in the PR
(generation token, supersede ordering, three dispatcher hops, four availability branches), and it is
precisely where two of the three prior review passes found real bugs. Length is actively working
against reviewability at the exact place reviewability matters most.

Suggested split — the branch bodies are independent and each is testable:

```kotlin
fun speak(turn: ChatTurn) {
  val generation = supersedeAndClaim()          // cancel + stop + bump, the invariant every path needs
  val engine = RelaisTtsEngineProvider.get() ?: return markSpeechUnavailable()
  speechJob = viewModelScope.launch { runSpeechAttempt(turn, engine, generation) }
}
```

**M2 — `docs/CODEMAPS/frontend.md` not updated; four new files unreferenced.**
`docs/CODEMAPS/frontend.md` §Chat enumerates the chat surface in detail (transport, markdown,
attachments, export, model switch) and does not mention speech playback. `tts/SpeechText.kt`,
`tts/TtsPlayer.kt`, `chat/ChatSpeech.kt` and `chat/ChatSpeechUi.kt` appear in no codemap.

This repo has a recorded history here: codemaps went 35 days stale, drift exceeded 30%, and the
resulting reachability confusion wrongly flagged ~157 files as dead before a hand audit corrected it.
A four-file, one-paragraph update now is cheap insurance against repeating that.

**M3 — Accessibility: the new action labels are plain `Text` + `clickable`, with no button role.**
`chat/ChatMessageList.kt` (`ActionLabel`), `chat/ChatSpeechUi.kt` (`SpeakingStopStrip`)

TalkBack announces these as text, not as buttons. Worse, the disabled state still attaches
`clickable` and no-ops inside the lambda:

```kotlin
onClick = {
  if (!enabled) return@ActionLabel   // node is still actionable to a11y services
```

so `SYNTHESIZING` is announced as an actionable control that silently does nothing. The gap is
partly pre-existing (`COPY`/`REGEN` share `ActionLabel`), so consistency is an argument for leaving
it — but this PR both extends the pattern and, being a text-to-speech feature, is unusually likely
to matter to the users affected.

```kotlin
modifier = Modifier
  .semantics { role = Role.Button }
  .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
```

### LOW

**L1** — `speechDispatcher` is a production constructor parameter existing solely for tests. It is
defaulted and documented, and it was added for a real reason (a hard-coded `Dispatchers.IO` escapes
virtual time), but it is production API shaped by test needs.

**L2** — `TtsPlayer` holds mutable `var current` / `var focusHolder` against the global immutability
guidance. Justified: it models native resource ownership and is lock-guarded throughout. Noted as a
deliberate, contained deviation rather than an oversight.

**L3** — `libs.versions.toml` `espressoCore` 3.6.1 → 3.7.0 is the only non-test-*source* change in the
PR. Forced, not optional: 3.6.1 reflects on `InputManager.getInstance()`, removed in Android 17, so
every Compose UI test errors without it. Existing probes re-verified green after the bump
(`LicensesActivityProbe` 2/2, `SpeechPlaybackProbe` 6/6). Flagged only because dependency changes
deserve explicit reviewer attention.

## Validation Results

| Check | Result |
|---|---|
| JVM unit tests (3 flavors, local) | **Pass** — 988 tests, 0 failures |
| Build Android APK (CI) | **Pass** |
| JVM unit tests (CI) | **Pass** |
| gitleaks / trufflehog | **Pass** |
| license headers | **Pass** |
| On-device `SpeechPlaybackProbe` | **Pass** — rango 6/6, comet 5/5 + 1 skipped |
| On-device `ChatSpeechUiProbe` | **Pass** — rango 14/14, comet 14/14 |
| Lint / typecheck | N/A (Kotlin compile is the gate; clean) |

## Security review

No findings. No new permission, no new IPC, no new storage, and no new network egress beyond the
voice download already disclosed in `privacy-policy.md` (via #212/#213). Two boundaries verified
rather than assumed:

- `SpeechState.Failed.message` can carry absolute storage paths and is **never rendered** — now
  enforced by a UI test (`theFailureMessageIsNeverRendered`), not just a KDoc note.
- Failure logging records a turn **id**, not turn content, keeping user prose out of logcat.

## Files Reviewed

| File | Change |
|---|---|
| `tts/SpeechText.kt` | Added |
| `tts/TtsPlayer.kt` | Added |
| `chat/ChatSpeech.kt` | Added |
| `chat/ChatSpeechUi.kt` | Added |
| `ChatViewModel.kt` | Modified |
| `RelaisChatActivity.kt` | Modified |
| `chat/ChatMessageList.kt` | Modified |
| `test/.../SpeechTextTest.kt` | Added |
| `test/.../chat/ChatSpeechTest.kt` | Added |
| `test/.../ChatViewModelSpeechTest.kt` | Added |
| `androidTest/.../SpeechPlaybackProbe.kt` | Added |
| `androidTest/.../ChatSpeechUiProbe.kt` | Added |
| `gradle/libs.versions.toml` | Modified (espresso 3.6.1→3.7.0) |
| `DESIGN.md` | Modified (decision log) |
