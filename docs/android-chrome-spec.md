# Android system chrome spec

The contract for every piece of system chrome the Android SDK draws around DSL
content. The web renderer
copies these numbers. Brand content (views, labels, images, DSL fonts and colors)
is out of scope: it renders identically to iOS. Colors come from the app, never
Material's purple baseline (`app8-dsl/docs/platforms.md`, "Android system colors").

Units are dp (sp for text). Measured on `a8_pixel_9_api37` (1080×2424, 420 dpi:
1dp = 2.625px) against Google's own apps on the same image (Clock, Phone,
Contacts, Files, Settings) in November 2026. Compose Material3 is 1.4.0 (BOM
2026.09); 1.5 (full Expressive) was still a release candidate.
`ChromeSpecTest` pins the constants below. Change the test, this file and the web renderer
together.

Sources (cited by letter in the tables):

- **T**: Material3 token files,
  https://github.com/androidx/androidx/tree/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens
  (`AppBarSmallTokens`, `AppBarMediumTokens`, `AppBarLargeTokens`, `AppBarTokens`,
  `SearchBarTokens`, `NavigationBarTokens`, `SheetBottomTokens`, `DialogTokens`,
  `SwitchTokens`, `SliderTokens`, `MotionTokens`, `StandardMotionTokens`,
  `ExpressiveMotionTokens`)
- **S**: Material3 component sources, same folder one level up (`AppBar.kt`,
  `SearchBar.kt`, `ShortNavigationBar.kt`, `SheetDefaults.kt`, `AlertDialog.kt`)
- **M3**: https://m3.material.io/components/app-bars/specs,
  https://m3.material.io/components/search/specs,
  https://m3.material.io/components/navigation-bar/specs,
  https://m3.material.io/foundations/interaction/gestures (predictive back),
  https://m3.material.io/styles/motion/transitions/transition-patterns
- **N3**: Navigation 3 `NavDisplay` defaults,
  https://github.com/androidx/androidx/tree/androidx-main/navigation3/navigation3-ui
- **E2E**: https://developer.android.com/develop/ui/views/layout/edge-to-edge,
  https://developer.android.com/about/versions/16/behavior-changes-16
- **REF**: measured in Google's own apps on the emulator (see above)

## Top app bar (`navigationBar`)

| Element | Spec | Ours | Source |
|---|---|---|---|
| Small / center-aligned height | 64 | 64 (`TOP_BAR_HEIGHT`) | T `AppBarSmallTokens.ContainerHeight` |
| Medium height, expanded → collapsed | 112 → 64 | 112 → 64 (`topAppBar: medium`) | T `AppBarMediumTokens` |
| Large height, expanded → collapsed | 152 → 64 | 152 → 64 (`topAppBar: large`) | T `AppBarLargeTokens` |
| Title, small / center | titleLarge 22/28 regular | Material `TopAppBar` (22sp, measured) | T, REF (Files: 22sp title at x=61) |
| Title, medium / large | headlineSmall 24/32; headlineMedium 28/36 | Material `MediumTopAppBar` / `LargeTopAppBar` | T |
| Leading / trailing edge padding | 4 | 4 | T `AppBarTokens` |
| Icon button touch target / icon | 48 / 24 | 48 / 24 (Symbol is an exact 24×24 box) | S, T |
| Navigation icon center | 28 from the edge | 28 (measured: glyph 20.6–36.2) | S, REF |
| Title start with navigation icon | 56 | 56 (glyph starts at 57.1) | S |
| Back icon | `arrow_back` | `arrow_back`, DSL tint | M3 |
| Icon button labels | content description on every icon-only action | back arrow "Navigate up"; bar items their `label` when set, else a name from the symbol ("Search", "Favourite", "Close", "More options", or the symbol's first word); the glyph itself is hidden from TalkBack (`SymbolLabels`) | M3, AppCompat `abc_action_bar_up_description` |
| Title color | `onSurface` | `titleColor`, else the app scheme's `onSurface` | T `AppBarTokens` |
| Empty bar on a root screen | no app bar without content | a root screen (a tab's first screen, the flow start, a modal's first screen) whose bar has no title, `titleView`, items or search draws no bar: 0dp, the status bar inset only. Pushed screens keep the bar for the back arrow | M3 |
| Container at rest | `surface` | transparent over the screen (iOS parity; looks like `surface` over the screen background) | T `AppBarTokens.ContainerColor` |
| Container on scroll | `surfaceContainer`, **no shadow** (elevation 0) | `surfaceContainer` derived from the screen root's background (`systemColorScheme(null, dark, bg)`), animated | T `AppBarTokens.OnScrollContainerColor` |
| Translucency / blur | none: M3 bars are opaque and content is clipped at the bar edge | none | M3, T |
| `topEdgeEffect: hidden` (content under the bar) | see [Top app bar over content](#top-app-bar-over-content) | transparent over the hero, opaque after it | DSL |
| Scroll behavior small / center | pinned (color change only) | `pinnedScrollBehavior` | S |
| Scroll behavior medium / large | exitUntilCollapsed | `exitUntilCollapsedScrollBehavior`; the bar's current height feeds the DSL safe area in whole dp, like a Scaffold's content padding | S |
| `titleView` | n/a (DSL) | DSL component at its fitting size in the title slot (brand content, as iOS). `titleAlignment: left` replaces the left item but keeps the back arrow (chrome) | DSL |
| Text bar items | `TextButton`, labelLarge 14sp medium | `TextButton` | S |

### Top app bar over content

When the DSL lays content under the bar (`topEdgeEffect: hidden`, usually a
scroll view pinned to the superview with `contentInsetAdjustment: never` and a
hero image), the screen keeps the iOS safe-area semantics: views that ignore the
safe area start at y = 0 behind the status bar, and safe-area views start below
the bar. Android draws the Play Store app-details pattern (M3 top app bar over an
image, edge-to-edge):

| State | Container | Icons | Title | Status bar icons |
|---|---|---|---|---|
| Hero under the bar | transparent | 40dp circle behind each icon button, `surfaceContainerLowest` at 90% (`OVER_CONTENT_ICON_ALPHA`), icon in the bar tint | hidden | chosen from the luminance of the pixels under them |
| Hero scrolled away | `surfaceContainer` of the screen background | plain icon buttons | fades in | re-sampled (`FlowController.chromeEpoch`) |

The switch happens when the scroll distance reaches the bottom of the tallest
full-width view that starts at y = 0, minus the bar's bottom edge
(`heroScrollDistance`). With no such view it happens on the first scroll. The
change animates over 200ms. Only views that really draw under the bar count: the
content of a scroll view that insets itself by the safe area
(`contentInsetAdjustment` other than `never`) is skipped.

With `topEdgeEffect: automatic` (or `soft` / `hard`) over a hero drawn under the
bar, the bar keeps its usual behavior (title shown, `surfaceContainer` as soon as
content scrolls under it), but while it is at rest its icons sit on the same
legibility circles, so a back arrow never stands bare on a photo. Sources: M3 app bars specs ("on scroll" container
color, no elevation), E2E (draw behind the system bars), Play Store app details.

## Search (`navigationBar.search`)

| Element | Spec | Ours | Source |
|---|---|---|---|
| Field height | 56 | 56 (`SEARCH_FIELD_HEIGHT`) | T `SearchBarTokens.ContainerHeight` |
| Field shape | full pill | `SearchBarDefaults.inputFieldShape` | T |
| Field color at rest / scrolled | `surfaceContainerHigh` / `surfaceContainerHighest` | same roles from the app scheme | S `appBarWithSearchColors` |
| Leading icon | 24, 16 from the field edge | 24 (`magnifyingglass` → `search`) | S |
| Search as the bar (no title: `integrated`, `inline`, `automatic` without title) | `AppBarWithSearch`: 4 above and below the field (64 bar), field 8 from the 4dp bar edge (12 from the screen) | 4 / 12 (`SEARCH_APP_BAR_VERTICAL_PADDING`, `SEARCH_APP_BAR_FIELD_INSET`) | S `AppBarWithSearchVerticalPadding = 4.dp`, `SearchBarAsTopBarPadding = 8.dp`, REF (Phone: 56dp field, 12dp sides) |
| Search app bar on scroll | enterAlways: scrolls off with content, returns on any upward scroll; bar `surfaceContainer` once content is under it | pinned by default (iOS default `hidesWhenScrolling: false`; also M3, as in Settings and Phone); enterAlways with `hidesWhenScrolling: true`, and the status bar strip keeps the bar color | S `enterAlwaysSearchBarScrollBehavior`, M3 (Gmail, Photos, Play) |
| Search row under a title (`stacked`, `automatic` with title) | n/a (M3 has no stacked variant) | 56 field, 16 sides, 8 below (64 row); turns the bar color on scroll | — |
| `integratedButton` | search action expanding into the bar | search icon action → field in the title slot | M3 |
| Gap from field to content | content's own spacing (4 in the bar) | 4 in the bar + the DSL content's own top spacing | S |

### Why the bar is opaque on scroll (no blur)

Material 3, including M3 Expressive in 2025–26, has no translucent or blurred
app bars. The container is `surface` at rest. Once content scrolls under it, it
turns `surfaceContainer` (`AppBarTokens.OnScrollContainerColor`; elevation 0, so
no shadow), and content is clipped at the bar's bottom edge. Search app bars
(Gmail, Photos, Play) use enterAlways: they leave with the first scroll down and
come back on any scroll up. The field turns `surfaceContainerHighest` while
content is under it (`SearchBarDefaults.appBarWithSearchColors`). Measured on the
emulator: Phone has a 56dp field 2dp below the status bar with 12dp sides, and
its content starts with its own padding (16dp chip row). Contacts (an older
design) has a 48dp field 10dp below the status bar with 16dp sides. The iOS 26
transparent bar with a soft edge blur is an iOS convention, so Android does not
copy it.

## Navigation bar (`tabBarScreen`)

| Element | Spec | Ours | Source |
|---|---|---|---|
| Component | M3 Expressive short navigation bar | `ShortNavigationBar` | S, REF (Clock, Phone: 64dp) |
| Height (without system nav inset) | 64 (baseline 80 is retired) | 64 (`TAB_BAR_HEIGHT`); 88 total with the gesture bar, identical to Clock | T `NavigationBarTokens`, REF |
| Indicator | 56×32 pill | 56×32, `selectionIndicatorColor`, else black at 10% (white at 10% on a dark bar); the tint at 14% up to 0.7.8 | T `NavigationBarVerticalItemTokens` |
| Icon | 24 | 24 | T |
| Label | labelMedium 12/16 medium | labelMedium | T |
| Container | `surfaceContainer` | `surfaceContainer` of the app scheme | T |
| Selected icon, label, indicator | `secondaryContainer` indicator, `onSurface` / `onSecondaryContainer` | `tintColor` (else the scheme's `primary`), indicator `selectionIndicatorColor` (default black or white at 10%) | T, DSL |
| Unselected icon and label | `onSurfaceVariant` | `unselectedColor` while it reaches 4.5:1 on the container (alpha composited), else `onSurfaceVariant`. The iOS gray defaults read 2.3–3:1 there | T, WCAG 1.4.3 |
| Wide windows (≥ 600) | horizontal items (icon beside label), centered | `Centered` + `NavigationItemIconPosition.Start` from 600dp | S, M3 |

## Sheets, dialogs, controls

| Element | Spec | Ours | Source |
|---|---|---|---|
| Bottom sheet drag handle | 32×4, 22 above and below | Material `DragHandle`; the content height subtracts the 48 block | T `SheetBottomTokens` |
| Bottom sheet, large detent | full height: top edge at the status bar's bottom | top edge at the status bar's bottom (no iOS 10pt gap) | S `ModalBottomSheet` |
| Bottom sheet corners | 28 top | 28 (Material) | T |
| Bottom sheet scrim | 32% black | Material | T `ScrimTokens` |
| Bottom sheet max width | 640 | Material | S |
| Bottom sheet container | `surfaceContainerLow` | `surfaceContainerLow` of the app scheme; a sheet screen without its own `backgroundColor` shows it too | T |
| Screen and full-screen cover background | `surface` | `surface` of the app scheme when the screen sets no `backgroundColor` (white / black unless `dynamicColor`) | T |
| Dialog | corner 28, headlineSmall 24/32, 280–560 wide | Material `AlertDialog` | T `DialogTokens` |
| Switch | track 52×32; thumb 16 / 24 / 28 pressed | Material `Switch`, laid out at the iOS size (brand layout) | T `SwitchTokens` |
| Slider (baseline) | Material `Slider` | Material `Slider` | T |

## Motion

Numbers live in `MaterialTransitions` (push / pop) and `MotionSpecs` (everything else the
engine owns). DSL-authored transitions (`scale`, `zoom`, `popup`, `cover`, `sheet`, `shared`,
custom keyframes) keep their authored timing on both platforms; only `system` and engine chrome
follow these rows.

| Element | Spec | Ours | Source |
|---|---|---|---|
| `system` push / pop | Material shared axis X: 30 slide, outgoing fades over the first 35%, incoming over the rest | shared axis X, 450ms, emphasized `cubic(0.2, 0, 0, 1)` (`MaterialTransitions`). The incoming screen is loaded before the transition starts (`SessionCache`, up to 300ms), so it draws on the first frame | M3 transition patterns, MDC `MaterialSharedAxis` (M3 `motionDurationLong1` 450) |
| Navigation 3 default (replaced) | 700ms cross-fade; predictive: scale to 0.7 | not used | N3 |
| Tab switch | fade through: outgoing fades out 90ms, incoming fades in and scales 92% → 100% over 210ms | `MotionSpecs.tabFadeThrough()`: outgoing linear 90ms; incoming 210ms after it, emphasized decelerate; through the `surface` colour. Each tab's screens stay loaded and its saved state is kept, so a revisit draws at once and deferred sections are not rebuilt. Reselect keeps pop-to-root / scroll-to-top | M3 fade through, MDC `MaterialFadeThrough` |
| Press feedback | bounded ripple / pressed state layer, clipped to the shape | bounded ripple in `onSurface` at 10% (`StateTokens.PressedStateLayerOpacity`), starting at 30% radius so it shows on the first frame, clipped to the view's corner radius; on every tappable view and collection cell. A DSL `touchDown` state replaces it, unless it changes only opacity | M3 states, compose-material3 `ripple` |
| Predictive back (in-app) | top screen scales to 90%, drifts width/20 − 8dp, corners round toward the display radius, commit continues from the gesture | `MotionSpecs`: Material `PredictiveBack` easing `cubic(0.1, 0.1, 0, 1)` on the progress; scale 1 → 0.9, drift up to width/20 − 8dp away from the swipe edge, corners 0 → the display's `RoundedCorner` radius (28dp when it reports none); fades only in the last third; release continues from the gesture position. The screen below slides in 30 and fades in. A tab bar hidden by the popped screen follows the gesture back | M3 predictive back, compose-material3 `PredictiveBack`, `SearchBar.kt` (0.9, 8 margin, w/20) |
| Full-screen cover (`fullScreen`, `crossDissolve` without a custom transition) | in: slide up; out: slide down and fade, emphasized accelerate ~250ms | in 400ms emphasized decelerate slide + 200ms fade; out 250ms emphasized accelerate slide + fade, then the layer is removed | M3 easing and duration tokens |
| Bottom sheet | in ~300ms emphasized decelerate; out 200–250ms, scrim fades with the sheet | Material's own animation; the scrim is drawn by the engine under the sheet and fades with it (in 300ms emphasized decelerate, out 220ms emphasized accelerate); a close from code (`dismiss`, a button) hides the sheet first and removes the layer once the scrim has faded. Predictive back shrinks the sheet (Material `ModalBottomSheet`) | S `SheetDefaults` |
| Top bar color change | effects spring | 200ms tween (`COLOR_MS`) for the search bar; Material's own for `TopAppBar` | S |
| Easing tokens | emphasized decelerate (0.05, 0.7, 0.1, 1), emphasized accelerate (0.3, 0, 0.8, 0.15), standard (0.2, 0, 0, 1) | `MotionSpecs.EmphasizedDecelerate` / `EmphasizedAccelerate`, `MaterialTransitions.Emphasized` | T `MotionTokens` |
| Springs (standard scheme) | spatial default 0.9 / 700; effects 1.0 / 1600 (expressive 0.8 / 380) | `MotionSpecs.spatialSpring()` / `effectsSpring()` for engine chrome: tab bar hide / show, swipe-action settle. `MaterialTheme.motionScheme` is internal in the stable artifact, so the values are copied | T `MotionScheme.standard()` |

## Platform

| Element | Spec | Ours | Source |
|---|---|---|---|
| Edge-to-edge | mandatory at targetSdk 36; transparent status and gesture bars | always; insets consumed explicitly | E2E |
| 3-button nav scrim | system contrast scrim unless disabled | system default | E2E |
| Status bar icons | light / dark by what is under them | `StatusBarContrast` | E2E |
| Overscroll | stretch (Android 12+) | Compose default stretch | E2E |
| `system` font | Roboto for third-party apps (Google Sans Flex is Google's own apps; OFL since Nov 2025 but not a system default) | Roboto | https://9to5google.com/2025/11/18/google-sans-flex-font-available/ |

## Not matched yet

- Status bar icons re-sample when a bar turns opaque or transparent, not
  continuously while a hero scrolls beneath them.
- A native zoom pop (`zoom` + `sourceKey`) can lose most of its shrink on a slow
  device: the pop's first frame composes the revealed screen again (Navigation 3 keeps
  only the top entry composed), and on a debug build that frame takes ~500ms. The pop
  now runs on its own clock after that frame, but the frame itself still stalls.
- `system` pops share that first-frame cost (Navigation 3 animates them on its own
  clock), so a heavy screen below can shorten the pop.
- The search screen push uses shared axis X, not the M3 search view expand (the DSL
  search field and the pushed search screen are separate screens).
- The stacked search row (search under a title) does not hide on scroll.
- Expressive-only styles (flexible top app bars with subtitles, Expressive shape
  morph on press, Expressive switches and 16dp sliders) wait for compose-material3
  1.5 stable and `MaterialExpressiveTheme`.
- Pixel Settings draws its back button in a 40dp tonal circle (SettingsLib
  Expressive). Third-party Material 3 apps keep the plain icon button, so we do too.
