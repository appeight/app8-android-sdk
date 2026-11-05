# Android system chrome spec

The contract for every piece of system chrome the Android SDK draws around DSL
content. The Studio web renderer (`shoppu-web` `packages/app-dsl/src/android/`)
copies these numbers. Brand content (views, labels, images, DSL fonts and colors)
is out of scope: it renders identically to iOS. Colors come from the app, never
Material's purple baseline (`app8-dsl/docs/platforms.md`, "Android system colors").

Units are dp (sp for text). Measured on `a8_pixel_9_api37` (1080×2424, 420 dpi:
1dp = 2.625px) against Google's own apps on the same image (Clock, Phone,
Contacts, Files, Settings) in November 2026. Compose Material3 is 1.4.0 (BOM
2026.09); 1.5 (full Expressive) was still a release candidate.
`ChromeSpecTest` pins the constants below. Change the test, this file and Studio
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
change animates over 200ms. Sources: M3 app bars specs ("on scroll" container
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
| Indicator | 56×32 pill | 56×32, tab tint at 14% | T `NavigationBarVerticalItemTokens` |
| Icon | 24 | 24 | T |
| Label | labelMedium 12/16 medium | labelMedium | T |
| Container | `surfaceContainer` | `surfaceContainer` of the app scheme | T |
| Wide windows (≥ 600) | horizontal items (icon beside label), centered | `Centered` + `NavigationItemIconPosition.Start` from 600dp | S, M3 |

## Sheets, dialogs, controls

| Element | Spec | Ours | Source |
|---|---|---|---|
| Bottom sheet drag handle | 32×4, 22 above and below | Material `DragHandle`; content height subtracts the 48 block so the sheet top stays 10 below the status bar | T `SheetBottomTokens` |
| Bottom sheet corners | 28 top | 28 (Material) | T |
| Bottom sheet scrim | 32% black | Material | T `ScrimTokens` |
| Bottom sheet max width | 640 | Material | S |
| Bottom sheet container | `surfaceContainerLow` | `systemBackground` (sheet content is the DSL screen) | T |
| Dialog | corner 28, headlineSmall 24/32, 280–560 wide | Material `AlertDialog` | T `DialogTokens` |
| Switch | track 52×32; thumb 16 / 24 / 28 pressed | Material `Switch`, laid out at the iOS size (brand layout) | T `SwitchTokens` |
| Slider (baseline) | Material `Slider` | Material `Slider` | T |

## Motion

| Element | Spec | Ours | Source |
|---|---|---|---|
| `system` push / pop | Material shared axis X: 30 slide, outgoing fades over the first 35%, incoming over the rest | shared axis X, 450ms, emphasized `cubic(0.2, 0, 0, 1)` (`MaterialTransitions`) | M3 transition patterns, MDC `MaterialSharedAxis` (M3 `motionDurationLong1` 450) |
| Navigation 3 default (replaced) | 700ms cross-fade; predictive: scale to 0.7 | not used | N3 |
| Predictive back (in-app) | top screen scales to 90%, drifts 1/20 of its width away from the swipe edge, then commits | 0.9 scale, width/20 drift, fades only in the last third; screen below slides in 30 and fades in | M3 gestures, S `SearchBar.kt` predictive back (0.9, 8 margin, w/20) |
| Top bar color change | effects spring | 200ms tween (`COLOR_MS`) for the search bar; Material's own for `TopAppBar` | S |
| Bottom sheet | `tween(300, FastOutSlowIn)` | Material | S `SheetDefaults` |
| Easing tokens | emphasized decelerate (0.05, 0.7, 0.1, 1), emphasized accelerate (0.3, 0, 0.8, 0.15), standard (0.2, 0, 0, 1) | standard / emphasized cubic | T `MotionTokens` |
| Springs (standard scheme) | spatial default 0.9 / 700; expressive default 0.8 / 380 | Material defaults (standard scheme) | T |

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
- Predictive back has no rounded corners on the shrinking screen (Material rounds
  them toward the display corner radius).
- The stacked search row (search under a title) does not hide on scroll.
- Expressive-only styles (flexible top app bars with subtitles, Expressive shape
  morph on press, Expressive switches and 16dp sliders) wait for compose-material3
  1.5 stable and `MaterialExpressiveTheme`.
- Pixel Settings draws its back button in a 40dp tonal circle (SettingsLib
  Expressive). Third-party Material 3 apps keep the plain icon button, so we do too.
