# Relais — Agent-Native Roadmap

Goal: an AI coding agent can pick up a raw bug report or feature request and
**reproduce → implement → test → verify** it with minimal human input.

**Baseline (read before doubting this document):** this repo is already unusually
agent-friendly for an Android project — 78 JVM unit test files with zero device
dependency, a CI matrix that gates GMS-leak/permission/16KB-alignment regressions,
a hand-written `docs/CODEMAPS/`, and `.claude/HANDOFF.md` session logs that already
function as a rolling tribal-knowledge dump. The gaps below are the specific,
narrow places where an agent still has to stop and ask a human, not a general
"this repo needs tests" finding.

Items are ranked by **Human-Attention-Saved per Unit of Effort** (HAS/E) — how much
a human would otherwise be pinged for, divided by how much implementation work it
takes an agent to close the gap.

---

## Top 5 — immediately actionable

### 1. Fill in the two stub READMEs that agents will search first — DONE (2026-07-07)
**Status:** `model_allowlists/README.md` was the literal stub and now has full content (see item 2
below — same deliverable). `mcp/README.md` turned out to **already contain real content** (the
upstream Gallery MCP walkthrough, committed in `7449604`) — not the stub this item's original
audit assumed; it was missing only the Relais-specific framing. Added a "Relais-specific notes"
section up top (file list: `McpManagerViewModel.kt`, `McpServersSerializer.kt`,
`McpToolCallPermissionDialog.kt`, `McpToolManagerBottomSheet.kt`, `McpManagerBottomSheet.kt`,
`AddMcpServerFromUrlDialog.kt`, `AddMcpDisclaimerDialog.kt`, `mcp.proto`; the permission model; the
distinction from the node's own `node_tools`/`tools` HTTP surface) and labeled the existing
walkthrough as upstream content. Doc-only change, no tests to run.

Original wording below for context (the "both are stubs" premise was only half true — see above):

`mcp/README.md` and `model_allowlists/README.md` both currently contain only the
literal text `# This is a readme file`. These are exactly the two places the task
brief flagged as "MCP conventions" and "model allowlist update procedure" — an
agent (or human) grepping the repo root for either topic hits a placeholder and
has to fall back to asking a person or reverse-engineering from source.

- **Files:** `/home/user/Documents/vibe-code/relais/mcp/README.md`,
  `/home/user/Documents/vibe-code/relais/model_allowlists/README.md`
- **Fix:**
  - `mcp/README.md`: the real, Relais-specific MCP implementation lives in
    `Android/src/app/src/main/java/cc/grepon/relais/customtasks/agentchat/Mcp*.kt`
    (`McpManagerViewModel`, `McpServersSerializer`, `McpToolCallPermissionDialog`,
    `McpToolManagerBottomSheet`, `AddMcpServerFromUrlDialog`,
    `AddMcpDisclaimerDialog`) plus `Android/src/app/src/main/proto/mcp.proto` for
    the persisted server-list schema. Replace the stub with: what MCP means in
    this repo (in-app agent-chat client, distinct from the node's own
    `node_tools`/`tools` HTTP surface described in `Function_Calling_Guide.md`),
    the file list above, the permission model (per-invocation prompt vs
    "always allow", `McpToolCallPermissionDialog`), and a link to the existing
    upstream walkthrough content (Add Local/Cloud Server) which is still useful
    end-user documentation and should be kept, just clearly labeled as
    upstream-Gallery content rather than Relais-specific.
  - `model_allowlists/README.md`: document what's in this directory (see item 2).
- **Acceptance:** both files contain real prose, no residual placeholder line;
  each links to the concrete source files it describes.

### 2. Document the model-allowlist update procedure — DONE (2026-07-07)
**Status:** `model_allowlists/README.md` written: what the directory is (a diffable history of a
runtime-fetched, version-keyed allowlist — not something the app reads directly), what fields
matter (`accelerators`/`visionAccelerator` as the safety-relevant ones, per the G5/E4B precedent),
a note that the repo-root `model_allowlist.json` is a stale/vestigial 4-model Gemma-3n-era snapshot
unrelated to the versioned files here (confirmed by diff — no shared entries with `1_0_15.json`,
and no `.kt` file references that literal repo-root path), and a 5-step bump runbook ending in the
five named tests. Verified against source: `RelaisModelProvisioner.kt` (`allowlistUrl()`,
`resolveModel`), `ModelManagerViewModel.kt` (`MODEL_ALLOWLIST_FILENAME`, `getAllowlistUrl`),
`RelaisEngine.kt` (`isG5Incompatible`, `G5_INCOMPATIBLE_MODEL_IDS`), `RelaisConfig.kt`
(`DEFAULT_MODEL_ID`). Ran the five named tests
(`Pixel10DefaultModelTest`, `RelaisModelCatalogTest`, `StaleModelPathTest`,
`RelaisModelRefProvisionTest`, `RelaisEngineConfigTest`) via
`./gradlew :app:testFullOpenDebugUnitTest --tests ...` — all green (doc-only change, ran them to
confirm the runbook's own prescribed command works, not because anything changed).

Original wording below for context:

`RelaisModelProvisioner.kt` and `ui/modelmanager/ModelManagerViewModel.kt` fetch
`model_allowlist.json` from
`https://raw.githubusercontent.com/google-ai-edge/gallery/.../model_allowlists`
**at runtime** — the live allowlist is authoritative (per `SPIKE-FINDINGS.md` Q1's
"live allowlist; repo snapshots are stale" note). But the repo also carries 13
committed snapshots (`model_allowlists/1_0_4.json` … `1_0_15.json`,
`ios_1_0_0.json`) plus a root `model_allowlist.json`, and there is no written
procedure for: when to add a new snapshot, how to diff it against the previous
one for backend/context/device-gating changes, or which tests must be re-run
(`Pixel10DefaultModelTest`, `RelaisModelCatalogTest`, `StaleModelPathTest`,
`RelaisModelRefProvisionTest`). Today this is tribal knowledge held by whoever
last did it (visible only as scattered commits, not as a doc).

- **Files to create/edit:** `model_allowlists/README.md` (procedure doc);
  reference from `CLAUDE.md` (added — see deliverable 3) and `DEVELOPMENT.md`.
- **Fix:** write a short runbook: (a) fetch the new upstream JSON version, (b)
  `diff` against the prior snapshot and flag new `accelerators`/device-gating
  fields (the G5/E4B SIGSEGV precedent in `SPIKE-FINDINGS.md` means a new entry's
  `accelerators` value is safety-relevant, not cosmetic), (c) add the file under
  `model_allowlists/<version>.json`, (d) update
  `MODEL_ALLOWLIST_FILENAME`/version constant in `ModelManagerViewModel.kt` if the
  bundled fallback version changes, (e) run the four tests named above plus
  `RelaisEngineConfigTest`.
- **Acceptance:** a fresh allowlist bump can be done by an agent following the doc
  alone, ending with the four tests green.

### 3. Add a fixture/replay corpus for tool-calling behavior — PARTIALLY DONE (2026-07-07)
**Status:** built the JVM fixture-replay harness this item calls for
(`Android/src/app/src/test/resources/fixtures/tool-calls/*.json` +
`RelaisToolFixtureReplayTest.kt`), covering both cases in the acceptance criteria: the typed-
argument quirk (`node-tool-typed-argument.json`, replayed through
`cc.grepon.relais.nodetools.NodeToolArgs`) and a multi-turn tool-call round trip
(`multi-turn-toolcall-roundtrip.json`, replayed through `buildPromptParts`). Both pass in the JVM
suite (`./gradlew :app:testFullOpenDebugUnitTest --tests cc.grepon.relais.RelaisToolFixtureReplayTest`
— 2/2 green, no device).

**Deviation from the original ask, flagged honestly:** this session had no `androidTest` probe logs
to seed from (none exist in the repo/session state) and hardware probing was out of scope (hard
rule: no device/hardware probes). So the fixtures are **hand-authored surrogates**, not captured
real litertlm output — clearly marked `"provenance": "synthetic"` in each fixture file and in
`fixtures/tool-calls/README.md`, with instructions for swapping in a real capture the next time a
tool-calling probe runs on hardware (the existing `Log.i(TAG, "... toolCall[$i] ... args=...")` in
`ToolCallingProbe.kt` already logs the raw shape needed — no probe code change was required). The
acceptance criterion "at least the typed-argument-quirk case and one multi-turn tool-result
round-trip are present as fixtures" is met structurally; the item's stronger implicit goal (fixtures
that prove real model behavior, not just documented behavior) is not yet met and needs a hardware
session.

Original wording below for context:

This is the sharpest verification gap. `RelaisToolParsingTest.kt`,
`ToolConversationParseTest.kt`, `NodeToolsTest.kt` etc. are strong at wiring/parsing
but use **hand-written synthetic JSON**, not captured real model output. The only
place real model tool-calling behavior gets exercised is
`androidTest/ToolCallingProbe.kt` / `MultiTurnReplayProbe.kt` / `NodeToolsProbe.kt`
— all instrumented, all requiring a physical device with a model staged, and
(per `DEVELOPMENT.md`) **not run in CI**. `.claude/HANDOFF.md` says this outright:
"the JVM suite covers wiring/escaping/state-handling only, NOT model-output
quality." An agent without hardware access cannot verify a tool-calling
regression fix beyond "the parser still accepts my synthetic example."

- **Files to create:** `Android/src/app/src/test/resources/fixtures/tool-calls/*.json`
  (captured, real litertlm tool-call payloads, including the documented
  typed-argument quirk `{"type":"STRING","value":"…"}` from
  `Function_Calling_Guide.md`), and a `RelaisToolFixtureReplayTest.kt` in
  `test/java/cc/grepon/relais/` that loads each fixture and asserts the parser's
  output shape — same style as the existing `RelaisToolParsingTest.kt` but backed
  by frozen real captures instead of inline literals.
- **How to seed it:** the next time any of the `androidTest` tool-calling probes
  run on hardware (they already log full request/response), capture the raw JSON
  and drop it in as a new fixture — a one-line addition to
  `ToolCallingProbe.kt`/`MultiTurnReplayProbe.kt`'s existing logging is enough;
  no new device work is required beyond runs that already happen.
- **Acceptance:** at least the typed-argument-quirk case and one multi-turn
  tool-result round-trip are present as fixtures; `RelaisToolFixtureReplayTest`
  runs in the JVM suite (`testFullOpenDebugUnitTest`) with no device.

### 4. Give bug reports a machine-replayable format (HAS/E: high, effort: medium)
`Bug_Reporting_Guide.md` is written entirely for a human with a physical device:
adb serial, logcat greps, a bug-report zip. There is no path from "a user pasted
a reported conversation + model id into an issue" to "an agent can replay it and
get a pass/fail" without hardware. `MultiTurnReplayProbe.kt` is the closest
existing thing but is instrumented-only.

- **Files to create:** `docs/agent-bug-repro.md` (the agent-facing counterpart to
  `Bug_Reporting_Guide.md`) plus a `test/resources/transcripts/` directory and a
  `TranscriptReplayTest.kt` skeleton that takes a `messages[]` + `tools[]` +
  `model` JSON blob (the same shape curl'd against `/v1/chat/completions`) and
  replays it through the HTTP-parsing/tool-shaping layer (`parseTools`,
  `parseToolChoice`, `RelaisToolParsing`, `ToolResponseShaping`) without touching
  the engine — this catches request-shape and response-shaping bugs (the
  majority of non-model-quality bugs) fully offline.
- **Explicitly out of scope / honestly flagged:** true model-output-quality bugs
  (wrong tool chosen, hallucinated args) still need device replay via
  `MultiTurnReplayProbe`; the new doc should say so rather than imply full
  offline reproduction, consistent with this repo's existing "never claim
  hardware-only facts without a device" discipline (`SPIKE-FINDINGS.md`'s
  honesty/stop-conditions section is a good model to imitate here).
- **Acceptance:** a pasted bug-report conversation can be dropped into
  `test/resources/transcripts/` and run through `TranscriptReplayTest` to
  confirm/rule out a parsing-layer cause before anyone reaches for a device.

### 5. Break up `RelaisHttpServer.kt` (1762 lines) along endpoint boundaries (HAS/E: medium, effort: medium-high)
This is the one clear structural obstacle. One file currently routes and
implements chat completions, images, embeddings, batch, RAG search, the
`/experiments` dashboard, and multipart parsing (`RelaisHttpIo.kt` was already
split out for the byte-safe reader — good precedent). An agent fixing one
endpoint has to load and reason about all of them, and the file is over 2x this
repo's own 800-line guideline. This is the highest-effort item here, so it's
ranked last, but it compounds: every future endpoint change pays the entanglement
tax.

- **File:** `Android/src/app/src/main/java/cc/grepon/relais/RelaisHttpServer.kt`
- **Fix:** extract per-endpoint-group handler files (e.g.
  `RelaisChatHandler.kt`, `RelaisImagesHandler.kt`, `RelaisEmbeddingsHandler.kt`,
  `RelaisBatchHandler.kt`, `RelaisExperimentsHandler.kt`) behind the routing
  table that stays in `RelaisHttpServer.kt`, mirroring the extraction pattern
  already used for `RelaisHttpIo.kt`. The existing test suite (`RelaisHttpIoTest`,
  `RelaisImagesEndpointTest`, `RelaisEmbeddingsEndpointTest`, `OpenAiRequestParserTest`,
  etc.) is the safety net — this refactor should be a pure move with the full
  suite green before/after, no behavior change.
- **Acceptance:** `RelaisHttpServer.kt` under ~400 lines (routing + shared
  concerns only); each extracted handler file under 800 lines; full JVM suite
  green with no test changes required.

---

## Audit notes (for context, not immediately actionable)

**Human-judgment chokepoints beyond the top 5:**
`CLAUDE.md` (root) only points agents at `docs/litertlm-native-api.md` and
`DESIGN.md`. It does not mention `SPIKE-FINDINGS.md` (device-specific hard facts:
G5/E4B SIGSEGV, backend-selection rules), `.claude/HANDOFF.md` (rolling session
state — the closest thing this repo has to a changelog of *why*), or
`DEVELOPMENT.md`/`docs/RUNBOOK.md` (build commands, common-issues table). An
agent picking up a bug cold has no forcing function to read the one file
(`SPIKE-FINDINGS.md`) that says "don't re-derive this, don't re-file this
upstream bug." Addressed partially in the CLAUDE.md update below; the deeper fix
(a standing "read these N files before touching anything" checklist) is covered
by the new pointer section.

**Verification gaps beyond the top 5:** the `androidTest`/`androidTestFull`
probes (18 + 3 files) are all hardware-gated and CI-excluded by design (real
model inference can't run in a GitHub Actions runner) — this is a reasonable,
explicit tradeoff (see `DEVELOPMENT.md`'s command table), not an oversight. The
gap is narrowly that *some* of what they test (request/response shaping, not
model quality) could be pulled forward into the JVM suite via fixtures — that's
item 3.

**Structural note beyond item 5:** `ui/modelmanager/ModelManagerViewModel.kt`
(1478 lines) and `customtasks/agentchat/SkillManagerViewModel.kt` (1261 lines)
have the same shape of problem as `RelaisHttpServer.kt` but are lower priority —
they're UI-layer and change less often than the HTTP surface that every
function-calling bug report touches.
