# PR2 — #13 Harden Gson deserialization against null fields

> Branch: `fix/relais-gson-null-hardening`. GitHub issue: **#13** (read it — it has the full
> instance list + a proposed `isNodeRunnable` diff). Depends on **PR1** (uses `src/test`).

## Summary
Gson fills fields by reflection and **does not honor Kotlin non-null types** — a declared
non-null `String`/`List` can be `null` at runtime, and a non-null access (`.contains()`,
`.endsWith()`, `.replace()`) throws a Kotlin-intrinsic NPE. PR #12 fixed this for
`RelaisModelRef.fromJson`; the **same bug class survives in sibling code**. Make the
Gson-parsed boundary null-tolerant so a malformed/partial allowlist entry **never NPEs** — it
either drops from a list or surfaces as the existing handled `error(...)`.

## Instances (fix all three; ordered by blast radius)

### OQ1 — boot/init-thread NPE via the allowlist-match path (HIGHEST)
`RelaisModelProvisioner.resolveModel` (≈`RelaisModelProvisioner.kt:104-106`) does, when no ref is
set: `allowlist.models.firstOrNull { it.modelId == modelId } … allowed.toModel().also { it.preProcess() }`
on the **`relais-init` thread**. `AllowedModel.toModel()` (`data/ModelAllowlist.kt:75+`)
dereferences non-null fields — `taskTypes.contains(...)` (line ~100), `description.replace(...)`
(~112-116), `defaultConfig.accelerators` (~113). If a matched entry has any of `taskTypes`,
`description`, or `defaultConfig` null/absent, the NPE **escapes on the boot thread → watchdog
restart loop**. `allowlistUrl()` is keyed off `BuildConfig.VERSION_NAME`, so a version with no
published allowlist or a malformed/partial entry is a realistic null path.
**Fix:** wrap the allowlist-match `toModel()` so a null-field failure surfaces as the existing
`error(...)` (already caught upstream → "Init failed: …" notification) instead of a raw NPE. E.g.
in `resolveModel`:
```kotlin
val allowed = allowlist.models.firstOrNull { it.modelId == modelId }
  ?: error("Model id '$modelId' not found in allowlist $url")
return runCatching { allowed.toModel().also { it.preProcess() } }
  .getOrElse { e -> error("Allowlist entry for '$modelId' is malformed ($url): ${e.message}") }
```
(Or make the offending `AllowedModel` fields nullable + guard in `toModel()` — but that ripples
widely; the wrap is the minimal, lowest-risk fix for the boot path.)

### M1 — `RelaisModelCatalog.isNodeRunnable` NPEs the selector sheet (UI path)
`RelaisModelCatalog.kt:69-80`: `m.taskTypes.contains(BuiltInTaskId.LLM_CHAT)` and
`m.modelFile.endsWith(".litertlm")` throw if Gson left those null. Reachable via the model picker
(recoverable crash, lower blast radius than OQ1, same class).
**Fix (from the issue):** make the dereferences null-safe:
```kotlin
private fun isNodeRunnable(m: AllowedModel): Boolean {
  if (m.disabled == true) return false
  if (m.taskTypes?.contains(BuiltInTaskId.LLM_CHAT) != true) return false
  if (m.socToModelFiles?.isNotEmpty() == true) return false
  return m.runtimeType == RuntimeType.LITERT_LM ||
    (m.runtimeType == null && m.modelFile?.endsWith(".litertlm") == true)
}
```
This requires `AllowedModel.taskTypes` and `.modelFile` to be **nullable** (`List<String>?`,
`String?`). Declaring them nullable is the honest fix (Gson can produce null) — but check every
other reader of those fields and guard accordingly (notably `toModel()` itself and any
`curatedModelsFrom` mapping). If the nullable ripple is too broad to land safely in one PR, instead
keep the fields non-null and wrap the per-element predicate:
`runCatching { isNodeRunnable(m) }.getOrDefault(false)` in `curatedModelsFrom` so one bad entry
drops rather than aborting the whole list. **Pick the smaller safe change; document which in the PR.**

### M2 — `modelFromRef` / `fromJson` don't validate `displayName`
`RelaisModelProvisioner.modelFromRef` (`:126-138`) builds an `AllowedModel` from a ref with
`name = ref.displayName` for curated refs. `RelaisModelRef.fromJson` validates `modelId`,
`modelFile`, `commitHash` but **NOT `displayName`** — a null `displayName` becomes
`AllowedModel.name`/`Model.name` and can NPE inside `toModel()`/`normalizedName`.
**Fix:** add `!it.displayName.isNullOrBlank()` to `fromJson`'s `takeIf` (it's the unique
`Model.name`), OR `require(ref.displayName.isNotBlank())` at the top of `modelFromRef`. Prefer the
`fromJson` guard (closes it at the boundary).

## TDD plan (pure JVM, no Robolectric — `src/test`)
Mirror `RelaisModelCatalogTest`'s fixture style (Gson-parse a JSON allowlist string).
1. **OQ1 RED:** a fixture allowlist whose matched entry omits `taskTypes` (and/or `description`,
   `defaultConfig`). Assert that calling the boot resolution over it currently throws an NPE
   (before the fix). After fix: it throws `IllegalStateException` with a "malformed" message (the
   handled `error`), **not** a `NullPointerException`. (Test the wrap at the smallest seam you can
   reach without a `Context` — if `resolveModel` needs `Context`, extract the
   `allowed.toModel()`-wrapping into a pure helper `fun safeToModel(AllowedModel): Model` and test
   that.)
2. **M1 RED:** a fixture with an entry missing `taskTypes` and one missing `modelFile`. Assert
   `curatedModelsFrom(allowlist)` currently throws; after fix it **drops** the bad entries and
   returns the good ones. (`curatedModelsFrom` is already pure — proven by `RelaisModelCatalogTest`.)
3. **M2 RED:** `RelaisModelRef.fromJson("""{"modelId":"a/b","modelFile":"m.litertlm",
   "commitHash":"abc","sizeInBytes":1,"source":"huggingface"}""")` (no `displayName`) currently
   decodes to a non-null ref; after fix it returns **null** (blank/absent displayName rejected).
   Add the positive case too (valid displayName → non-null).

GREEN: implement the three fixes; all new tests pass; existing ported suites still pass.

## Acceptance criteria
- New `src/test` tests cover OQ1, M1, M2; each failed before the fix (quote it) and passes after.
- `./gradlew testDebugUnitTest` + `assembleRelease` green; CI green.
- No raw `NullPointerException` can escape the allowlist→`toModel()` boot path or `isNodeRunnable`
  for any null/absent field; malformed entries are dropped or surface as the existing `error(...)`.
- Independent review APPROVE on the final diff.

## Deferred on-device gate (document in PR, do NOT fake)
Re-run `RelaisModelCatalogTest` on a device and exercise the boot path with a crafted/partial
allowlist — confirms no boot-thread NPE in the real `relais-init` flow. Owner runs later.

## Guardrails / PR
Branch `fix/relais-gson-null-hardening`; PR to `main`; review the diff; merge on green CI.
Keep the change minimal and defensive — this is hardening, not a refactor. Don't touch AICore.
