# Relais — Overnight Autonomous Loop Runbook

> **For the overnight Fable loop.** Read this first. It defines the queue, the verification
> reality, the merge protocol, and what is explicitly OUT of scope. Each PR has a self-contained
> PRP in this directory (`pr1-…` → `pr5-…`); execute them **in order** (PR1 is load-bearing).
>
> Repo: `bearyjd/relais` · base branch: `main` (unprotected, you may merge) · today: 2026-06-12.
> `main` is green and clean. All the hard work (rename/de-Google, Gate-3, model selector A+B,
> G5 crash fix) is already merged.

---

## The verification reality (READ — it shapes everything)

1. **CI runs NO tests today.** `.github/workflows/build_android.yaml` runs only
   `./gradlew assembleRelease`. The other two checks are `license-lint` and `secret-scan`.
   So "green CI" currently means *compiles + no secrets* — it does not verify logic.
2. **There is NO JVM unit-test source set.** All 9 existing tests live in `src/app/src/androidTest`
   (instrumented) and need a device/emulator. There is no `./gradlew testDebugUnitTest`.
3. **No device is attached overnight.** You cannot run instrumented (`androidTest`) tests.

**Therefore PR1 builds a headless JVM unit-test harness and wires it into CI. After PR1, every
later PR is TDD'd with `./gradlew testDebugUnitTest` (no device) and CI actually verifies the
logic.** Design each fix around a **pure decision function** so it is unit-testable without
Android `Context` (this is why PR2/PR3/PR4 need no Robolectric).

> **Hard lesson this project already paid for:** the #12 Gson-null NPE passed compile + THREE
> review passes and was caught only by a real run. Compile-green ≠ tested. For these bugs,
> *running the test* is what catches them — which is the whole point of PR1.

---

## Queue (execute in order — one PR each, branch off `main`)

| # | PRP file | Issue | Robolectric? | On-device gate after? |
|---|---|---|---|---|
| **PR1** | `pr1-jvm-unit-test-harness.plan.md` | — | adds it (for PR5) | no |
| **PR2** | `pr2-issue-13-gson-null-hardening.plan.md` | #13 | no (pure Gson) | yes — re-run catalog/boot path on device |
| **PR3** | `pr3-issue-11-stale-model-path.plan.md` | #11 | no (pure decision fn) | yes — adb `--es modelId` repro |
| **PR4** | `pr4-pixel10-default-model.plan.md` | (deferred Part 3) | no (pure decision fn) | yes — fresh Pixel 10 default |
| **PR5** | `pr5-test-coverage-backfill.plan.md` | — | yes | no |

PR2 and PR3 are fully specified in the GitHub issues (root cause + proposed diff + test plan) —
they are the highest-confidence work; do them first after the harness. PR4's *logic* is
overnight-shippable but its model choice + on-device serve are deferred gates (see its PRP).

---

## Per-PR workflow (TDD — mandatory)

1. `git switch main && git pull && git switch -c <branch>` (branch name in each PRP).
2. **RED:** write the unit test(s) first in `src/test`. Run `./gradlew testDebugUnitTest` and
   **confirm they fail for the right reason** (the NPE / wrong persisted value / wrong default).
   Quote the failure in the commit/PR body.
3. **GREEN:** implement the **minimal** change. Re-run `./gradlew testDebugUnitTest` → all pass.
4. **Verify build:** `./gradlew assembleDebug -x lint` (and `assembleRelease` if the change is
   release-relevant) — must compile.
5. **Independent review pass on the FINAL diff** (project hard rule — CI-green ≠ reviewed):
   run the `code-reviewer` agent (or `/code-review`) over the branch diff vs `main`. Address
   CRITICAL/HIGH before merge. Re-review the delta after responding to review.
6. **Open PR** (`gh pr create --base main`), wait for CI green (build + the NEW unit-test job +
   scans), then **merge** (`gh pr merge <n> --repo bearyjd/relais --merge --delete-branch`).
7. Move to the next PRP.

---

## Guardrails (do NOT violate)

- **Never commit to `main` directly.** Branch per PR; merge via PR only.
- **Test-first.** No production change lands without a unit test that failed before it and passes
  after. Tests go in `src/test` (JVM) so they run headless and in CI.
- **Pure decision functions.** Extract the logic-under-test as a pure function (no `Context`) so
  it's unit-testable without Robolectric. The Android-side call site just invokes it.
- **Do NOT touch the AICore / NPU path.** It was investigated 2026-06-12 and is blocked at the
  platform layer (the `com.google.android.aicore` service isn't on the Pixel 10 build;
  `genai-prompt` is already the latest `1.0.0-beta2`). No code change unblocks it. Leave
  `RelaisAicore`, `BackendSelector`, and the `g4b_npuAicorePathOrSkip` test alone.
- **Respect `DESIGN.md`** for any UI-visible change (PR4 may surface a default-model label). Amber
  signal-relay on near-black, monospace. Do not deviate.
- **Honesty:** never claim on-device verification you did not perform. Where a PRP lists a
  "deferred on-device gate," say so in the PR body and leave it for the human to run later.
- **Secrets:** never log tokens/keys; `secret-scan` (gitleaks + trufflehog) gates CI.
- **One-time online build:** PR1 adds test deps (Robolectric et al.) — the first
  `testDebugUnitTest` needs network to resolve them, then the cache is warm. Don't pass `--offline`
  on that first resolve.

---

## Definition of done (the whole loop)

PR1–PR4 merged to `main` with: green CI (incl. the new unit-test job), an independent review
APPROVE on each final diff, and the deferred on-device gates documented (not faked). PR5 merged if
time remains. A short summary appended to `.claude/HANDOFF.md` listing what merged, the new
`testDebugUnitTest` lever, and the on-device gates still owed (PR2 catalog/boot re-run, PR3 adb
repro, PR4 fresh-Pixel-10 serve).

**Toolchain (verified):** AGP 8.8.2 · Kotlin 2.2.0 · Gradle 8.10.2 · JVM target 11 · compileSdk 35
· minSdk 31 · JUnit 4.13.2 (already a `testImplementation`) · Gson 2.12.1. Work dir for Gradle:
`Android/src`.
