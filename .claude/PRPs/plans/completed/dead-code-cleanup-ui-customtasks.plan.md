# Plan: Dead-Code Cleanup — `ui/` + `customtasks/` (verified 29-file pocket)

## Summary
A codemap refresh claimed most of `ui/` (114 files) and `customtasks/` (43 files) — the pre-fork
Gallery UI tree — was dead code, unreachable since the app-shell unification. A rigorous
reachability audit (import-graph BFS + Hilt `@IntoSet` multibinding trace, hand-verified against
source) found that claim **false**: only **29 files** are genuinely dead. The other 128 are live —
either directly wired into `MainActivity`/`RelaisAppShell`, or reachable only through Hilt/Dagger
multibinding (`@Provides @IntoSet fun ...(): CustomTask`), a mechanism no plain grep or import
search can see. This plan deletes exactly the verified-dead 29 files (+2 more found in the same
investigation, outside the original 157-file scope) in safety-gated increments, and explicitly
excludes everything whose liveness is real or unresolved.

## User Story
As a Relais maintainer (human or agent) navigating this codebase,
I want the confirmed-dead pre-fork Gallery files removed,
So that `find`/`grep`/codebase-map tools stop surfacing 29 files of unreachable code as if they
were live surface area, without risking a build break or a feature regression in the 128 files
that only *look* dead to a naive search.

## Problem → Solution
**Current state**: 29 files (`ui/navigation/GalleryNavGraph.kt`, `ui/home/{HomeScreen,SettingsDialog,...}`,
dead `ui/modelmanager/*`/`ui/common/*`/`ui/common/chat/*` files, `customtasks/common/SteadinessMonitor.kt`,
`customtasks/examplecustomtask/*`) plus root-package `GalleryApp.kt` and the dead
`class RelaisChatActivity` declaration are compiled into every build, appear in every codebase
search, and cost APK size + build time, with zero runtime purpose — nothing calls them.

**Desired state**: those 31 files are gone. Everything with a live Hilt `@IntoSet` binding
(`customtasks/agentchat/`, `mobileactions/`, `tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/`) or a
direct `RelaisAppShell`/`MainActivity` import (`ui/modelmanager/ModelManagerViewModel.kt`,
`ui/benchmark/*`, `ui/theme/*`, `ui/common/{MarkdownText,BufferedFadingMarkdownText,Accordions}.kt`,
`ui/home/LicensesActivity.kt`) is untouched, because deleting any of it either breaks the build
(Hilt annotation processing fails on a missing multibinding consumer) or removes a shipping
capability (skill management, MCP client+auth, WebView agent sandbox — none of which exist
anywhere else in the codebase).

## Metadata
- **Complexity**: Medium (well-scoped file count, but correctness-critical — a wrong deletion
  either breaks the build immediately, in which case CI catches it, or silently removes a runtime
  path that only a full instrumented run would catch — see Risks)
- **Source PRD**: N/A (originated from a codemap-refresh finding, corrected mid-investigation)
- **PRD Phase**: N/A
- **Estimated Files**: 31 delete + 1 partial-edit (`RelaisChatActivity.kt`, remove one class, keep
  the file) + doc updates already applied to `docs/CODEMAPS/{architecture,frontend}.md` and
  `.reports/codemap-diff.txt` this session (not part of this plan's task list — already done)

---

## UX Design
N/A — pure dead-code removal. No screen, route, or user-visible behavior changes. If any user
notices a behavior change after this plan executes, that is evidence a file was misclassified and
the change must be reverted (see Risks / rollback).

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/MainActivity.kt` | 1-135 | Confirms `RelaisAppShell` is the sole live entry; confirms `ModelManagerViewModel` + `ui.theme.GalleryTheme` are directly imported (must survive) |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisAppShell.kt` | 40-155 | Confirms `BenchmarkScreen`/`ModelManagerViewModel` composed directly in the live `NavHost`; confirms `ChatScreen()` (same-package call into `RelaisChatActivity.kt`, not the dead `class RelaisChatActivity`) |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/ui/modelmanager/ModelManagerViewModel.kt` | 190-210 | The `Set<@JvmSuppressWildcards CustomTask>` constructor parameter — the Hilt multibinding consumer that makes `agentchat`/`mobileactions`/`tinygarden`/`llmchat`/`llmsingleturn` live |
| P0 | `Android/src/app/src/main/java/cc/grepon/relais/customtasks/examplecustomtask/ExampleCustomTaskModule.kt` | full | The ONE `@IntoSet` binding that IS disabled (commented out) — proves the module pattern and shows what "genuinely disabled" looks like vs the 5 active ones |
| P1 | `Android/src/app/src/main/AndroidManifest.xml` | 105-125 | `ui.home.LicensesActivity` is the only `ui.`/`customtasks.` class with a manifest entry — must never be deleted regardless of Kotlin-import analysis |
| P1 | `Android/src/app/src/main/java/cc/grepon/relais/RelaisChatActivity.kt` | 100-160 | Shows the split: `class RelaisChatActivity : ComponentActivity()` (dead, no manifest entry, no `Intent`/`startActivity` reference anywhere) vs `internal fun ChatScreen()` in the same file (live, called from `RelaisAppShell.kt`) — only the class declaration is removed, the file stays |
| P2 | `docs/CODEMAPS/frontend.md` | full | This session's corrected codemap — the authoritative "what's live / what's dead / what's unresolved" reference, written from the same audit this plan is based on |
| P2 | `.reports/codemap-diff.txt` | tail (SAME-DAY CORRECTION section) | The narrative of how the original 157-file claim was disproven — read this before trusting any other "X is dead" claim in this codebase without re-verifying |

## External Documentation
No external research needed — this is Hilt/Dagger multibinding (`@IntoSet`), a well-understood
internal Android DI pattern already used correctly elsewhere in this codebase (`di/AppModule.kt`).
The risk here isn't understanding the mechanism, it's *finding every place it's used* before
deleting anything — which is why this plan leans on exhaustive `grep -rn "@IntoSet"` re-verification
at each step rather than external docs.

---

## Patterns to Mirror

### SMALL_INCREMENTAL_PRS
This repo's established convention (every PR merged this session): one logically-scoped change per
PR, JVM gate green before merge, squash-merge via `gh pr merge --squash --delete-branch`.
// SOURCE: `.claude/HANDOFF.md` "Merged this session" — every entry is a single-purpose PR
```
git checkout -b <branch> main
# ... change ...
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest
git commit -m "..."; git push -u origin <branch>
gh pr create ...
```

### VERIFY_BEFORE_DELETE
This session's own methodology for the reachability audit — re-verify with a fresh, narrow grep
right before acting, don't trust an earlier broad conclusion without re-checking the specific file.
// SOURCE: this session's Explore-agent reachability audit — the audit itself caught its own
// first-pass false positives (`GalleryApp.kt` treated as a live root) by re-verifying
```bash
# Before deleting any single file, confirm zero live importers AND zero @IntoSet binding of it:
grep -rn "cc\.grepon\.relais\.ui\.<pkg>\.<ClassName>\b" Android/src/app/src/main --include=*.kt \
  | grep -v "^Android/src/app/src/main/java/cc/grepon/relais/ui/<pkg>/<ClassName>.kt"
grep -rn "@IntoSet" Android/src/app/src/main --include=*.kt   # re-list all bindings, confirm none touch this file
```

### CI_GATE_FIRST
JVM tests are cheap and always run before anything heavier; Build APK (full assemble) is the real
proof a Hilt graph still resolves, since Hilt errors are annotation-processor-time, not JVM-test-time.
// SOURCE: `.claude/HANDOFF.md` "JVM gate (fast, always run)"
```bash
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest
./gradlew :app:assembleFullOpenDebug   # exercises Hilt/KSP codegen — the real Hilt-graph proof
```

---

## Files to Change

### Increment 1 — root-package + `ui/navigation/` dead entry points (2 files)
| File | Action | Justification |
|---|---|---|
| `Android/src/app/src/main/java/cc/grepon/relais/GalleryApp.kt` | DELETE | Zero callers anywhere; `MainActivity` calls `RelaisAppShell` directly (confirmed `MainActivity.kt:108`) |
| `Android/src/app/src/main/java/cc/grepon/relais/ui/navigation/GalleryNavGraph.kt` | DELETE | Only ever invoked from the now-deleted `GalleryApp()`; itself the root of the entire dead subtree |

### Increment 2 — `ui/home/` dead screens (6 files)
| File | Action | Justification |
|---|---|---|
| `ui/home/HomeScreen.kt` | DELETE | Only reachable via `GalleryNavGraph` (deleted in Increment 1) |
| `ui/home/SettingsDialog.kt` | DELETE | Only launched from `HomeScreen`; its `LicensesActivity` launch call is the ONLY live-code reference to `LicensesActivity`, which becomes unreachable-in-practice as a result (pre-existing gap, not newly caused — see Risks) |
| `ui/home/SquareDrawerItem.kt` | DELETE | `HomeScreen`-only component |
| `ui/home/PromoScreenGm4.kt` | DELETE | `HomeScreen`-only |
| `ui/home/MobileActionsChallengeDialog.kt` | DELETE | `HomeScreen`-only |
| `ui/home/NewReleaseNotification.kt` | DELETE | `HomeScreen`-only |

### Increment 3 — dead `ui/modelmanager/` screens (5 files — NOT `ModelManagerViewModel.kt`, that's live)
| File | Action | Justification |
|---|---|---|
| `ui/modelmanager/ModelManager.kt` | DELETE | Only reachable via `GalleryNavGraph` |
| `ui/modelmanager/ModelList.kt` | DELETE | `ModelManager.kt`-only |
| `ui/modelmanager/GlobalModelManager.kt` | DELETE | `GalleryNavGraph`-only route target |
| `ui/modelmanager/ModelImportDialog.kt` | DELETE | Dead-tree-only |
| `ui/modelmanager/PromoBannerGm4.kt` | DELETE | Dead-tree-only |

### Increment 4 — dead `ui/common/`, `ui/common/chat/`, `ui/common/modelitem/`, `ui/common/tos/`, `ui/icon/`, `ui/notifications/` (12 files)
| File | Action | Justification |
|---|---|---|
| `ui/notifications/NotificationsScreen.kt` | DELETE | `GalleryNavGraph`-only route target |
| `ui/common/EmptyState.kt` | DELETE | Dead-tree-only consumer |
| `ui/common/GlitteringShapesLoader.kt` | DELETE | Dead-tree-only consumer |
| `ui/common/LiveCameraView.kt` | DELETE | Dead-tree-only consumer (CameraX dep itself stays — also used by live `MessageInputText.kt`) |
| `ui/common/TaskIcon.kt` | DELETE | Dead-tree-only consumer |
| `ui/common/chat/MessageBodyClassification.kt` | DELETE | Only referenced within `ui/`/`customtasks/` dead subtree |
| `ui/common/chat/MessageBodyImageWithHistory.kt` | DELETE | Same |
| `ui/common/chat/ModelInitializationStatus.kt` | DELETE | Same |
| `ui/common/chat/ModelNotDownloaded.kt` | DELETE | Same |
| `ui/common/modelitem/DeleteModelButton.kt` | DELETE | Same |
| `ui/common/tos/AppTosDialog.kt` | DELETE | Same |
| `ui/icon/Deploy.kt` | DELETE | Same |

### Increment 5 — `customtasks/` dead files (5 files) + `RelaisChatActivity.kt` partial edit
| File | Action | Justification |
|---|---|---|
| `customtasks/common/SteadinessMonitor.kt` | DELETE | Zero references anywhere, including within its own subsystem |
| `customtasks/examplecustomtask/ExampleCustomTask.kt` | DELETE | Its `@IntoSet` module binding is commented out — genuinely disabled |
| `customtasks/examplecustomtask/ExampleCustomTaskModule.kt` | DELETE | The disabled `@IntoSet` module itself |
| `customtasks/examplecustomtask/ExampleCustomTaskScreen.kt` | DELETE | Same subsystem, same reason |
| `customtasks/examplecustomtask/ExampleCustomTaskViewModel.kt` | DELETE | Same subsystem, same reason |
| `RelaisChatActivity.kt` | UPDATE (remove `class RelaisChatActivity : ComponentActivity() { ... }` only) | No manifest entry, no `Intent`/`startActivity` reference anywhere in the codebase (verified); the file's `internal fun ChatScreen()` is live (called from `RelaisAppShell.kt`) and MUST remain |

## NOT Building
Explicitly out of scope for this plan — do not delete, do not "clean up while we're here":

- **`ui/modelmanager/ModelManagerViewModel.kt`, `ui/benchmark/*`** (5 files) — directly composed in
  the live `RelaisAppShell` NavHost (`benchmark/{model}` route). Deleting breaks compilation today.
- **`customtasks/agentchat/`** (22 files: skill manager, URL-based skill install, MCP client with
  OAuth/header auth, WebView agent sandbox) — Hilt `@IntoSet`-live, AND has no replacement anywhere
  in the new Relais-native stack. Removing this is a **product decision about cutting a shipping
  capability**, not a dead-code cleanup. If the answer is "yes, cut it," that's a separate plan with
  its own PR, changelog entry, and Play/Izzy release notes — not bundled into this one.
- **`customtasks/mobileactions/`, `customtasks/tinygarden/`** (12 files) — same Hilt-live reasoning.
- **`ui/llmchat/`, `ui/llmsingleturn/`** — same Hilt-live reasoning; also the original "built-in
  task" chat/single-turn screens this app forked from.
- **`ui/theme/*`, `ui/common/MarkdownText.kt`, `ui/common/BufferedFadingMarkdownText.kt`,
  `ui/common/Accordions.kt`** — directly imported by live code (`MainActivity`, `RelaisApplication`,
  `chat/ChatMessageList.kt`, `ui/benchmark/*`).
- **`ui/home/LicensesActivity.kt`** — manifest-declared, covered by `LicensesActivityProbe.kt`. Its
  only launch path (`SettingsDialog.kt`, deleted in Increment 2) is dead, making it
  unreachable-in-practice — but that's a **pre-existing product gap** (the open-source license
  viewer already has no live entry point today, independent of this cleanup) worth its own small
  follow-up issue ("wire LicensesActivity into RelaisConfigureActivity or the shell somewhere"),
  not a reason to delete a manifest-declared, test-covered Activity.
- **Resource cleanup** (`res/` drawables/strings only used by the 29 deleted files) — the audit
  produced a starting candidate list but flagged it as non-exhaustive. Do this as a **separate,
  later pass** using Android Studio's "Remove Unused Resources" lint after the code deletion lands
  and a full rebuild confirms no new references — resource removal has its own failure mode
  (removing a resource still referenced by XML/manifest that code-grep misses) and shouldn't be
  bundled with the code deletion's risk surface.
- **Dependency removal** (`build.gradle.kts`) — the audit checked every plausible candidate
  (CameraX, AppAuth, Moshi, MCP SDK/Ktor) and found each still has a live consumer. No dependency
  becomes removable as a result of this plan.
- **Resolving the DI-instantiated-vs-UI-reachable open question** — Hilt eagerly constructs
  `Set<CustomTask>` when `ModelManagerViewModel` is created (because it's a constructor parameter),
  meaning `agentchat`/`mobileactions`/`tinygarden`/`llmchat`/`llmsingleturn`'s `CustomTask` objects
  ARE instantiated at runtime today — but since the task-carousel `HomeScreen` (deleted in
  Increment 2) was the only UI that ever rendered a `CustomTask` picker, **no user-facing path
  currently lets anyone actually navigate into these screens**. Whether that's "safe to formally
  cut, just haven't yet" or "an accidental UX regression from an earlier unrelated change" is
  unresolved and explicitly not this plan's job to answer — flag as a separate investigation/issue
  if the team wants to pursue it.

---

## Step-by-Step Tasks

### Task 1: Increment 1 — delete `GalleryApp.kt` + `GalleryNavGraph.kt`
- **ACTION**: Branch off `main`, delete the 2 files, build.
- **IMPLEMENT**:
  ```bash
  git checkout -b chore/dead-code-cleanup-1-entry-points main
  git rm Android/src/app/src/main/java/cc/grepon/relais/GalleryApp.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/navigation/GalleryNavGraph.kt
  ```
- **MIRROR**: `SMALL_INCREMENTAL_PRS`, `CI_GATE_FIRST`
- **IMPORTS**: N/A (deletion only)
- **GOTCHA**: `GalleryNavGraph.kt` is the thing every other dead file in `ui/` ultimately routes
  through — deleting it FIRST, before its downstream screens, means the build will fail loudly at
  `GalleryNavGraph.kt`'s own now-broken imports if anything in Increments 2-4 turns out to still be
  referenced elsewhere. That's a deliberate ordering choice: it converts a silent "we assumed X was
  dead" mistake into an immediate compile error in the SAME increment, not three increments later.
- **VALIDATE**: `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest && ./gradlew :app:assembleFullOpenDebug` — both green means no live code depended on either file.

### Task 2: Increment 2 — delete `ui/home/` (6 files)
- **ACTION**: On a fresh branch off updated `main` (after Task 1 merges), delete the 6 files.
- **IMPLEMENT**:
  ```bash
  git checkout -b chore/dead-code-cleanup-2-home main
  git rm Android/src/app/src/main/java/cc/grepon/relais/ui/home/{HomeScreen,SettingsDialog,SquareDrawerItem,PromoScreenGm4,MobileActionsChallengeDialog,NewReleaseNotification}.kt
  ```
- **MIRROR**: `SMALL_INCREMENTAL_PRS`, `VERIFY_BEFORE_DELETE`
- **IMPORTS**: N/A
- **GOTCHA**: `SettingsDialog.kt` contains the only call site (`startActivity(Intent(context,
  LicensesActivity::class.java))`) that ever reaches `ui/home/LicensesActivity.kt`. Deleting this
  file makes `LicensesActivity` unreachable in practice, but `LicensesActivity.kt` ITSELF is not
  part of this deletion (manifest-declared, self-contained, test-covered — see NOT Building). Do
  not follow the "unreachable" thread into deleting `LicensesActivity.kt` too.
- **VALIDATE**: Same as Task 1's validate command. Additionally re-run
  `grep -rn "LicensesActivity::class" Android/src/app/src/main` — expect ZERO hits after this
  increment (confirms the gotcha above; if this ever shows a hit, something referenced
  `LicensesActivity` from live code and the audit missed it — stop and investigate before proceeding).

### Task 3: Increment 3 — delete dead `ui/modelmanager/` screens (5 files)
- **ACTION**: Branch, delete, build. **Explicitly do NOT touch `ModelManagerViewModel.kt`.**
- **IMPLEMENT**:
  ```bash
  git checkout -b chore/dead-code-cleanup-3-modelmanager main
  git rm Android/src/app/src/main/java/cc/grepon/relais/ui/modelmanager/{ModelManager,ModelList,GlobalModelManager,ModelImportDialog,PromoBannerGm4}.kt
  ```
- **MIRROR**: `SMALL_INCREMENTAL_PRS`, `VERIFY_BEFORE_DELETE`
- **IMPORTS**: N/A
- **GOTCHA**: This is the single highest-risk increment — it's the same directory as the
  definitely-live `ModelManagerViewModel.kt`, so a copy-paste `rm ui/modelmanager/*.kt` instead of
  the explicit 5-file list would delete the live file too and break the build immediately (which
  CI would catch) or, worse, silently break `RelaisAppShell`'s benchmark route if somehow the build
  still passed via a stale cached artifact. Use the explicit file list above, never a glob.
- **VALIDATE**: Full JVM gate + `assembleFullOpenDebug`, PLUS manually confirm
  `ui/modelmanager/ModelManagerViewModel.kt` still exists on disk (`ls` it) before committing —
  a cheap, explicit sanity check for the highest-risk increment.

### Task 4: Increment 4 — delete dead `ui/common/*`, `ui/notifications/`, `ui/icon/` (10 files)
- **ACTION**: Branch, delete, build.
- **IMPLEMENT**:
  ```bash
  git checkout -b chore/dead-code-cleanup-4-common main
  git rm Android/src/app/src/main/java/cc/grepon/relais/ui/notifications/NotificationsScreen.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/common/{EmptyState,GlitteringShapesLoader,LiveCameraView,TaskIcon}.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/common/chat/{MessageBodyClassification,MessageBodyImageWithHistory,ModelInitializationStatus,ModelNotDownloaded}.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/common/modelitem/DeleteModelButton.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/common/tos/AppTosDialog.kt \
         Android/src/app/src/main/java/cc/grepon/relais/ui/icon/Deploy.kt
  ```
- **MIRROR**: `SMALL_INCREMENTAL_PRS`, `VERIFY_BEFORE_DELETE`
- **IMPORTS**: N/A
- **GOTCHA**: `ui/common/` (not `ui/common/chat/`) has LIVE siblings (`MarkdownText.kt`,
  `BufferedFadingMarkdownText.kt`, `Accordions.kt`) one directory up from some of these targets —
  do not glob `ui/common/*.kt`, use the explicit list. `ui/common/LiveCameraView.kt` shares a
  dependency (CameraX) with the live `ui/common/chat/MessageInputText.kt` (NOT in this deletion
  list) — deleting `LiveCameraView.kt` does not remove the CameraX Gradle dependency, that's
  correct and expected (see NOT Building — no dependency removal in this plan).
- **VALIDATE**: Full JVM gate + `assembleFullOpenDebug`.

### Task 5: Increment 5 — `customtasks/` dead files + `RelaisChatActivity.kt` class removal (5 deletes + 1 edit)
- **ACTION**: Branch, delete the 5 `customtasks/` files, edit `RelaisChatActivity.kt` to remove
  only the dead `class RelaisChatActivity` block, build.
- **IMPLEMENT**:
  ```bash
  git checkout -b chore/dead-code-cleanup-5-customtasks-and-chatactivity main
  git rm Android/src/app/src/main/java/cc/grepon/relais/customtasks/common/SteadinessMonitor.kt \
         Android/src/app/src/main/java/cc/grepon/relais/customtasks/examplecustomtask/{ExampleCustomTask,ExampleCustomTaskModule,ExampleCustomTaskScreen,ExampleCustomTaskViewModel}.kt
  # Then hand-edit RelaisChatActivity.kt: delete the `class RelaisChatActivity : ComponentActivity() { ... }`
  # block only (currently lines ~107-151 per the mandatory-reading trace above — re-confirm exact
  # line range at edit time, line numbers drift). Keep every other declaration in the file,
  # especially `internal fun ChatScreen()`.
  ```
- **MIRROR**: `SMALL_INCREMENTAL_PRS`, `VERIFY_BEFORE_DELETE`
- **IMPORTS**: After removing the class, check whether any import at the top of
  `RelaisChatActivity.kt` becomes unused (e.g. `ComponentActivity`, `enableEdgeToEdge`, or similar
  Activity-lifecycle imports the class used but `ChatScreen()` doesn't) — remove those too, but
  ONLY imports confirmed unused by the remaining file content.
- **GOTCHA**: This is a partial-file edit, not a deletion — the highest-precision task in the
  plan. Read the full current file before editing (line numbers in this plan are from the
  mandatory-reading pass and may have drifted if earlier increments' PRs touched anything in the
  same file, which they shouldn't have). Re-run the "no manifest entry, no `Intent` reference"
  verification from Mandatory Reading immediately before this edit, not just from memory.
- **VALIDATE**: Full JVM gate + `assembleFullOpenDebug`, PLUS grep
  `RelaisChatActivity::class` and any `startActivity.*RelaisChatActivity` codebase-wide one more
  time post-edit — expect zero hits (same check as Task 2's LicensesActivity check, applied here).

### Task 6: Final sweep — confirm the dead-file count and re-run the full reachability check once more
- **ACTION**: After all 5 increments have merged, do one more full-repo pass to confirm nothing
  was missed and nothing new became stale in the interim (another dev/agent may have merged
  unrelated work touching `ui/`/`customtasks/` between increments).
- **IMPLEMENT**:
  ```bash
  git checkout main && git pull
  find Android/src/app/src/main/java/cc/grepon/relais/ui Android/src/app/src/main/java/cc/grepon/relais/customtasks -name "*.kt" | wc -l
  # Expect: 157 - 31 = 126 files remaining (114+43-31, since GalleryApp.kt/RelaisChatActivity.kt
  # class-removal were outside/within the original 157 count respectively — recompute precisely
  # against the actual count at execution time, don't hardcode-trust this arithmetic)
  ```
- **MIRROR**: N/A — verification step
- **IMPORTS**: N/A
- **GOTCHA**: If the count is off from expectation, do NOT assume the plan was wrong — first
  check whether unrelated work landed in the interim (new files added, or files this plan
  didn't touch got moved/renamed by someone else).
- **VALIDATE**: Full JVM gate + `assembleFullOpenDebug` one final time on `main` post-merge.
  Also re-run the on-device smoke test (see Validation Commands below) once, since this is the
  first time all 5 increments are combined together — an interaction between increments (however
  unlikely, given each is independently gated) is cheaper to catch here than after the fact.

---

## Testing Strategy

### Unit Tests
No new unit tests are needed — this plan removes code, it doesn't add behavior. The existing JVM
gate (`testFullOpenDebugUnitTest` etc.) is the regression check: if any deleted file was
transitively required by a test, the build fails at compile time, which is exactly the desired
failure mode.

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| Full JVM gate, all 3 flavors | (unchanged) | Green, same pass count as pre-deletion | No — this IS the safety net |
| `LicensesActivityProbe.kt` (existing, on-device) | Launch `LicensesActivity` directly via `am start` | Activity launches and renders | Confirms the manifest-live Activity still works even though its in-app launch path was removed in Increment 2 |
| `SkillUrlPolicyTest.kt`, `SkillUrlPinTest.kt`, `SkillHttpTest.kt` (existing) | (unchanged) | Green | Confirms `customtasks/agentchat/` — correctly NOT touched by this plan — is unaffected |

### Edge Cases Checklist
- [x] Hilt graph still resolves after each increment (`assembleFullOpenDebug` is the check —
  Hilt/KSP failures surface as compile errors, not runtime exceptions)
- [x] No manifest-declared Activity/Service/Receiver among the 31 deleted files (verified in the
  reachability audit; `LicensesActivity.kt` is the only manifest hit and is excluded)
- [x] No `androidTest`/`test` file targets a deleted file (verified: the only 4 tests touching
  `ui/`/`customtasks/` all target `LicensesActivity` or `customtasks/agentchat/`, neither deleted)
- [ ] On-device smoke test after all increments land (dashboard start/stop, chat send, models
  switch, benchmark route if reachable) — not yet run, part of Task 6 validation
- [ ] Play/Izzy release notes — N/A, this is invisible dead-code removal with no user-facing change

---

## Validation Commands

### Static Analysis / Build (run after EVERY increment, not just at the end)
```bash
cd Android/src
./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest
```
EXPECT: Zero failures, same test count as the pre-deletion baseline (a DROP in test count would
mean a test file got accidentally swept up in a deletion — investigate immediately).

```bash
./gradlew :app:assembleFullOpenDebug
```
EXPECT: `BUILD SUCCESSFUL`. This specifically exercises Hilt/KSP annotation processing — a broken
`@IntoSet` consumer chain fails HERE, not in the JVM test task above.

### Full Test Suite
Same commands as above cover this repo's full automated gate — there is no separate "full suite"
distinct from the 3-flavor JVM run per this repo's own `CLAUDE.md`/`HANDOFF.md` convention.

### Manual / On-Device Validation (once, after Task 6, not per-increment)
- [ ] Install the built APK on rango (or any test device), launch, confirm `MainActivity` →
  `RelaisAppShell` still renders (Dashboard tab loads).
- [ ] Navigate Dashboard → Chat → Models — all three bottom-nav tabs render without crash.
- [ ] Send one chat message (any transport), confirm response renders — proves
  `chat/ChatMessageList.kt`'s dependency on the KEPT `ui/common/MarkdownText.kt` /
  `BufferedFadingMarkdownText.kt` still resolves correctly.
- [ ] Open Models tab, confirm the model list/switch still works — proves the KEPT
  `ModelManagerViewModel.kt` still functions after its dead siblings were removed.
- [ ] `adb shell am instrument -w -e class cc.grepon.relais.LicensesActivityProbe <pkg>.test/androidx.test.runner.AndroidJUnitRunner` — confirms `LicensesActivity` (manifest-live, in-app-path now gone) still launches correctly via direct `am start`, per this repo's rango test recipe (see `.claude/HANDOFF.md`).

---

## Acceptance Criteria
- [ ] All 5 increments merged to `main`, each its own PR, each JVM-gate-green before merge
- [ ] `assembleFullOpenDebug` green after every increment (Hilt graph intact throughout)
- [ ] Final file count matches the verified 31-file deletion (recompute precisely at Task 6, don't
      trust a pre-computed number)
- [ ] Zero references to any deleted file remain anywhere in the codebase (re-grep at Task 6)
- [ ] On-device smoke test (Dashboard/Chat/Models + LicensesActivity probe) passes once, post-merge
- [ ] `docs/CODEMAPS/{architecture,frontend}.md` — already corrected this session — remain accurate
      after the actual deletion lands (spot-check, they described the END STATE this plan produces)

## Completion Checklist
- [ ] Code follows discovered patterns (small incremental PRs, JVM-gate-first)
- [ ] No `git rm` used a glob where an explicit file list was specified in this plan
- [ ] `ModelManagerViewModel.kt`, `ui/benchmark/*`, `customtasks/agentchat/`,
      `customtasks/mobileactions/`, `customtasks/tinygarden/`, `ui/llmchat/`, `ui/llmsingleturn/`,
      `ui/theme/*`, `ui/common/{MarkdownText,BufferedFadingMarkdownText,Accordions}.kt`,
      `ui/home/LicensesActivity.kt` all still exist on disk after every increment
- [ ] No hardcoded assumptions carried forward without re-verification (line numbers, file counts)
- [ ] No unnecessary scope additions (resource cleanup and dependency removal explicitly deferred)
- [ ] Self-contained — every increment's exact file list is in this plan, no additional
      codebase searching should be needed during execution beyond the re-verification greps
      this plan itself calls for

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| A file classified "dead" is actually reached via a mechanism neither the import-BFS nor the `@IntoSet` grep caught (e.g. Kotlin reflection, a string-based classloader lookup, a WorkManager `Worker` class-name reference) | Low (both agents specifically checked reflection-adjacent patterns like bare-value DI references) | High — silent runtime break, not a compile error | Per-increment `assembleFullOpenDebug` catches compile-time breaks; the on-device smoke test in Task 6 is the backstop for anything that only breaks at runtime. If ANY unexpected behavior surfaces post-merge, `git revert` the specific increment's PR — each is small and isolated, so revert blast radius is one increment, not all 31 files |
| Increment 3 (`ui/modelmanager/`) accidentally deletes `ModelManagerViewModel.kt` via a copy-paste glob mistake | Low if the explicit file list in Task 3 is followed | Critical — breaks `MainActivity`/`RelaisAppShell` compilation immediately | CI/JVM-gate catches this instantly (it's a compile error, not a subtle bug); Task 3's GOTCHA explicitly calls this out; the extra manual `ls` sanity check in Task 3's VALIDATE step |
| Someone reads the corrected `frontend.md`/this plan's "NOT Building" section and later decides unilaterally to also delete `customtasks/agentchat/` as a "quick follow-up," not realizing it's a shipping feature with no replacement | Medium (the files sit right next to genuinely-dead siblings, easy to lump together) | High — silent feature/capability regression (skill management + MCP client), no compile error at all since the code compiles fine, it's the DECISION to remove a feature that's the issue | This plan's "NOT Building" section states explicitly and repeatedly that this needs a separate product decision; the corrected `docs/CODEMAPS/frontend.md` carries the same warning for anyone who skips this plan and reads the codemap directly |
| Resource references (`res/` XML) to files scanned only via Kotlin-code grep, missed because a resource is referenced from another resource file (e.g. a style inheriting a drawable) rather than from Kotlin | Low-medium (explicitly flagged as non-exhaustive by the audit) | Low — unused resources are inert, not a functional regression, just APK bloat until cleaned | Explicitly deferred to a separate future pass (see NOT Building); not this plan's problem to solve now |

## Notes
- This plan's file counts and line-number references were established via two independent
  Explore-agent investigations plus hand-verification against source during this session
  (2026-07-19). If significant time passes before execution, or if other PRs touch `ui/` or
  `customtasks/` in the interim, re-run at minimum the `grep -rn "@IntoSet"` check and the specific
  file-existence checks in each Task's VALIDATE step before trusting this plan's file lists as
  still accurate — do not execute this plan blind against a codebase state it wasn't verified
  against.
- The corrected `docs/CODEMAPS/architecture.md` and `docs/CODEMAPS/frontend.md`, plus the
  same-day correction note in `.reports/codemap-diff.txt`, were already written this session and
  are NOT part of this plan's task list — they're prerequisite context, already done.
- A `customtasks/agentchat/`-or-not product decision (see NOT Building) is the natural next
  planning exercise after this cleanup lands, if the team wants to pursue it — but it deserves its
  own PRP, not a bolt-on to this one, given it's a feature-cut decision rather than a cleanup.
