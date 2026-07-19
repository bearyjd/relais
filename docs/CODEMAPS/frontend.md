# Frontend — UI (unified Relais shell; a small dead-code pocket, NOT most of the tree)

<!-- Generated: 2026-07-19 (corrected same-day after a rigorous reachability audit) | Files scanned: RelaisAppShell + chat/ + Dashboard/Models screens + full ui/(114) + customtasks/(43) import+DI graph | main @ ab345ff -->

> ⚠️ **CORRECTION, same day**: an earlier pass of this file claimed most of `ui/`+`customtasks/` (157
> files) was dead. A rigorous reachability audit (BFS over imports + Hilt `@IntoSet` multibinding,
> hand-verified) found that claim **false** — only **29 files** are actually dead. `GalleryNavGraph`
> and `GalleryApp()` are dead (confirmed), but `RelaisAppShell`/`MainActivity` still directly wire in
> `ui/modelmanager/ModelManagerViewModel.kt` and `ui/benchmark/*`. More importantly, `ModelManagerViewModel`
> constructor-injects `Set<@JvmSuppressWildcards CustomTask>` — a Hilt/Dagger multibinding — and
> `@Provides @IntoSet` modules in `customtasks/agentchat/`, `customtasks/mobileactions/`,
> `customtasks/tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/` all feed it. **No Kotlin import
> connects these to `RelaisAppShell` — a plain grep/BFS misses this entirely** — but Hilt wires and
> instantiates them at runtime, and deleting them breaks the build via annotation processing. See
> `.claude/PRPs/plans/dead-code-cleanup-ui-customtasks.plan.md` for the verified dead-file list and
> the open question this raises (DI-instantiated ≠ necessarily user-reachable — no live UI currently
> renders a picker for these CustomTasks, since the task-carousel `HomeScreen` is itself dead; whether
> that's a safe-to-remove UI gap or a real regression is unresolved, see the plan's "NOT Building"
> section).

## Navigation — `RelaisAppShell.kt` (NavHost, replaces GalleryNavGraph as the live entry)
```
MainActivity → RelaisAppShell (Scaffold + NavHost) → bottom nav:
  dashboard  → DashboardScreen       (node status/start-stop/endpoints/key)
  chat       → RelaisChatActivity's ChatScreen (Room-persistent conversations)
  models     → ModelsScreen          (model catalog + switch)
  benchmark/{model} → defined, not yet linked from bottom nav
CONFIGURE stays an Intent-launched Activity (RelaisConfigureActivity), not a nav route.
Deep links: resolveShellDeepLink — global_model_manager→MODELS, else→CHAT.
```
`RelaisShellViewModel` hoists a shared 1s-polling `StateFlow` (`WhileSubscribed(5000)`) feeding Dashboard.

## Dashboard (`DashboardScreen.kt`)
Status dot/word + phase line, LAN(:8443)/LOCAL(:8080) copyable endpoints, masked `AccessKeyChip` (show/hide, copy, share), read-only model summary → Models, CONFIGURE link, one state-appropriate primary button.

## Chat (`chat/`, `RelaisChatActivity.kt`, `ChatViewModel.kt`, `ChatRepository.kt`)
Room-backed via `ChatDao` (conversations/turns, truncate/regenerate/edit-and-resend). **Hybrid transport**: `ChatTransportSelector` health-probes the loopback HTTP server per send, prefers `HttpChatTransport` (SSE), falls back to `InProcessChatTransport` (direct `RelaisEngine.generate`). Markdown rendering, image/PDF/WAV/text attachments, export-to-.md, share, in-chat model switch via `ModelSwitch.kt`.

## Models (`ModelsScreen.kt`)
Current-model header + bottom-sheet model selector, reload-polling feedback.

## Theme (`RelaisPalette.kt`) — DESIGN.md tokens confirmed still applied
Amber `#FFB000` / Charcoal `#0B0B0D`, `FontFamily.Monospace`, dark-only — consistently used across shell/dashboard/chat/models. `ui/theme/*` is itself LIVE (imported directly by `MainActivity`/`RelaisApplication`/`ModelManagerViewModel`'s fan-out) — the inherited Gallery light/Nunito variant ships in the same theme files, unused in practice but not dead code.

## Still LIVE despite looking like old-Gallery leftovers (do not delete)
- `ui/modelmanager/ModelManagerViewModel.kt` + `ui/benchmark/*` — directly constructed/composed by `MainActivity.kt`/`RelaisAppShell.kt` (the `benchmark/{model}` route, unlinked from bottom nav but live in the NavHost).
- `customtasks/agentchat/` (22 files: skill manager + URL-based skill install, MCP client with OAuth/header auth, WebView agent sandbox), `customtasks/mobileactions/`, `customtasks/tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/` — all Hilt `@IntoSet`-bound into `ModelManagerViewModel`'s `Set<CustomTask>`; **no equivalent of agentchat's skill/MCP capability exists anywhere in the new Relais-native stack** (`nodetools/` is a fixed 4-tool list, no user-facing skill or MCP management at all).
- `ui/home/LicensesActivity.kt` — manifest-declared (`AndroidManifest.xml`), self-contained, covered by `LicensesActivityProbe.kt`. Its only launch path (`ui/home/SettingsDialog.kt`) IS dead, so it's currently unreachable in practice despite being manifest-live — a separate pre-existing product gap, not part of this cleanup.
- `ui/common/MarkdownText.kt`, `ui/common/BufferedFadingMarkdownText.kt`, `ui/common/Accordions.kt` — siblings of the dead `ui/common/chat/` tree, but consumed directly by the new `chat/ChatMessageList.kt` and by `ui/benchmark/*`.

## Genuinely dead code (verified, hand-checked — 29 files)
Only reachable, if at all, through the confirmed-dead `GalleryApp()` → `ui/navigation/GalleryNavGraph.kt` chain: `ui/navigation/GalleryNavGraph.kt`; `ui/home/{HomeScreen,SettingsDialog,SquareDrawerItem,PromoScreenGm4,MobileActionsChallengeDialog,NewReleaseNotification}.kt`; `ui/modelmanager/{ModelManager,ModelList,GlobalModelManager,ModelImportDialog,PromoBannerGm4}.kt`; `ui/notifications/NotificationsScreen.kt`; `ui/common/{EmptyState,GlitteringShapesLoader,LiveCameraView,TaskIcon}.kt`; `ui/common/chat/{MessageBodyClassification,MessageBodyImageWithHistory,ModelInitializationStatus,ModelNotDownloaded}.kt`; `ui/common/modelitem/DeleteModelButton.kt`; `ui/common/tos/AppTosDialog.kt`; `ui/icon/Deploy.kt`; `customtasks/common/SteadinessMonitor.kt`; `customtasks/examplecustomtask/*` (4 files, its `@IntoSet` binding is commented out). Plus, outside the original 157: root-package `GalleryApp.kt` and the dead `class RelaisChatActivity` declaration inside `RelaisChatActivity.kt` (the file's `ChatScreen()` function is live and stays). Full plan: `.claude/PRPs/plans/dead-code-cleanup-ui-customtasks.plan.md`.
