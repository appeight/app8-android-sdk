# Android best practices for this SDK

Research notes behind the SDK's design, gathered November 2026. Each point
names the decision it drove. Re-check the sources before relying on a
version number.

## Platform baseline

- **Android 17 (API 37)** is the current release. Google Play requires
  `targetSdk 36` for new apps and updates from August 31, 2026; API 37 will
  likely follow in August 2027.
  Decision: `compileSdk 37`, `targetSdk 36` in the sample.
- **minSdk 31 (Android 12).** AndroidX libraries require at least API 23. We
  pick 31 because it is the first level with `RenderEffect` blur (DSL
  materials and glass), `WindowInsets.getRoundedCorner`, display cutout
  paths, stretch overscroll and dynamic color. Every Pixel from the Pixel 6
  ships with it.
- **Edge-to-edge is mandatory** when targeting API 36: the opt-out flag is
  gone. Every screen draws behind the system bars and consumes insets
  explicitly. Use `WindowInsets.safeDrawing`, `displayCutout`, `systemBars`
  and `ime`. https://developer.android.com/about/versions/16/behavior-changes-16
- **Predictive back** is on by default and `onBackPressed` is no longer
  called. Use `PredictiveBackHandler`, or the NavigationEvent library
  (`android-skills:navigation-event`).

## UI toolkit and architecture

- **Jetpack Compose** for all rendering. Ship a `ComposeView`-based wrapper
  for hosts built with Views. Google's own server-driven runtime (Remote
  Compose) ships the same pair.
- **Unidirectional data flow** as in Now in Android: state flows down, events
  flow up, and screen state is exposed as `StateFlow`.
  https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md
- **No DI framework in a library.** Hilt would force itself on host apps. Use
  constructor injection behind `App8.instance(...)`.
- **Material 3:** stable 1.4. The Expressive APIs are in 1.5 alphas. Do not
  hard-wire Material colors or shapes into DSL content: DSL tokens drive the
  look. Use Material only for system chrome (top app bar, navigation bar,
  sheets, dialogs, pickers).

## Server-driven UI references

- Airbnb Ghost Platform: sections, screens and actions; clients in Kotlin,
  Swift and TypeScript.
  https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5
- DoorDash: generic UI primitives instead of data models.
- Zalando Appcraft: one flex layout from the server, RecyclerView and
  UICollectionView on the clients.
  https://engineering.zalando.com/posts/2024/05/appcraft.html
- DivKit (Yandex, Apache 2.0): a JSON DSL with a Compose renderer. Worth
  reading for decoding and action design.
- Remote Compose (`androidx.compose.remote`, alpha): a binary stream of
  drawing operations, not a component schema. Not a fit for a JSON DSL.

Our design: kotlinx.serialization into an immutable node tree, a component
registry keyed by type, one action dispatcher, state held in variable
stores, and layout solved by Cassowary to keep iOS Auto Layout semantics.
ConstraintLayout cannot express priorities, inequalities, hugging or
compression resistance, so it is not used.

## Performance

- Strong skipping is on by default (Kotlin 2.0.20+): unstable parameters are
  compared by identity. Keep models immutable so skipping works.
- Lazy lists need a `key` and a `contentType` (the node type).
- Ship a Baseline Profile in the library AAR (`androidx.baselineprofile`).
- Tools: `android-skills:jetpack-compose-performance`,
  https://skydoves.github.io/compose-stability-analyzer/

## What makes an Android app feel polished

- Correct insets everywhere, including the keyboard.
- Predictive back previews.
- The Android 12+ stretch overscroll (Compose's default), not an iOS bounce.
- Ripples on press, with the right bounds.
- Haptics through `HapticFeedbackConstants` (`CONFIRM`, `REJECT`,
  `GESTURE_START`, …).
- Spring motion and shared-element transitions.
- Text sized in sp and scaling up to 200% (non-linear font scaling).
- Readable system bar icons over any content.

## Testing

- JVM unit tests for `engine-core`: fast, no emulator.
- Roborazzi (Robolectric) for component snapshots on the JVM.
- Emulator end-to-end runs for real insets, cutouts, corners and predictive
  back: the conformance report (`app8-conformance`, `/sdk-report`).
- Apple Silicon: use only `arm64-v8a` `google_apis` images (Play Store
  images block adb automation).

## Agent skills

- Installed: Google's official `android/skills` plugin. Relevant here:
  edge-to-edge, navigation-event, navigation-3, jetpack-compose-performance,
  testing-setup, agp-9-upgrade, android-cli.
- Worth reading before adopting: `skydoves/compose-performance-skills`
  (strong skipping, Baseline Profiles).
