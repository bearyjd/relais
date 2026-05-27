# Implementation Report: Rebrand the Gallery UI with Relais colors

## Summary
Recolored the stock Gallery UI to the Relais palette (amber `#FFB000` on charcoal `#0B0B0D`). The app
uses a fixed (non-dynamic) Material 3 scheme, so this was value swaps in `Color.kt` + `Theme.kt`'s
`CustomColors`, charcoal splash, and a dark default. Verified on-device (Pixel 9 Pro Fold): home screen
renders amber-on-charcoal with no blue/purple/red/green clashes.

## Assessment vs Reality
| Metric | Predicted | Actual |
|---|---|---|
| Complexity | Medium | Medium |
| Confidence | 8/10 | Confirmed (one extra retint round for task-card clash) |
| Files Changed | ~4 | 5 |

## Tasks Completed
| # | Task | Status | Notes |
|---|---|---|---|
| 1 | Dark scheme → amber/charcoal | ✅ | Color.kt |
| 2 | Light scheme → deepened amber | ✅ | Color.kt; `#9A6A00` for white-bg legibility |
| 3 | Retint CustomColors blue chrome | ✅ | title gradient, tab header, links, bubbles, bg-stars |
| 4 | Charcoal splash | ✅ | both themes.xml |
| 5 | Default to dark | ✅ | DataStoreRepository:148 |
| 3b | Task-card + New-badge retint | ✅ | **Deviation** — see below (caught by the home screenshot) |

## Validation Results
| Level | Status | Notes |
|---|---|---|
| Static / build | ✅ Pass | `assembleDebug` green (twice) |
| Unit tests | N/A | pure recolor |
| On-device visual | ✅ Pass | Home screen screenshotted; amber/charcoal cohesive, dark default applied |

## Files Changed
| File | Action |
|---|---|
| `ui/theme/Color.kt` | UPDATED — full light+dark scheme to Relais palette |
| `ui/theme/Theme.kt` | UPDATED — CustomColors chrome + task quartet + New badge → amber |
| `res/values/themes.xml` | UPDATED — splash bg charcoal |
| `res/values-night/themes.xml` | UPDATED — splash bg charcoal |
| `data/DataStoreRepository.kt` | UPDATED — default theme Dark |

## Deviations from Plan
- **Task-category cards + "New" badge retinted** (plan had these as "optional, leave"). The first
  on-device screenshot showed the blue AI-Chat hexagons and purple "New" badge clashing badly with the
  amber app. Retinted the 4 task swatches/gradients to a warm amber quartet (amber/orange/gold/bronze —
  still distinguishable) and the badge to amber. WHY: the user asked for a cohesive rebrand; leaving them
  was visibly half-done.

## Not Done (out of scope / flagged)
- **Toolbar Google-dots logo** — an image drawable, not a themeable color; still multicolor. Needs an
  asset swap, not a recolor. Follow-up if wanted.
- **Promo banner brushes** (`promoBannerBgBrush`/`IconBgBrush`) — still bluish; transient new-feature
  banner, low visibility. Left per plan.
- **`ui/common/Utils.kt` detection palette** — functional distinct-category colors for bounding boxes;
  not brand chrome.
- **Fonts** — Gallery stays Nunito; mono is control-screen-only by design.

## Next Steps
- [ ] Spot-check chat + settings screens (global theme should carry; home verified)
- [ ] `/prp-pr` then squash-merge
