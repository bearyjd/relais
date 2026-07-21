# Implementation Report: Dead-Code Cleanup — `ui/` + `customtasks/`

## Summary
Deleted the 29 files verified genuinely dead in `ui/`+`customtasks/` (plus root-package
`GalleryApp.kt` and the dead `class RelaisChatActivity` declaration, outside the original
157-file scope), across 5 gated increments matching the plan exactly. All Hilt `@IntoSet`-live
code (`customtasks/agentchat/`, `mobileactions/`, `tinygarden/`, `ui/llmchat/`,
`ui/llmsingleturn/`) and everything directly wired into `RelaisAppShell`
(`ModelManagerViewModel.kt`, `ui/benchmark/*`) was left untouched, per the plan's NOT-Building
section. Final sweep caught and fixed one process failure (see Deviations) and two stale
comments left dangling by the deletions.

## Assessment vs Reality

| Metric | Predicted (Plan) | Actual |
|---|---|---|
| Complexity | Medium | Medium — matched |
| Confidence | 8/10 | Justified — only issue was a process mistake (git staging), not a wrong file classification |
| Files Changed | 31 delete + 1 partial-edit | 31 delete + 1 partial-edit + 2 comment fixes (found during final sweep, not predicted) |

## Tasks Completed

| # | Task | Status | Notes |
|---|---|---|---|
| 1 | Increment 1 — GalleryApp.kt + GalleryNavGraph.kt | ✅ Complete | PR #194. No deviation. |
| 2 | Increment 2 — ui/home/ (6 files) | ✅ Complete | PR #195. Verified zero `LicensesActivity::class` refs post-deletion, as planned. |
| 3 | Increment 3 — ui/modelmanager/ (5 files) | ✅ Complete | PR #196. Highest-risk increment per the plan; explicit file list + manual `ModelManagerViewModel.kt` survival check both held. |
| 4 | Increment 4 — ui/common/* etc (12 files) | ✅ Complete | PR #197. **Deviation**: `gitleaks` CI check failed identically across 5 fresh runs over ~1hr due to sustained GitHub-side API 503s (not this repo's code — confirmed via log inspection, core API health checks, and a direct API GET succeeding while the Action's calls failed). Resolved by waiting for GitHub's outage to clear rather than bypassing the required check; user explicitly declined an admin-merge override when offered. |
| 5 | Increment 5 — customtasks/ (5 files) + RelaisChatActivity edit | ⚠️ Complete, with a caught-and-fixed process failure | PR #198 merged missing the `RelaisChatActivity.kt` edit — see Deviations. Follow-up PR #199 landed the correction. |
| 6 | Final sweep + on-device smoke test | ✅ Complete | Recount (128 files, exactly 157−29), re-grep (3 false-positive hits investigated and confirmed benign — see Deviations), 2 stale comments found and fixed (PR #200), full JVM+build gate green on synced `main`, on-device smoke test on rango (Dashboard/Chat/Models all render; `LicensesActivityProbe` 2/2 green). |

## Validation Results

| Level | Status | Notes |
|---|---|---|
| Static Analysis (JVM gate) | ✅ Pass | `testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest` green on every increment and on the final synced `main` |
| Build (Hilt/KSP graph) | ✅ Pass | `assembleFullOpenDebug` green on every increment — the real proof no live `@IntoSet` consumer chain broke |
| Manual re-verification | ✅ Pass | Increment 3: `ModelManagerViewModel.kt` existence checked before every commit involving that directory. Increment 5 (redo): `git diff --cached --stat` checked before commit, not just `git status --short` |
| Integration (on-device) | ✅ Pass | rango (Pixel 10 Pro Fold): Dashboard/Chat/Models tabs all render cleanly post-cleanup (screenshots captured); `LicensesActivityProbe` 2/2 green, confirming the manifest-live, now-unreachable-in-app `LicensesActivity` still functions |
| Edge Cases | ✅ Pass | See plan's checklist — all items satisfied |

## Files Changed

| File | Action | Increment |
|---|---|---|
| `GalleryApp.kt` | DELETED | 1 |
| `ui/navigation/GalleryNavGraph.kt` | DELETED | 1 |
| `ui/home/{HomeScreen,SettingsDialog,SquareDrawerItem,PromoScreenGm4,MobileActionsChallengeDialog,NewReleaseNotification}.kt` (6) | DELETED | 2 |
| `ui/modelmanager/{ModelManager,ModelList,GlobalModelManager,ModelImportDialog,PromoBannerGm4}.kt` (5) | DELETED | 3 |
| `ui/notifications/NotificationsScreen.kt`, `ui/common/{EmptyState,GlitteringShapesLoader,LiveCameraView,TaskIcon}.kt`, `ui/common/chat/{MessageBodyClassification,MessageBodyImageWithHistory,ModelInitializationStatus,ModelNotDownloaded}.kt`, `ui/common/modelitem/DeleteModelButton.kt`, `ui/common/tos/AppTosDialog.kt`, `ui/icon/Deploy.kt` (12) | DELETED | 4 |
| `customtasks/common/SteadinessMonitor.kt`, `customtasks/examplecustomtask/{ExampleCustomTask,ExampleCustomTaskModule,ExampleCustomTaskScreen,ExampleCustomTaskViewModel}.kt` (5) | DELETED | 5 |
| `RelaisChatActivity.kt` | UPDATED | 5 (attempted), landed in follow-up (#199) — removed dead `class RelaisChatActivity`, moved its KDoc onto `ChatScreen()`, removed 6 now-unused imports |
| `MainActivity.kt` | UPDATED | Final sweep (#200) — 1-line comment fix (stale `GalleryNavGraph` reference) |
| `customtasks/common/CustomTask.kt` | UPDATED | Final sweep (#200) — KDoc pointer repointed from deleted `ExampleCustomTask` to live `MobileActionsTask` |

**Totals**: 31 files deleted, 3 files edited (1 code, 2 comment-only).

## Deviations from Plan

1. **`gitleaks` CI outage on PR #197** (not a plan deviation, but a significant execution event). GitHub's API returned 503s on the specific calls the `gitleaks-action` makes (user license lookup, PR-commits fetch, rerun-trigger, log-fetch) across 5 independently-triggered fresh CI runs over roughly an hour. Confirmed external and unrelated to this repo's code: `Build Android APK` and `JVM unit tests` passed repeatedly on the same commits; direct `gh api` calls to unrelated endpoints succeeded throughout; the failure mode and error text were identical to a documented GitHub-side `HttpError: No server is currently available` regardless of which run or job was retried. Presented the option to admin-merge past the required check to the user; they explicitly chose to keep waiting rather than bypass a security gate. Resolved when GitHub's API recovered.

2. **PR #198 merged missing the `RelaisChatActivity.kt` edit** (genuine process failure, caught and fixed). The command `git add -A -- path1 path2 path3` was run with one pathspec (`SteadinessMonitor.kt`) that had already been staged via a prior `git rm`, causing git to exit with a fatal "pathspec did not match any files" error. Git's `add` with explicit `--` pathspecs is all-or-nothing: when any pathspec fails to resolve, **nothing** from that invocation is staged — including the two other valid pathspecs in the same command. The subsequent `git status --short` output showed `" M RelaisChatActivity.kt"` (a leading space, meaning unstaged-in-worktree), which was misread as a staged modification. The commit therefore only included the 5 `customtasks/` file deletions; PR #198 merged in that state. The uncommitted `RelaisChatActivity.kt` edit was later silently discarded by a routine `git reset --hard origin/main` sync (expected `reset --hard` behavior — it wasn't a bug in the reset, the edit was simply never committed).
   - **Caught**: during Task 6's final-sweep verification, when a fresh read of the merged `RelaisChatActivity.kt` showed the dead class still present, contradicting the PR's own commit message and description.
   - **Fixed**: redid both edits from scratch (re-verified zero manifest/`Intent` references first), this time verifying with `git diff --cached --stat` before committing rather than `git status --short` alone. Landed as follow-up PR #199, with the root cause documented in its commit message and PR description for future reference.
   - **Process lesson applied going forward**: never chain multiple pathspecs in one `git add -- ...` call when any of them might already be staged by a prior `git rm`/`git add`; prefer `git add -A` (no explicit pathspec list) or one pathspec per invocation. Always confirm staged content with `git diff --cached --stat` before committing non-trivial changes, not just `git status --short`'s column codes.

3. **Final sweep found 3 substring "dangling reference" false positives**, all investigated and confirmed benign rather than assumed safe:
   - `GalleryNavGraph` in `MainActivity.kt` — a comment only (fixed in #200 for accuracy, not because it was broken).
   - `ModelInitializationStatus` in 3 files — a coincidental name collision between the deleted `ui/common/chat/ModelInitializationStatus.kt` and a distinct, live, same-named class defined in `ui/modelmanager/ModelManagerViewModel.kt`. Different package, unrelated type — not a real dangling reference.
   - `ExampleCustomTask` in `customtasks/common/CustomTask.kt` — a KDoc `@see`-style doc-comment pointer, not a compiled reference (fixed in #200, repointed to a live implementation).

4. **Two comment-only fixes beyond the plan's original scope** (`MainActivity.kt`, `CustomTask.kt`) — not predicted by the plan, but a natural and low-risk extension of the final-sweep verification step once the 3 substring hits above were traced to their root cause. Bundled into a single PR (#200) separate from the code-deletion PRs, since the plan's own philosophy (small, single-purpose increments) argued against folding a comment fix into a deletion commit.

## Issues Encountered
See Deviations above (both items were resolved within this implementation; neither is an open issue).

## Tests Written
No new automated tests were added — this plan is pure dead-code removal with the plan's own Testing Strategy correctly identifying the JVM gate + `assembleFullOpenDebug` (Hilt graph) as the regression net, since removing unreachable code has no new behavior to unit-test. Existing tests (`LicensesActivityProbe`, `SkillUrlPolicyTest`, `SkillUrlPinTest`, `SkillHttpTest`) were confirmed to still target only live code and were not touched.

| Test File | Tests | Coverage |
|---|---|---|
| `LicensesActivityProbe.kt` (existing, on-device) | 2 | Confirms the manifest-live, now-in-app-unreachable `LicensesActivity` still launches correctly post-cleanup |

## Next Steps
- [ ] `docs/CODEMAPS/architecture.md` + `frontend.md` (refreshed and corrected this session, PR #193) already reflect the end state this plan produced — no further doc update needed unless file counts drift again.
- [ ] The unresolved product question flagged in the plan's NOT-Building section — whether to formally cut `customtasks/agentchat/` (skill manager + MCP client, currently Hilt-instantiated but with no live UI path since the task-carousel `HomeScreen` is gone) — remains open. Worth its own PRP if pursued; explicitly NOT part of this implementation.
- [ ] Resource cleanup (`res/` drawables/strings only used by the 31 deleted files) was explicitly deferred to a separate pass per the plan — the final sweep did not attempt it.
