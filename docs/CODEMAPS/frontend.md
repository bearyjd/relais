# Frontend — UI (mostly inherited Gallery)

<!-- Generated: 2026-06-26 | Files scanned: ui/ (114) + customtasks/ (41) | main @ 44879e6 -->

> This app is a fork of `google-ai-edge/gallery`. Most of `ui/` and `customtasks/` is **inherited
> Gallery** (re-namespaced, NOT redesigned → off `DESIGN.md`). Relais-authored UI = the node-control
> surface + the amber/monospace theme.

## Navigation (ui/navigation/GalleryNavGraph.kt ~662L)
```
MainActivity → Compose root → NavHost:
  homepage              → HomeScreen (task carousel)            [Gallery]
  model_list            → ModelManager (per-task selector)      [Gallery]
  route_model/{task}/{model} → CustomTask MainScreen            [Gallery shell]
  model_manager         → GlobalModelManager                    [Gallery]
  notifications         → NotificationsScreen
  benchmark/{model}     → BenchmarkScreen                       [Gallery]
deep links: com.ventouxlabs.relais://{model|global_model_manager|<taskId>}?query=
```
Headless node control is **Activities, not nav routes**: `RelaisControlActivity` (exported launcher,
API-key-gated start/stop), `TriageControlActivity` (#7), `PromptTemplateEditorActivity` (#12),
`NfcWriteActivity` (#15), `LicensesActivity`.

## Relais-authored vs inherited
- **Relais:** `ui/theme/` (amber-on-near-black, monospace), `ui/icon/`, node-control Activities, parts of `customtasks/agentchat` (node chat + MCP/skill managers).
- **Inherited Gallery (off-DESIGN.md):** `HomeScreen` (~1168L, Nunito/promo), `LlmChatScreen` (~370L), `LlmSingleTurnScreen`, `BenchmarkScreen`, `ui/common/chat/` (30+ message renderers), `ui/common/` utilities, model-manager screens.

## State management
Hilt `@HiltViewModel` + `StateFlow`. Hubs:
- `ModelManagerViewModel` (~1478L) — central: selected model, download/init status, tasks.
- `ChatViewModel` (~581L) — base chat state (messages, streaming, protobuf history); subclassed per task.
- Custom-task VMs: Skill/Mcp/MobileActions/TinyGarden.

## Theme (ui/theme/)
DESIGN.md direction present in `Color.kt`/`Type.kt`: amber `#FFB000` on near-black `#0B0B0D`, `FontFamily.Monospace`, dark-only, live-pulse dot. **But** inherited Gallery light scheme + Nunito + promo screens still ship (HomeScreen et al.) — the redesign is incomplete.

## customtasks/ (41 files)
- **agentchat/** (~22 files, node-control surface): `AgentChatScreen` (~809L) WebView agent sandbox; `SkillManagerBottomSheet` (~1085L) + `AddSkillFromUrlDialog` — ⚠️ **add-skill-from-URL = SSRF / prompt-injection surface** (host allowlist `google-ai-edge.github.io` only; descriptor content unvalidated, skills run JS in WebView). SSRF guard added #72.
- **mobileactions/**: on-device action picker (SMS/email/calendar wrappers exposed as LLM tools).
- **tinygarden/**: web-based prototyping sandbox (WebView).
- **common/**: `CustomTask` interface + data; **examplecustomtask/**: template.

## Key files
| Path | Purpose | ~LOC |
|---|---|---|
| ui/navigation/GalleryNavGraph.kt | routes, deep links | 662 |
| ui/modelmanager/ModelManagerViewModel.kt | central state hub | 1478 |
| ui/common/chat/ChatViewModel.kt | base chat state | 581 |
| ui/home/HomeScreen.kt | launcher carousel (Gallery) | 1168 |
| customtasks/agentchat/AgentChatScreen.kt | node agent chat [Relais] | 809 |
| customtasks/agentchat/SkillManagerBottomSheet.kt | skill registry [SSRF] | 1085 |
| ui/theme/{Color,Type,Theme}.kt | DESIGN.md amber/mono | ~290 |
