# Contributing to the App8 Android SDK

Thanks for your interest in improving the App8 Android SDK. This guide covers
how to build, test, and submit changes.

## Prerequisites

- JDK 17 or newer (CI uses Temurin 17)
- Android SDK with platform 37 (Android Studio installs it; set `sdk.dir` in
  `local.properties` or `ANDROID_HOME`)
- An `app8-dsl` checkout next to this repository for the doc-example tests:
  `scripts/fetch-dsl.sh` clones the tag pinned in `dsl.lock` into `../app8-dsl`
- For on-device work, two emulators (AVDs):
  - `a8_pixel_9_api37`: current release, the default for screenshots
  - `a8_pixel_6_api31`: the Android 12 floor

```bash
emulator -avd a8_pixel_9_api37 -no-window -no-audio -no-boot-anim -gpu host
adb exec-out screencap -p > out.png
```

## Building and testing

```bash
./gradlew :engine-core:test          # pure JVM: models, expressions, variables, layout solver
./gradlew :engine:testDebugUnitTest  # renderer unit tests on the JVM
./gradlew :engine:lint               # Android lint (new findings fail; see engine/lint-baseline.xml)
./gradlew apiCheck                   # public API matches api/*.api
./gradlew :sample:installDebug       # sample + preview host on the running emulator
```

CI (`.github/workflows/ci.yml`) runs all of these except the install, plus
`scripts/check-version.sh`, on every pull request. All of them must pass
before a change merges.

### Public API

`explicitApi()` is on, and every published module has an API dump in
`<module>/api/<module>.api`. When you change the public API on purpose, run
`./gradlew apiDump` and commit the updated dump with the change; reviewers read
the dump diff. Removing or changing a public signature is a breaking change.

### Lint

`engine/lint-baseline.xml` records findings that predate CI. Fix new findings
instead of adding them to the baseline. When you fix a baselined finding,
regenerate the baseline with `./gradlew :engine:updateLintBaseline`.

## Project layout

- `engine-core/`: pure Kotlin/JVM. DSL models, expressions, variables,
  Cassowary layout solver. No Android imports.
- `engine/`: Compose renderer, the `App8` entry point, `App8DataSource`,
  `App8View`, navigation.
- `engine-maps/`: optional Google Maps provider for `map` components.
- `sample/`: host app, preview host for the conformance report, device probe.
  `sample/.../readme/QuickStart.kt` is the README Quick Start; keep them equal.
- `scripts/`: rendering, device probing, version checks.

## Parity rules

The Android SDK is the twin of [`app8-ios-sdk`](https://github.com/appeight/app8-ios-sdk).

- **Port faithfully.** When iOS behavior is the spec, match it. When the DSL
  docs and the Swift code disagree, follow the code and flag the drift in the
  pull request.
- **Brand content renders identically; system chrome is native.** See
  [`app8-dsl/docs/platforms.md`](https://github.com/appeight/app8-dsl/blob/main/docs/platforms.md).
- **Layout uses the same constraints as iOS**, solved by our Cassowary solver
  in a custom Compose `Layout`. Never translate DSL layout to ConstraintLayout.
- **Same analytics contract** as iOS: every component trigger goes through the
  one dispatch helper, buses stamp `engineVersion`, canonical keys are snake_case.
- Visual changes come with a screenshot of Android next to iOS for the same
  fixture.

## Ship DSL changes on both platforms

A DSL feature is one change across the platforms, released together (see
[`app8-dsl/VERSIONING.md`](https://github.com/appeight/app8-dsl/blob/main/VERSIONING.md)):

1. Spec: edit `app8-dsl/docs/` and the feature matrix row in `docs/platforms.md`.
2. iOS: implement in `app8-ios-sdk`.
3. Android: implement here.
4. Studio: implement in the web renderer.
5. Fixture: add a conformance fixture so the screenshot report covers it.
6. Release: tag app8-dsl, bump `dsl.lock` and `EngineVersion.CURRENT` here and
   `EngineVersion.current` on iOS, tag both SDKs with the same version.

Do not merge a DSL feature to `main` on one platform when the other cannot
follow in the same release. A row may say `—` for Android only when the
feature belongs to another platform's system chrome.

## Conventions

- minSdk 31, compileSdk 37, targetSdk 36. Edge-to-edge always; consume insets
  explicitly.
- Back: `PredictiveBackHandler` / NavigationEvent, never `onBackPressed`.
- No DI framework in the library: constructor injection.
- Models are immutable; Compose parameters use stable types; lazy lists set
  `key` and `contentType`.
- New behavior comes with tests.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`); release notes are drafted
  from them.

## Submitting changes

1. Fork the repository and create a branch for your change.
2. Make your change with accompanying tests.
3. Run the commands above and confirm they pass.
4. Open a pull request describing the change and the motivation; for DSL
   changes, link the matching app8-dsl and app8-ios-sdk pull requests.

Maintainers cut releases with `/release` (`.claude/commands/release.md`).

By submitting a contribution you agree it is licensed under the Apache License
2.0, the same license as the project (see [LICENSE](LICENSE)).
