# Unified App Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fold the Relais node dashboard, in-app chat, and model selection into a single unified app under one launcher icon and one Compose `NavHost` hosted by `MainActivity`, with the node dashboard as the home destination and a DESIGN.md-styled bottom nav (DASHBOARD / CHAT / MODELS).

**Architecture:** Evolve `MainActivity` (already `@AndroidEntryPoint`, already owns the `NavHost` + `ModelManagerViewModel` + the `com.ventouxlabs.relais://` deep-link filter + the "Relais" launcher) into the app shell. Its NavHost start-destination flips from the gallery homepage to a new node `dashboard` destination. The node dashboard UI is extracted out of `RelaisControlActivity.onCreate` into a reusable `DashboardScreen` composable; `ChatScreen` and the Relais model selector are wired in as destinations; `BenchmarkScreen` stays reachable (Hilt is available in this host). The duplicated inline amber theme is hoisted into one `RelaisTheme` composable. `RelaisControlActivity`'s second launcher icon is removed; the class is kept as a thin adb/deep-link trampoline.

**Tech Stack:** Kotlin, Jetpack Compose, androidx.navigation.compose (NavHost), Hilt, JUnit (device-free JVM unit tests under `test/java/cc/grepon/relais/`).

## Global Constraints

- **Package root:** all Kotlin lives under `cc.grepon.relais` at `Android/src/app/src/main/java/cc/grepon/relais/`. (Copied verbatim from spec.)
- **Design system (DESIGN.md, dark-only):** background Charcoal `#0B0B0D`, surface Panel `#16171A`, hairline Line `#2A2B30`, accent Amber `#FFB000`, text Paper `#EDEAE3`, muted Muted `#8A8780`, stop StopRed `#FF5247`; monospace (`FontFamily.Monospace`); no Material light-theme leakage. Colors already exist as `internal val`s in `RelaisPalette.kt`.
- **Pure logic is JVM-tested** device-free under `test/java/cc/grepon/relais/`, one `*Test.kt` per subject file (repo convention). Compose UI is **not** unit-tested in this repo (no Robolectric requirement) — UI wiring is verified by build + on-device smoke, not JVM tests.
- **Do NOT run Gradle builds unless explicitly asked** (slow/heavy). Where a step says "verify build," that is a checkpoint for the human operator / a later device session, not an automatic `./gradlew` run. JVM unit-test runs ARE allowed and expected for the pure-logic tasks.
- **CI unit-test job (the gate for pure logic):** `./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`.
- **Flavor matrix:** `AndroidManifest.xml` under `src/main` is shared across the `dist`×`policy` flavor matrix (`fullOpen`/`playsafe`/`degoogled`). Manifest edits must merge cleanly for all three.
- **Immutability / small files:** prefer new focused files over growing large ones (repo target < 800 lines).

---

## File Structure

**New files:**
- `RelaisShellDestinations.kt` — pure Kotlin: the nav-destination model (route constants + bottom-nav item list) and two pure functions: `startDestinationRoute()` and `resolveShellDeepLink(uri)`. No Compose/Android imports beyond `android.net.Uri`. JVM-testable.
- `RelaisTheme.kt` — one `@Composable fun RelaisTheme(content)` hoisting the currently-duplicated `MaterialTheme(darkColorScheme(...))` + `Surface(Charcoal)` block.
- `DashboardScreen.kt` — the node control-panel UI extracted from `RelaisControlActivity.onCreate`, as `@Composable fun DashboardScreen(state, callbacks…)`, plus the panel sub-composables moved with it.
- `ModelsScreen.kt` — full-screen MODELS destination wrapping the existing selector content.
- `RelaisShellViewModel.kt` — hoists the node-status polling loop out of `RelaisControlActivity`.
- `RelaisAppShell.kt` — the `@Composable fun RelaisAppShell(...)`: `Scaffold` + DESIGN.md bottom nav + `NavHost` wiring all destinations.

**New test files:**
- `test/java/cc/grepon/relais/RelaisShellDestinationsTest.kt`

**Modified files:**
- `MainActivity.kt` — `setContent` renders `RelaisAppShell(...)` instead of `GalleryApp(...)`.
- `AndroidManifest.xml` — remove `RelaisControlActivity`'s LAUNCHER intent-filter; set its `exported=false`; drop the `RelaisConfigureActivity` / `RelaisChatActivity` activity blocks (now destinations, no longer launched by Intent) — but keep `RelaisChatActivity`/`RelaisConfigureActivity` classes on disk until their composables are wired in (retire the manifest entries in the task that removes the last Intent launch).
- `RelaisControlActivity.kt` — strip the inline UI (moved to `DashboardScreen`); keep `handleCmd`/`onNewIntent` as an adb/deep-link trampoline that forwards to `MainActivity`.

---

## Task 1: Pure nav-destination model + deep-link resolver (TDD)

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/RelaisShellDestinations.kt`
- Test: `Android/src/app/src/test/java/cc/grepon/relais/RelaisShellDestinationsTest.kt`

**Interfaces:**
- Produces:
  - `object RelaisRoutes { const val DASHBOARD="dashboard"; const val CHAT="chat"; const val MODELS="models"; const val CONFIGURE="configure"; const val BENCHMARK="benchmark" }`
  - `data class BottomNavItem(val route: String, val label: String)`
  - `val RELAIS_BOTTOM_NAV: List<BottomNavItem>` — DASHBOARD, CHAT, MODELS (in that order).
  - `fun startDestinationRoute(): String` — returns `RelaisRoutes.DASHBOARD`.
  - `fun resolveShellDeepLink(uri: android.net.Uri?): String?` — maps a `com.ventouxlabs.relais://` URI to a shell route, else `null`.

- [ ] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/cc/grepon/relais/RelaisShellDestinationsTest.kt`:

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.grepon.relais

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RelaisShellDestinationsTest {
  @Test fun startDestination_isDashboard() {
    assertEquals(RelaisRoutes.DASHBOARD, startDestinationRoute())
  }

  @Test fun bottomNav_hasThreeTopLevelItemsInOrder() {
    assertEquals(
      listOf(RelaisRoutes.DASHBOARD, RelaisRoutes.CHAT, RelaisRoutes.MODELS),
      RELAIS_BOTTOM_NAV.map { it.route },
    )
  }

  @Test fun deepLink_globalModelManager_mapsToModels() {
    val uri = Uri.parse("com.ventouxlabs.relais://global_model_manager")
    assertEquals(RelaisRoutes.MODELS, resolveShellDeepLink(uri))
  }

  @Test fun deepLink_modelHost_mapsToChat() {
    val uri = Uri.parse("com.ventouxlabs.relais://model/llm_chat/gemma")
    assertEquals(RelaisRoutes.CHAT, resolveShellDeepLink(uri))
  }

  @Test fun deepLink_null_returnsNull() {
    assertNull(resolveShellDeepLink(null))
  }

  @Test fun deepLink_unknownScheme_returnsNull() {
    assertNull(resolveShellDeepLink(Uri.parse("https://example.com/x")))
  }
}
```

> Note: `android.net.Uri` is not available in a plain JVM test, so this uses Robolectric (already on the test classpath in this repo — see existing `*Test.kt` that touch Android types). If a subject you're testing needs no Android types, prefer a plain JVM test without the runner.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Android/src && ./gradlew testFullOpenDebugUnitTest --tests 'cc.grepon.relais.RelaisShellDestinationsTest'`
Expected: FAIL — `RelaisRoutes` / `startDestinationRoute` / `resolveShellDeepLink` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `Android/src/app/src/main/java/cc/grepon/relais/RelaisShellDestinations.kt`:

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.grepon.relais

import android.net.Uri

/** Route keys for the unified shell NavHost destinations. */
object RelaisRoutes {
  const val DASHBOARD = "dashboard"
  const val CHAT = "chat"
  const val MODELS = "models"
  const val CONFIGURE = "configure"
  const val BENCHMARK = "benchmark"
}

/** A top-level destination shown in the DESIGN.md bottom nav. */
data class BottomNavItem(val route: String, val label: String)

/** The three top-level destinations, in display order. */
val RELAIS_BOTTOM_NAV: List<BottomNavItem> = listOf(
  BottomNavItem(RelaisRoutes.DASHBOARD, "DASHBOARD"),
  BottomNavItem(RelaisRoutes.CHAT, "CHAT"),
  BottomNavItem(RelaisRoutes.MODELS, "MODELS"),
)

/** The shell always opens on the node dashboard. */
fun startDestinationRoute(): String = RelaisRoutes.DASHBOARD

/**
 * Map a `com.ventouxlabs.relais://` deep link to a shell route.
 *
 * The legacy gallery deep links (see GalleryNavGraph.kt) are remapped:
 *  - `global_model_manager` -> MODELS
 *  - any `model/...` or bare task host (LLM chat entry points) -> CHAT
 * Anything else (or a non-relais scheme, or null) returns null (caller ignores it).
 */
fun resolveShellDeepLink(uri: Uri?): String? {
  if (uri == null) return null
  if (uri.scheme != "com.ventouxlabs.relais") return null
  return when (uri.host) {
    "global_model_manager" -> RelaisRoutes.MODELS
    else -> RelaisRoutes.CHAT
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew testFullOpenDebugUnitTest --tests 'cc.grepon.relais.RelaisShellDestinationsTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/RelaisShellDestinations.kt \
        Android/src/app/src/test/java/cc/grepon/relais/RelaisShellDestinationsTest.kt
git commit -m "feat(relais): pure nav-destination model + deep-link resolver for the unified shell"
```

---

## Task 2: Hoist the amber theme into `RelaisTheme`

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/RelaisTheme.kt`

**Interfaces:**
- Consumes: `RelaisPalette.kt` vals (`Amber`, `Charcoal`, `Panel`, `Line`, `Paper`, `Muted`, `StopRed`).
- Produces: `@Composable fun RelaisTheme(content: @Composable () -> Unit)` — wraps `content` in the node `MaterialTheme(darkColorScheme(...))` + `Surface(color = Charcoal)`. This is the single source of the amber-on-charcoal wrapper currently duplicated in the three node activities.

- [ ] **Step 1: Create the file**

Create `Android/src/app/src/main/java/cc/grepon/relais/RelaisTheme.kt` (license header as in Task 1, then):

```kotlin
package cc.grepon.relais

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The single amber-on-charcoal (DESIGN.md) theme wrapper for every node surface.
 * Replaces the identical inline block previously duplicated in RelaisControlActivity,
 * RelaisChatActivity, and RelaisConfigureActivity.
 */
@Composable
fun RelaisTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Amber,
      onPrimary = Charcoal,
      background = Charcoal,
      onBackground = Paper,
      surface = Panel,
      onSurface = Paper,
      error = StopRed,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) { content() }
  }
}
```

- [ ] **Step 2: Verify it compiles (no test — Compose)**

This is Compose UI with no pure logic to unit-test. Verification is at build time (Task 8 checkpoint). Confirm imports resolve against the existing activities' usage (they import the same symbols).

- [ ] **Step 3: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/RelaisTheme.kt
git commit -m "refactor(relais): hoist the duplicated amber theme into RelaisTheme composable"
```

---

## Task 3: Extract the node control panel into `DashboardScreen`

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/DashboardScreen.kt`
- Modify: `Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt` (move the inline UI + panel sub-composables out; the class keeps only `handleCmd`/`onNewIntent`)

**Interfaces:**
- Consumes: `RelaisControlPanelState` and `computeControlPanelState(...)` from `RelaisControlPanelState.kt` (unchanged); the palette vals; `RelaisTheme` (Task 2).
- Produces:
  ```kotlin
  @Composable
  fun DashboardScreen(
    state: RelaisControlPanelState,
    lanEndpoint: String,          // "$ip:8443"
    localEndpoint: String,        // "127.0.0.1:8080"
    apiKey: String,
    modelSummary: String,
    onPrimaryAction: () -> Unit,  // START/CANCEL/STOP dispatch
    onOpenConfigure: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onShareConnection: () -> Unit,
  )
  ```
  The panel sub-composables (`Divider`, `ActionLink`, `CopyableRow`, `HeroCopyableRow`, `ModelSummaryRow`, `PrimaryButton`, `AccessKeyChip`) move into this file as `private` composables (verbatim from `RelaisControlActivity.kt:305-540`). The `lanIpv4()` helper (`RelaisControlActivity.kt:541`) moves too (as a top-level or file-private fun).

- [ ] **Step 1: Create `DashboardScreen.kt`**

Move the inline UI from `RelaisControlActivity.onCreate` (the `Surface` lambda body, lines 143-297) into a new `@Composable fun DashboardScreen(...)` with the signature above. Replace the direct `ctx.startActivity(Intent(...))` calls with the injected callbacks:
- `OPEN CHAT ›` link's onClick — remove entirely (chat is now a bottom-nav destination, not launched from the dashboard). Delete that `ActionLink`.
- `CONFIGURE ›` link + MODEL row tap — call `onOpenConfigure()` / `onOpenModelSheet()`.
- The primary button — call `onPrimaryAction()`.
- SHARE CONNECTION — call `onShareConnection()`.

Move the `private` panel composables (Divider/ActionLink/CopyableRow/HeroCopyableRow/ModelSummaryRow/PrimaryButton/AccessKeyChip) and `lanIpv4()` into `DashboardScreen.kt`. Keep them `private`.

- [ ] **Step 2: Slim `RelaisControlActivity.kt`**

Remove the moved UI + sub-composables from `RelaisControlActivity.kt`. The class keeps: the class declaration, `handleCmd(intent)` (104-118), `onCreate` (now only `handleCmd(intent)` + a redirect to `MainActivity` — see Task 7), and `onNewIntent`. Remove the `LaunchedEffect` polling and state vars (they move to the ViewModel in Task 4). Leave `onCreate` calling `handleCmd` then `startActivity(Intent(this, MainActivity::class.java)); finish()` as a placeholder redirect — Task 7 finalizes this.

- [ ] **Step 3: Verify compile (Compose — build checkpoint)**

No JVM test (UI). Confirm `DashboardScreen` references only injected params + moved sub-composables. Build verification deferred to Task 8.

- [ ] **Step 4: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/DashboardScreen.kt \
        Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt
git commit -m "refactor(relais): extract DashboardScreen from RelaisControlActivity"
```

---

## Task 4: `RelaisShellViewModel` — hoist node polling

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/RelaisShellViewModel.kt`

**Interfaces:**
- Consumes: `RelaisEngine`, `RelaisConfig`, `ThermalGovernor`, `RelaisNodeProgress` singletons; `computeControlPanelState(...)`.
- Produces:
  ```kotlin
  class RelaisShellViewModel(app: Application) : AndroidViewModel(app) {
    val panelState: StateFlow<RelaisControlPanelState>
    val modelSummary: StateFlow<String>
    val apiKey: StateFlow<String>
    fun onPrimaryAction()   // dispatches START/CANCEL/STOP via RelaisNodeService
  }
  ```
  A polling coroutine in `init` mirrors the old `LaunchedEffect` (poll every 1000ms, rebuild via `computeControlPanelState`).

- [ ] **Step 1: Create the ViewModel**

Port the polling loop from the old `RelaisControlActivity.LaunchedEffect` (155-182) into a `viewModelScope.launch { while (isActive) { … ; delay(1000) } }` that updates `MutableStateFlow`s. `onPrimaryAction()` reads the current `panelState.value.primaryAction` and calls `RelaisNodeService.start(app)` / `RelaisNodeService.stop(app)` accordingly (matching the old button dispatch).

- [ ] **Step 2: Verify compile (no JVM test — touches Android singletons/Context)**

The polling logic depends on `Application`/singletons, so it is not a pure JVM unit — no `*Test.kt`. (The pure derivation it calls, `computeControlPanelState`, is already tested.) Build verification in Task 8.

- [ ] **Step 3: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/RelaisShellViewModel.kt
git commit -m "feat(relais): RelaisShellViewModel hoists node-status polling for all shell destinations"
```

---

## Task 5: `ModelsScreen` — full-screen MODELS destination

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/ModelsScreen.kt`

**Interfaces:**
- Consumes: `RelaisModelSelectorSheet(currentModelId, hfToken, onPickRef, onPickManualId, onDismiss)` (`RelaisModelSelector.kt:78-86`); `RelaisConfig`; `RelaisModelRef` (`cc.grepon.relais.data.RelaisModelRef`).
- Produces: `@Composable fun ModelsScreen()` — a full-screen destination that shows the current model as a header row and hosts the selector. Reuse the exact `onPickRef`/`onPickManualId` bodies from `RelaisConfigureActivity.kt:278-305` (persist via `RelaisConfig.setModelRef` / `setModelId`). Because the selector is a `ModalBottomSheet`, `ModelsScreen` shows a header + a "CHANGE MODEL" `ActionLink` that opens the sheet (state `showSheet`), mirroring Configure's `showModelSheet` pattern.

- [ ] **Step 1: Create `ModelsScreen.kt`** with the destination composable per the interface above (header row with current `RelaisConfig.modelId(ctx)`, a CHANGE MODEL link toggling `showSheet`, and the `RelaisModelSelectorSheet(...)` block copied from Configure).

- [ ] **Step 2: Verify compile (Compose — build checkpoint).**

- [ ] **Step 3: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/ModelsScreen.kt
git commit -m "feat(relais): ModelsScreen destination wrapping the Relais model selector"
```

---

## Task 6: `RelaisAppShell` — Scaffold + bottom nav + NavHost

**Files:**
- Create: `Android/src/app/src/main/java/cc/grepon/relais/RelaisAppShell.kt`

**Interfaces:**
- Consumes: `RelaisTheme` (T2), `DashboardScreen` (T3), `RelaisShellViewModel` (T4), `ModelsScreen` (T5), `ChatScreen` (existing, param-less — must be made non-`private` / callable; see note), `RelaisModelSelectorSheet`, `BenchmarkScreen` (existing, needs `ModelManagerViewModel`), `RelaisRoutes` / `RELAIS_BOTTOM_NAV` / `startDestinationRoute` / `resolveShellDeepLink` (T1), `computeControlPanelState`.
- Produces:
  ```kotlin
  @Composable
  fun RelaisAppShell(
    modelManagerViewModel: ModelManagerViewModel,  // passed from MainActivity for BenchmarkScreen
    deepLinkUri: android.net.Uri? = null,
  )
  ```

**Note on `ChatScreen`:** it is currently `@Composable private fun ChatScreen()` in `RelaisChatActivity.kt:145`. Change `private` → internal/public and (optionally) move it into its own `ChatScreen.kt`, so the shell can call it. Its `LocalActivityContext()` helper already uses `LocalContext.current`, which works unchanged inside the NavHost.

- [ ] **Step 1: Create `RelaisAppShell.kt`**

```kotlin
package cc.grepon.relais
// imports: androidx.navigation.compose.NavHost/composable/rememberNavController,
// androidx.compose.material3 Scaffold/NavigationBar/NavigationBarItem/Text, palette vals, etc.

@Composable
fun RelaisAppShell(
  modelManagerViewModel: ModelManagerViewModel,
  deepLinkUri: android.net.Uri? = null,
) {
  RelaisTheme {
    val navController = rememberNavController()
    // One-shot: honor an incoming deep link.
    LaunchedEffect(deepLinkUri) {
      resolveShellDeepLink(deepLinkUri)?.let { navController.navigate(it) }
    }
    val vm: RelaisShellViewModel = viewModel()
    Scaffold(
      containerColor = Charcoal,
      bottomBar = { RelaisBottomBar(navController) },
    ) { padding ->
      NavHost(
        navController = navController,
        startDestination = startDestinationRoute(),
        modifier = Modifier.padding(padding),
      ) {
        composable(RelaisRoutes.DASHBOARD) {
          val state by vm.panelState.collectAsState()
          val modelSummary by vm.modelSummary.collectAsState()
          val apiKey by vm.apiKey.collectAsState()
          DashboardScreen(
            state = state,
            lanEndpoint = /* from vm or lanIpv4() */,
            localEndpoint = "127.0.0.1:8080",
            apiKey = apiKey,
            modelSummary = modelSummary,
            onPrimaryAction = { vm.onPrimaryAction() },
            onOpenConfigure = { navController.navigate(RelaisRoutes.CONFIGURE) },
            onOpenModelSheet = { navController.navigate(RelaisRoutes.MODELS) },
            onShareConnection = { /* system share chooser */ },
          )
        }
        composable(RelaisRoutes.CHAT) { ChatScreen() }
        composable(RelaisRoutes.MODELS) { ModelsScreen() }
        composable(RelaisRoutes.CONFIGURE) { ConfigureScreenHost(navController) }
        composable("${RelaisRoutes.BENCHMARK}/{modelName}") { entry ->
          val modelName = entry.arguments?.getString("modelName") ?: ""
          modelManagerViewModel.getModelByName(modelName)?.let { model ->
            BenchmarkScreen(
              initialModel = model,
              modelManagerViewModel = modelManagerViewModel,
              onBackClicked = { navController.navigateUp() },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RelaisBottomBar(navController: NavHostController) {
  val backStack by navController.currentBackStackEntryAsState()
  val current = backStack?.destination?.route
  NavigationBar(containerColor = Panel) {
    RELAIS_BOTTOM_NAV.forEach { item ->
      NavigationBarItem(
        selected = current == item.route,
        onClick = {
          navController.navigate(item.route) {
            launchSingleTop = true
            popUpTo(startDestinationRoute()) { saveState = true }
            restoreState = true
          }
        },
        icon = {},
        label = {
          Text(
            item.label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = if (current == item.route) Amber else Muted,
          )
        },
      )
    }
  }
}
```

> Implementer notes: (a) supply `lanEndpoint` by moving `lanIpv4()` into a shared helper the VM or shell can call (it needs no Compose). (b) `ConfigureScreenHost` wraps the existing `ConfigureScreen` content minus its `activity.finish()` back row — replace that back row's `activity.finish()` with `navController.navigateUp()`. (c) Style `NavigationBar` to DESIGN.md: `containerColor = Panel`, a top hairline `Line` divider (draw a 1dp `Divider` above it), no elevation, `NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent)`.

- [ ] **Step 2: Verify compile (Compose — build checkpoint).**

- [ ] **Step 3: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/RelaisAppShell.kt \
        Android/src/app/src/main/java/cc/grepon/relais/RelaisChatActivity.kt
git commit -m "feat(relais): RelaisAppShell — NavHost + DESIGN.md bottom nav wiring all destinations"
```

---

## Task 7: Point `MainActivity` at the shell + retire the second launcher

**Files:**
- Modify: `Android/src/app/src/main/java/cc/grepon/relais/MainActivity.kt`
- Modify: `Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt`
- Modify: `Android/src/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `RelaisAppShell(modelManagerViewModel, deepLinkUri)` (T6).

- [ ] **Step 1: `MainActivity.setContent`** — replace the `GalleryTheme { Surface { GalleryApp(modelManagerViewModel = modelManagerViewModel) } }` block (MainActivity.kt:97-118) with:

```kotlin
setContent {
  RelaisAppShell(
    modelManagerViewModel = modelManagerViewModel,
    deepLinkUri = intent?.data,
  )
}
```

Keep `@AndroidEntryPoint`, the `by viewModels()` model manager VM, the splash install, and `modelManagerViewModel.loadModelAllowlist()`.

- [ ] **Step 2: `RelaisControlActivity` → trampoline** — reduce `onCreate` to:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  handleCmd(intent)  // preserve the adb `--es cmd start --es token` ABI
  startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
  finish()
}
```

Keep `handleCmd`/`onNewIntent`. This preserves the documented `adb am start .RelaisControlActivity --es cmd start --es token <key>` control ABI while removing its UI.

- [ ] **Step 3: Manifest** — in `AndroidManifest.xml`:
  - Delete the `<intent-filter>` with MAIN/LAUNCHER from the `.RelaisControlActivity` block and its `android:label="Relais Node"`; set `android:exported="true"` remains (adb-invoked) but drop `excludeFromRecents` is optional. Result: `RelaisControlActivity` has no launcher, only the adb entry.
  - Delete the `.RelaisConfigureActivity` and `.RelaisChatActivity` `<activity>` blocks entirely (they are now NavHost destinations, never `startActivity`-launched). Their Kotlin classes stay on disk but are no longer Activities in the manifest — **if any class still `extends ComponentActivity` and is unreferenced, that's fine; leave the file, it's dead but harmless.** (A later cleanup can delete them.)
  - Leave `MainActivity`'s LAUNCHER + `com.ventouxlabs.relais` VIEW filter intact — it is now the single launcher.
  - Confirm `android:label` on the app / MainActivity reads "Relais" (DESIGN.md §Application).

- [ ] **Step 4: Verify — launcher count**

Manual/greppable check (no build): confirm exactly one `<category android:name="android.intent.category.LAUNCHER" />` remains in the manifest.

Run: `grep -c 'android.intent.category.LAUNCHER' Android/src/app/src/main/AndroidManifest.xml`
Expected: `1`

- [ ] **Step 5: Commit**

```bash
git add Android/src/app/src/main/java/cc/grepon/relais/MainActivity.kt \
        Android/src/app/src/main/java/cc/grepon/relais/RelaisControlActivity.kt \
        Android/src/app/src/main/AndroidManifest.xml
git commit -m "feat(relais): MainActivity hosts the unified shell; retire the second launcher icon"
```

---

## Task 8: DESIGN.md decisions-log entry + build/flavor verification

**Files:**
- Modify: `DESIGN.md`

- [ ] **Step 1: Add the decisions-log row** to `DESIGN.md` (§Decisions Log table):

```markdown
| 2026-07-11 | Unified app shell: single launcher, Compose NavHost hosted by MainActivity, node dashboard as home; new DESIGN.md-conformant bottom nav (DASHBOARD/CHAT/MODELS); Configure + Benchmark as sub-screens | docs/superpowers/specs/2026-07-11-unified-app-shell-design.md |
```

Also update §Application: the control screen is now the `dashboard` destination inside the shell (was `RelaisControlActivity`).

- [ ] **Step 2: Run the JVM unit-test gate** (the one build command that IS allowed — pure logic only):

Run: `cd Android/src && ./gradlew testFullOpenDebugUnitTest testFullPlaysafeDebugUnitTest testDegoogledOpenDebugUnitTest`
Expected: PASS (includes the new `RelaisShellDestinationsTest`).

- [ ] **Step 3: Hand off device/build verification** (NOT run here — requires hardware + an explicit build request):

Document in the PR description the on-device smoke checklist:
  1. One launcher icon; opens to the node dashboard.
  2. Bottom nav switches DASHBOARD / CHAT / MODELS without leaving the app.
  3. Start/stop, endpoints, access key, model summary, primary action all work on DASHBOARD.
  4. Chat works from CHAT; model selection works from MODELS; Benchmark reachable + renders.
  5. `adb am start -n <pkg>/cc.grepon.relais.RelaisControlActivity --es cmd start --es token <key>` still starts the node (trampoline ABI intact).
  6. A `com.ventouxlabs.relais://global_model_manager` deep link lands on MODELS; a `…://model/...` link lands on CHAT.
  7. No Material light-theme leakage on any absorbed screen (Benchmark).
  8. Verify manifest merges for `fullOpen` / `playsafe` / `degoogled` (assemble each if the operator chooses).

- [ ] **Step 4: Commit**

```bash
git add DESIGN.md
git commit -m "docs(relais): record the unified-app-shell decision in DESIGN.md"
```

---

## Self-Review (completed by plan author)

- **Spec coverage:** §3 nav model → T1/T6/T7; §4 absorb/retire → T3 (dashboard), T5 (models), T6 (benchmark wiring), T7 (retire launcher + manifest); §5 shared state → T4; §6 re-theming → T2 + T6 bottom-bar styling + T8 Benchmark leakage check; §7 DESIGN.md deviation → T8; §8 testing → T1 (JVM) + T8 (gate + device checklist); §9 risks (Hilt, deep links, theme, flavors) → host=MainActivity (T7) resolves Hilt, T1 resolves deep links, T2/T8 resolve theme, T8 resolves flavors; §11 success criteria → T8 device checklist. No gaps.
- **Placeholder scan:** the only non-literal code fragments are in T6 (`/* from vm or lanIpv4() */`, `/* system share chooser */`) — these are explicitly annotated implementer notes with the exact source (`lanIpv4()` at the old `RelaisControlActivity.kt:541`; the share chooser is the existing SHARE CONNECTION intent). Acceptable because the concrete source is named; a subagent executing T6 should inline them from the named source.
- **Type consistency:** `RelaisRoutes.*`, `RELAIS_BOTTOM_NAV`, `startDestinationRoute()`, `resolveShellDeepLink(Uri?)`, `RelaisTheme(content)`, `DashboardScreen(...)` signature, `RelaisShellViewModel` API, `ModelsScreen()`, `RelaisAppShell(modelManagerViewModel, deepLinkUri)` are used consistently across tasks. `RelaisModelSelectorSheet` and `BenchmarkScreen` signatures match the extracted source.
