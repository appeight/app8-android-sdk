# Claude Code guidelines for the App8 Android SDK

The Android twin of `app8-ios-sdk`. One DSL, two native renderers, one
version. The DSL spec lives in `appeight/app8-dsl` (pinned in `dsl.lock`);
read `docs/platforms.md` there before changing behavior.

## Build and test

```bash
./gradlew :engine-core:test          # pure JVM: models, expressions, variables, layout solver
./gradlew :engine:testDebugUnitTest  # renderer unit tests (Robolectric/Roborazzi)
./gradlew :sample:installDebug       # sample + preview host on the running emulator
./gradlew :engine:lint apiCheck      # lint (baseline: engine/lint-baseline.xml), public API dumps
./gradlew apiDump                    # after an intended public API change; commit api/*.api
```

CI (`.github/workflows/ci.yml`) runs the tests, lint, `apiCheck`,
`scripts/check-version.sh` and the sample build. Releases: `/release`
(`.claude/commands/release.md`) on the shared train; `EngineVersion.CURRENT`
is the only place the version is written (Gradle reads it), and `dsl.lock`
equals it.

Emulators: AVDs `a8_pixel_9_api37` (current) and `a8_pixel_6_api31` (floor).
Headless: `emulator -avd a8_pixel_9_api37 -no-window -no-audio -no-boot-anim -gpu host`.
Screenshots: `adb exec-out screencap -p > out.png`.

## Modules

- `engine-core`: pure Kotlin/JVM. DSL models (`model`), expressions (`expr`),
  variables (`vars`), Cassowary layout solver (`layout`). No Android imports.
- `engine`: Compose renderer, `App8` entry point, `App8DataSource`, navigation.
- `sample`: host app, `PreviewHost` for the conformance report, `ProbeActivity`
  for the device catalogue (`scripts/probe-pixels.sh`).

## Parity rules

- Port from Swift faithfully. When iOS behavior is the spec, match it; when
  the DSL docs and the Swift code disagree, follow the code and flag the drift.
- Brand content renders identically to iOS. System chrome follows Android
  (`app8-dsl/docs/platforms.md`): Material 3 top app bar, NavigationBar,
  predictive back, ripple, stretch overscroll, Roboto as `system` font.
- Layout uses the same constraints as iOS, solved by our Cassowary solver in
  a custom Compose `Layout`. Never translate DSL layout to ConstraintLayout.
- A DSL feature ships on both platforms in the same release (see
  `app8-dsl/VERSIONING.md`). Update the feature matrix row when you add one.
- `EngineVersion.CURRENT` equals the iOS `EngineVersion.current` and the git tag.

## Android rules

- minSdk 31, compileSdk 37, targetSdk 36. Edge-to-edge always; consume
  insets explicitly (`WindowInsets.safeDrawing`, `displayCutout`).
- Back: `PredictiveBackHandler` / NavigationEvent, never `onBackPressed`.
- No Hilt or other DI framework in the library: constructor injection.
- Models are immutable; lists in Compose parameters are `ImmutableList` or
  wrapped in `@Immutable` holders. Lazy lists set `key` and `contentType`.
- `explicitApi()` is on: every public symbol is intentional.
- See `docs/android-best-practices.md` and the installed `android-skills`
  plugin (edge-to-edge, navigation-event, jetpack-compose-performance,
  testing-setup) before inventing patterns.

## Analytics and events

Same contract as iOS (`app8-dsl/docs/analytics-events.spec.md`): every
component trigger goes through one dispatch helper so author-declared
analytics fire; buses stamp `engineVersion`; canonical keys are snake_case.
