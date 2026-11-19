# Android design rubric

How we judge whether an app rendered by the Android SDK looks and behaves like a
well-made Material 3 Android app. Audits and QA score screens against this file.
It complements [`android-chrome-spec.md`](android-chrome-spec.md), which pins the
exact numbers for system chrome (bars, search, navigation bar, sheets, motion).
This file covers everything around the chrome: how the screen feels as a whole.

Ground rule (`app8-dsl/docs/platforms.md`): brand content (DSL views, fonts,
colors, images, copy) is the same on both platforms; system chrome and
interaction are native. A finding is therefore always one of:

- **engine**: the Android SDK draws chrome or behaves in a non-Android way;
- **spec**: the DSL has no way to express the Android-correct result, or maps an
  iOS-only property badly;
- **content**: the app's JSON makes an iOS-only choice that also looks wrong on
  Android (fix in the app, platform-neutral);
- **host**: the host app's own UI around the engine (sign-in, app lists, share flows).

Units: dp and sp. Reference device `a8_pixel_9_api37` (412×915 dp); small device
`a8_pixel_6_api31` (on moonlander). Measure with `uiautomator dump` bounds
divided by the density (2.625 on Pixel 9), never by eye.

## Severity

| Level | Meaning |
|---|---|
| S1 | Broken or clearly wrong: overlap, clipped text, unreachable control, wrong back behavior, crash, missing feature that iOS shows |
| S2 | Reads as "an iPhone app on Android" or as sloppy: wrong metric by > 4dp, iOS idiom in chrome, no press feedback, janky or wrong motion |
| S3 | Polish: off by 2–4dp, timing slightly off, minor inconsistency between screens |

## 1. Layout and spacing

| Check | Expected | Source |
|---|---|---|
| Screen side margin | 16dp on compact width (< 600dp), 24dp medium and up. One left edge per screen | M3 layout, window size classes |
| Spacing grid | multiples of 4dp, mostly 8dp steps (8, 12, 16, 24, 32) | M3 spacing |
| Touch targets | ≥ 48×48dp for every tappable, including icon-only buttons and chips | M3 accessibility, Android a11y |
| List rows | one line 56dp, two lines 72dp, three lines 88dp; 16dp start/end padding; leading icon 24dp, avatar 40dp, image 56dp | `ListItem` tokens |
| Content below the top bar | starts at the bar's bottom edge plus the content's own padding; no double gaps (bar + large title + spacer) | chrome spec |
| Small screen (Pixel 6, 412×869 → 411×914 at 2.625; test also at 360dp width) | nothing clipped or overlapping; text wraps, does not truncate key copy | — |
| Large text (font scale 1.3) | layout grows, nothing overlaps; bars keep their height | Android a11y |
| Edge-to-edge | content under status and gesture bars only when intended (hero); scrolling content ends above the gesture bar inset | E2E |

## 2. Top of the screen

| Check | Expected |
|---|---|
| Bar choice | small 64dp bar for most screens; medium (112) or large (152) only on top-level screens with a short title, collapsing to 64 on scroll |
| Total header height at rest | status bar + bar + (optional search 64) ≤ about 25% of the screen height on Pixel 9 (≈ 230dp). More reads as "huge bars" |
| Title | left-aligned (`TopAppBar`) by default; centered only for brand wordmarks |
| Back | `arrow_back` at the start, 48dp target; system back and predictive back always work |
| No iOS idioms in chrome | no "< Back" text, no chevron-left glyph, no blur/translucent bar, no hairline under the bar |

## 3. Typography

Brand fonts and sizes come from the app. The Android check is about readable
defaults and chrome text:

| Check | Expected |
|---|---|
| Chrome text | M3 type scale: titleLarge 22/28 bar title, labelLarge 14 buttons, labelMedium 12 nav labels, bodyLarge 16/24 |
| Body text minimum | ≥ 14sp for reading text, ≥ 12sp for captions |
| Line breaks | no orphans (`LineBreak.Paragraph`), no single-word last lines in headlines |

## 4. Color and surfaces

| Check | Expected |
|---|---|
| Chrome colors | derived from the app's colors as M3 roles (surface, onSurface, surfaceContainer, primary), never Material's purple baseline, never iOS system blue/gray as visible defaults |
| Depth | tonal surfaces, no drop shadows on bars; cards may use the brand's own look |
| Dark theme | follows the app's DSL; chrome follows the screen background |
| Contrast | text ≥ 4.5:1, large text and icons ≥ 3:1 |

## 5. Interaction and feedback

| Check | Expected |
|---|---|
| Press feedback | every tappable shows feedback within one frame: ripple / state layer (8% pressed overlay of the content color), or the DSL's own touch state |
| Back | predictive back preview on every pushed screen and sheet; back from the root tab goes home, not to a previous tab |
| Sheets | M3 bottom sheet: drag handle, 28dp top corners, scrim, swipe down and back both dismiss |
| Lists | swipe actions only where the app asks; long-press menus as M3 menus or sheets |
| Haptics | on toggles and confirmations only (HapticFeedbackConstants), never on scroll |
| Keyboard | IME action matches the field (search, done, next); content scrolls above the keyboard |

## 6. Motion

Numbers from `MotionTokens` / `MotionScheme` (compose-material3):

| Transition | Expected |
|---|---|
| Push / pop | shared axis X, 30dp slide, ~450ms emphasized (0.2, 0, 0, 1); outgoing fades over the first 35% |
| Tab switch | fade through: outgoing fades out ~90ms, incoming fades and scales 92%→100% over ~210ms. Never a hard cut |
| Sheet | ~300ms in, emphasized decelerate (0.05, 0.7, 0.1, 1); out ~200–250ms emphasized accelerate (0.3, 0, 0.8, 0.15) |
| Predictive back | scale to 90%, drift width/20 − 8dp, rounded corners toward the display radius, commit continues from the gesture |
| Springs | standard scheme spatial 0.9 / 700 (expressive 0.8 / 380); effects 1.0 / 1600 |
| Durations | short 50–200, medium 250–400, long 450–600ms; nothing user-triggered over 600ms unless it is a hero |
| Smoothness | no dropped frames on push, tab switch, scroll (FrameTimingMetric P90 < 16ms on the reference emulator) |

Measure motion from a `screenrecord` (60 fps): first and last frame that differ
give the duration; a frame-difference curve shows the easing.

## 7. Content smells (iOS habits that look wrong on Android)

- iOS large-title header built as content, plus an Android bar above it (double title).
- iOS-style grouped inset table sections with 10pt corner radius and gray page background in chrome-like settings screens.
- `chevron.right` disclosure arrows on every row (Android rows rarely show them).
- iOS share glyph (`square.and.arrow.up`) instead of Material `share`.
- Segmented controls where M3 uses tabs or segmented buttons.
- Swipe-from-left-edge-only affordances; Android back is from both edges.

## How to report a finding

One line each: `S2 · engine · demo/screen · what is wrong (measured vs expected) · evidence (png/mp4 path) · proposed fix`.
