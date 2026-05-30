# Plan: Rebrand the Gallery UI with Relais colors

## Summary
Recolor the stock Gallery model-browser/chat/home/settings UI to the Relais palette (amber `#FFB000`
on charcoal `#0B0B0D`) so the whole app matches the already-branded launcher icon and node control
screen. The app uses a fixed (NON-dynamic) Material 3 color scheme, so this is mostly value swaps in
the theme package plus killing the leftover Google-blue accents in `CustomColors`.

## User Story
As an operator opening the Relais app, I want the model browser and chat to use the same amber-on-black
identity as the node control screen, so the app feels like one product instead of a re-skinned Google demo.

## Problem → Solution
Gallery UI is Google-blue (`primary #0B57D0`/`#A8C7FA`) on white/`#131314`, with blue gradients, blue
tab headers, blue links, and 4-color task swatches. → Amber `#FFB000` accent on a charcoal surface ramp,
blue chrome neutralized to amber, matching `DESIGN.md`.

## Metadata
- **Complexity**: Medium
- **Source PRD**: N/A (free-form, follows DESIGN.md)
- **Estimated Files**: ~4 (Color.kt, Theme.kt, values/themes.xml, values-night/themes.xml) + 1 optional default-theme line

---

## UX Design
### Before
Blue Material app: blue buttons/FAB, blue app-title gradient, blue tab header, blue links, white or `#131314` surfaces.
### After
Amber-accented app on charcoal: amber buttons/FAB with dark text, amber title/links, charcoal surface ramp. Dark by default (Relais is dark-only per DESIGN.md); light scheme kept but amber-accented for users who pick Light.

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| Primary actions (buttons/FAB) | blue fill, white text | amber `#FFB000` fill, charcoal text | mirrors START button on control screen |
| App title / tab header | blue gradient `#3174F1` | amber | `CustomColors` |
| Links | blue `#9DCAFC`/`#32628D` | amber | `CustomColors.linkColor` |
| Default theme | Auto (often light) | Dark | optional; set THEME default to DARK |

---

## Mandatory Reading
| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `ui/theme/Color.kt` | 21-91 | The 70 scheme constants to swap (`*Light` 21-55, `*Dark` 57-91) |
| P0 | `ui/theme/Theme.kt` | 39-115, 147-296, 319-349 | scheme assembly, `CustomColors` blue accents, `GalleryTheme` (NO dynamic color) |
| P1 | `res/values/themes.xml` | 19-25 | light splash bg `#FFFFFF` → charcoal |
| P1 | `res/values-night/themes.xml` | 19-24 | dark splash bg `#131314` → charcoal |
| P1 | `relais/RelaisControlActivity.kt` | 40-47 | canonical Relais palette constants to match (`Amber`, `Charcoal`, `Panel`, `Line`, `Paper`, `Muted`, `StopRed`) |
| P2 | `DESIGN.md` | all | brand source of truth |
| P2 | `ui/home/SettingsDialog.kt` | 88, 163-177, 353-355 | the Auto/Light/Dark toggle (stays functional) |
| P2 | `GalleryApplication.kt` | 40 | theme loaded from DataStore at startup |

## External Documentation
No external research needed — fixed Material 3 `ColorScheme`, established internal pattern.

KEY_INSIGHT: `GalleryTheme` (Theme.kt:319-349) uses fixed `lightScheme`/`darkScheme`, NOT
`dynamicColorScheme`. APPLIES_TO: whole recolor. GOTCHA: none from dynamic color — the palette fully
controls the app. The real gotcha is `CustomColors` (Theme.kt:147-296): blue chrome lives there, not in
the Material scheme, so it must be edited separately or blue leaks through.

---

## Patterns to Mirror

### RELAIS_PALETTE (the target values)
// SOURCE: relais/RelaisControlActivity.kt:40-47
```kotlin
Amber=#FFB000  Charcoal=#0B0B0D  Panel=#16171A  Line=#2A2B30  Paper=#EDEAE3  Muted=#8A8780  StopRed=#FF5247
```

### SCHEME_CONSTANTS (how the scheme is defined — swap values only, keep names)
// SOURCE: ui/theme/Color.kt:57-91 (dark)
```kotlin
val primaryDark = Color(0xFFA8C7FA)   // → Color(0xFFFFB000)
val onPrimaryDark = Color(0xFF062E6F) // → Color(0xFF0B0B0D)
val backgroundDark = Color(0xFF131314) // → Color(0xFF0B0B0D)
val surfaceDark = Color(0xFF131314)    // → Color(0xFF0B0B0D)
```

### CUSTOM_COLORS (blue chrome — must be retinted)
// SOURCE: ui/theme/Theme.kt:227-228, 264, 267
```kotlin
appTitleGradientColors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1)) // → amber pair
tabHeaderBgColor = Color(0xFF3174F1)   // → Color(0xFFFFB000)
homeBottomGradient = listOf(Color(0x00F8F9FF), Color(0x1AF6AD01)) // already amber-ish, keep
linkColor = Color(0xFF9DCAFC)          // → amber
```

---

## Files to Change
| File | Action | Justification |
|---|---|---|
| `ui/theme/Color.kt` | UPDATE | Swap `*Dark` (and `*Light`) scheme constants to amber/charcoal family |
| `ui/theme/Theme.kt` | UPDATE | Retint `lightCustomColors`/`darkCustomColors` blue chrome → amber/neutral |
| `res/values-night/themes.xml` | UPDATE | Splash bg `#131314` → `#0B0B0D` |
| `res/values/themes.xml` | UPDATE | Splash bg `#FFFFFF` → `#0B0B0D` (dark splash; app defaults dark) |
| `GalleryApplication.kt` or `DataStoreRepository.kt:148` | UPDATE (optional) | Default theme AUTO → DARK so the app opens branded |

## NOT Building
- Font change. Gallery stays Nunito (`Type.kt`); Relais mono is only the control screen. Scope = colors.
- The detection/category color palette in `ui/common/Utils.kt:143-160` (functional distinct-category colors for bounding boxes), and `ModelNameAndStatus.kt:92` status-yellow — leave; not brand chrome.
- Removing the Light/Dark setting. Keep it working; just recolor both schemes.
- New launcher icon / control screen (already shipped).
- The 4-color task category swatches (`taskBgGradientColors`/`taskIconColors`) — optional retint, default leave (they distinguish task types).

---

## Step-by-Step Tasks

### Task 1: Recolor the DARK scheme constants (canonical)
- **ACTION**: In `Color.kt:57-91`, swap the `*Dark` values to the Relais family.
- **IMPLEMENT** (key ones):
  - `primaryDark=#FFB000`, `onPrimaryDark=#0B0B0D`, `primaryContainerDark=#3A2A00`, `onPrimaryContainerDark=#FFD98A`
  - `secondaryDark=#E0A23A`, `onSecondaryDark=#0B0B0D`, `tertiaryDark=#C9C5BC`, `onTertiaryDark=#0B0B0D` (neutral, one-accent discipline)
  - `backgroundDark=#0B0B0D`, `onBackgroundDark=#EDEAE3`, `surfaceDark=#0B0B0D`, `onSurfaceDark=#EDEAE3`
  - `surfaceVariantDark=#2A2B30`, `onSurfaceVariantDark=#B7B4AC`
  - charcoal ramp: `surfaceContainerLowestDark=#08080A`, `surfaceContainerLowDark=#121316`, `surfaceContainerDark=#16171A`, `surfaceContainerHighDark=#1E2024`, `surfaceContainerHighestDark=#26282D`, `surfaceDimDark=#0B0B0D`, `surfaceBrightDark=#26282D`
  - `outlineDark=#4A4B50`, `outlineVariantDark=#2A2B30`, `inversePrimaryDark=#9A6A00`, `errorDark=#FF5247`, `onErrorDark=#0B0B0D`
- **MIRROR**: RELAIS_PALETTE.
- **GOTCHA**: `onPrimary` MUST be dark (charcoal) — amber is a light color, so light text on amber is unreadable. Matches the control screen (charcoal text on amber START).
- **VALIDATE**: build; on-device screenshot of home/chat shows amber accents on charcoal.

### Task 2: Recolor the LIGHT scheme (kept for users who pick Light)
- **ACTION**: In `Color.kt:21-55`, swap `*Light` accents to a deeper amber readable on white.
- **IMPLEMENT**: `primaryLight=#9A6A00` (deep amber, readable as text/icon on white), `onPrimaryLight=#FFFFFF`, `primaryContainerLight=#FFE08A`, `onPrimaryContainerLight=#2A1D00`, `secondaryLight=#7A5A12`, `tertiaryLight=#5F5B52`. Leave neutrals/backgrounds (white) as-is.
- **MIRROR**: RELAIS_PALETTE (amber hue, darkened for contrast).
- **GOTCHA**: Don't use `#FFB000` as `primaryLight` — fails contrast for text on white. Use the deepened amber.
- **VALIDATE**: switch Settings → Light; buttons/links amber, text legible.

### Task 3: Retint CustomColors blue chrome (both palettes)
- **ACTION**: In `Theme.kt`, replace the Google-blue accents in `lightCustomColors` (147-223) and `darkCustomColors` (225-296).
- **IMPLEMENT**:
  - `appTitleGradientColors = listOf(Color(0xFFFFC24D), Color(0xFFFFB000))`
  - `tabHeaderBgColor = Color(0xFFFFB000)`
  - `linkColor`: dark `#FFB000`, light `#9A6A00`
  - `userBubbleBgColor`: dark `#1E2024`, light `#FFE08A`; `agentBubbleBgColor`: dark `#16171A`, light `#F0EFEA`
  - `bgStarColor`: low-alpha amber e.g. `#19FFB000`
  - `promoBannerBgBrush`/`promoBannerIconBgBrush`: swap blue stops for low-alpha amber, or leave (low visibility)
- **MIRROR**: CUSTOM_COLORS.
- **GOTCHA**: This is the step people forget — the Material scheme alone won't recolor the title gradient, tab header, or links. Skipping it leaves blue chrome on an amber app.
- **VALIDATE**: home screen title + tab header + a chat link are amber, no blue remains.

### Task 4: Charcoal splash background
- **ACTION**: `values-night/themes.xml:21` `#FF131314` → `#FF0B0B0D`; `values/themes.xml:22` `#FFFFFFFF` → `#FF0B0B0D`.
- **GOTCHA**: The splash shows before Compose loads; leaving it white flashes white on launch of a dark app.
- **VALIDATE**: cold-launch shows charcoal splash, no white flash.

### Task 5 (optional): Default to dark
- **ACTION**: `DataStoreRepository.kt:148` default `THEME_AUTO` → `THEME_DARK` (and/or `ThemeSettings.kt:23`), so a fresh install opens in the branded dark theme.
- **GOTCHA**: Respect the user's saved choice; only change the default-when-unset.
- **VALIDATE**: fresh install opens dark; Settings can still switch to Light/Auto.

---

## Testing Strategy
Visual, on-device (this is a recolor). No unit tests needed.

### Edge Cases Checklist
- [ ] Dark theme: amber accents, charcoal surfaces, no Google-blue anywhere (home, chat, model list, settings)
- [ ] Light theme (Settings → Light): amber readable, no contrast failures
- [ ] Cold launch: charcoal splash, no white flash
- [ ] Chat bubbles / links / task cards retinted
- [ ] Status bar icons legible over charcoal (StatusBarColorController already handles)

---

## Validation Commands
### Build
```bash
cd Android/src && ANDROID_HOME=/home/user/Android/Sdk ./gradlew :app:assembleDebug --console=plain
```
EXPECT: BUILD SUCCESSFUL

### On-device visual
```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.google.aiedge.gallery/com.google.ai.edge.gallery.MainActivity
adb shell screencap -p /sdcard/g.png && adb pull /sdcard/g.png /tmp/gallery.png
```
EXPECT: amber-on-charcoal home screen; screenshot for review. Repeat into chat + settings.

### Manual
- [ ] Home, model list, chat, settings all amber/charcoal
- [ ] Toggle Light in Settings, verify legibility
- [ ] Compare against the control screen — same amber, coherent

---

## Acceptance Criteria
- [ ] No Google-blue remains in app chrome (scheme + CustomColors)
- [ ] Amber `#FFB000` is the accent; charcoal `#0B0B0D` the dark surface
- [ ] Matches the control screen + launcher identity (DESIGN.md)
- [ ] Light theme still legible; Settings toggle works
- [ ] Charcoal splash, no white flash
- [ ] Build green; verified on-device with screenshots

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Blue leaks via CustomColors (forgotten layer) | High if Task 3 skipped | Med | Task 3 explicit; grep `0x..3174F1`/`0x..0B57D0` after |
| Amber primary unreadable on white (light theme) | Med | Med | Light uses deepened amber `#9A6A00`, not `#FFB000` |
| White splash flash on dark app | Med | Low | Task 4 |
| Some screens hardcode colors | Low | Low | Only Utils.kt (functional) + 1 tint found; not chrome |

## Notes
- Update `DESIGN.md` Decisions Log after implementing (Gallery UI now on the Relais palette).
- Keep this on its own branch off `main`; squash-merge (consistent with PRs #1–#3).
- Fonts intentionally untouched — if a future pass wants the mono identity in Gallery too, that's a separate plan (Type.kt swaps Nunito → a mono family; higher risk to readability).
