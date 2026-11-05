# Performance

How the renderer is measured, what the numbers were in November 2026, and where the
remaining time goes. Re-run before trusting a number: emulators are noisy.

## Tools

| What | How |
|---|---|
| Layout solver (JVM) | `./gradlew :engine-core:layoutBenchmark`. `LayoutSolverBenchmark` runs 30 warm-up and 60 timed solves per fixture and prints the median, p90 and min. |
| Macrobenchmarks | `:benchmark` module (`Benchmarks`), run on the sample's `benchmarkRelease` build (R8, profileable, debug-signed). |
| Engine trace sections | `App8.solve` (a solver run on a cache miss), `App8.layoutTree` (solver input: expressions and text measuring), `App8.cell` (a collection cell's node tree). These show up in Perfetto and in the benchmark JSON as `TraceSectionMetric`s (Sum and Max per iteration). |
| Recompositions | Debug builds only: `adb logcat -s App8Recompose`. Prints how many times each node's body has run, once a second while the counts change. The release build has a no-op. |
| Stability | `./gradlew :engine:compileReleaseKotlin -Papp8.composeReports -Pkotlin.incremental=false --rerun` writes the reports to `engine/build/compose_compiler/`. |
| Baseline Profile | Generate it with `ANDROID_SERIAL=<device> ./gradlew :engine:generateBaselineProfile`. The output, `engine/src/main/generated/baselineProfiles/baseline-prof.txt`, is filtered to `dev.app8.engine.**` and ships in the AAR. |

### Running the macrobenchmarks without Gradle installing on every device

```bash
./gradlew :sample:assembleBenchmarkRelease :benchmark:assembleBenchmarkRelease
adb -s $SERIAL install -r sample/build/outputs/apk/benchmarkRelease/sample-benchmarkRelease.apk
adb -s $SERIAL install -r -t benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk
adb -s $SERIAL shell am instrument -w -e class dev.app8.benchmark.Benchmarks \
  -e androidx.benchmark.suppressErrors EMULATOR \
  dev.app8.benchmark/androidx.test.runner.AndroidJUnitRunner
adb -s $SERIAL pull /sdcard/Android/media/dev.app8.benchmark/   # JSON and Perfetto traces
```

Frame percentiles come from `frameDurationCpuMs`. Jank % is the share of frames with
`frameOverrunMs > 0`, computed from every frame sample in the JSON.

## Journeys

- **startup**: cold start of Facetheory. TTID is the first frame, which is a blank box while
  the bundle loads. TTFD is the first DSL screen, reported with `reportFullyDrawn` on
  `app8.screen.appeared`. 10 iterations.
- **scrollShop**: launch, then *Continue as guest*, then three flings down and three up on
  the shop's section list. 5 iterations.
- **openProduct**: from the shop, tap the first product card, then go back. 5 iterations.
- **scrollLongList**: the `longlist` fixture (1000 self-sizing rows, 8 nodes each, with
  wrapping text), four flings down and four up. 5 iterations.

Each journey runs twice. `None` is a fresh install without AOT compilation. `BaselineProfile`
is `Partial(Require)` with the profiles the APK carries.

## Results

Device: emulator `a8_pixel_9_api37` (google_apis arm64, 1080×2424) on an M-series Mac that
other agents were also using. Take the frame numbers as same-day before/after comparisons on
the same AVD only. Repeated runs of the same build moved jank % by up to 20 points. For
example, scrollShop `None` measured 22.0 % in one run and 0.4 % in another.

### Before (2770a78: original solver, no engine Baseline Profile)

| Journey | Mode | TTID / TTFD (median) | frame CPU p50 / p90 / p99 | jank |
|---|---|---|---|---|
| startup | None | 86.6 / 163.4 ms | | |
| startup | BaselineProfile (libraries' profiles only) | 97.1 / 170.7 ms | | |
| scrollShop | None | | 1.8 / 3.3 / 10.3 ms | 0.4 % |
| scrollShop | BaselineProfile | | 2.0 / 4.3 / 10.4 ms | 0.3 % |
| openProduct | None | | 4.2 / 21.0 / 34.3 ms | 46.7 % |
| openProduct | BaselineProfile | | 1.6 / 17.3 / 23.9 ms | 21.3 % |
| scrollLongList | None | | 2.3 / 4.7 / 9.1 ms | 0.5 % |
| scrollLongList | BaselineProfile | | 1.9 / 3.5 / 6.3 ms | 0.1 % |

Engine trace sections (median per iteration, before, `BaselineProfile`):

| Journey | App8.solve | App8.layoutTree | App8.cell |
|---|---|---|---|
| startup | 5.0 ms (2 solves, max 2.9) | 0.8 ms | — |
| scrollShop | 19.2 ms (30 solves) | 8.8 ms | 0.8 ms (39 cells) |
| openProduct | 3.6 ms (4 solves, max 2.9) | 1.2 ms | 0.0 ms |
| scrollLongList | **199 ms** (271 solves, ~0.75 ms each) | 60 ms | 5.4 ms |

On the long list, solving each new cell is three quarters of the engine's main-thread work.
That is the reason for the solver changes below.

### After

There are no on-device after numbers yet. The emulator was taken away before the Baseline
Profile could be generated and the suite re-run (see *Unfinished*). The solver change is
measured on the JVM:

| Fixture (`layoutBenchmark`) | nodes | passes | before median | after median | speed-up |
|---|---:|---:|---:|---:|---:|
| stackList750 (scroll → stack of 150 rows) | 753 | 1 | 162.3 ms | 36.3 ms | 4.5× |
| constraintCards750 (sibling chains, wrapping text) | 752 | 2 | 43.5 ms | 7.3 ms | 5.9× |
| productCardCell (Facetheory `ProductCard`) | 7 | 2 | 0.133 ms | 0.056 ms | 2.4× |
| wrappingTextCell (self-sizing, 2 passes) | 4 | 2 | 0.057 ms | 0.026 ms | 2.2× |

The cell fixtures match the per-cell work in `App8.solve` on the long list. Expect that
section to drop by about 2× on device.

## Changes

### Layout solver (`engine-core`)

- **Primitive, insertion-ordered tableau maps.** Rows (`Symbol → Double`) and the column
  index (`Symbol → basic rows`) were `LinkedHashMap`s of boxed doubles. They are now
  `SymbolMap`s: arrays plus an open-addressing index keyed by a per-solver symbol id, and the
  row and column live on the `Symbol` itself, so the hot path does no hash lookups.
  Iteration order matches `LinkedHashMap`, so pivot choices, and therefore frames, are
  unchanged. This is checked by a randomised comparison against `LinkedHashMap` and a golden
  dump of the benchmark fixtures.
- **No dummy markers in layout passes.** kiwi gives every required equality a dummy symbol
  that is always zero and never pivots. It only lets `removeConstraint` find the constraint
  again. A layout pass never removes constraints, so `LayoutPass` uses
  `Solver(removable = false)`, which skips the dummies. On a stack's position chain they
  were 390k of the 745k tableau cells. The public `Solver()` is unchanged. A randomised test
  checks that both modes reach the same solutions.
- The existing `SolveCache` (keyed by tree signature, bounds, safe area and keyboard) still
  skips repeat solves. Incremental re-solving between passes was not added: the second
  pass only runs when wrapping text re-measures, and a rebuild now costs microseconds per
  cell.

### Compose stability

- Strong skipping is on, and every render composable is skippable (compiler reports). The
  only unstable parameters in hot paths are DSL models from `engine-core`
  (`CollectionProperties`, `Material`, `NavigationBar`, …). They are immutable and keep
  their identity, so the identity check is correct and cheaper than `equals()`. Following
  the jetpack-compose-performance guidance, they are deliberately not annotated.
- `engine/compose-stability.conf` marks engine-core's flat value types (`EdgeInsets`,
  `Frame`, `Size`, `LayoutBounds`, `AxisValues`, `StackSpec`, `ScrollSpec`, `ColorHex`) and
  `java.time` as stable. Before this, `ScreenCanvas(safeArea: EdgeInsets)` was recomposed
  for every new but equal inset.
- Recomposition counts, debug build, on emulator:
  - Shop: a horizontal swipe on one product row composed only the 3 cards it brought in
    (+21 node compositions = 3 × 7 nodes). The other row, the section list and the strip did
    not recompose.
  - Shop: a vertical scroll composed the 2 new sections and their 4 cards (+34). Adding to
    the bag (the `cart` variable) recomposed no DSL node; only the tab badge changed.
  - Long list: each scroll composed only the rows entering the viewport (8 nodes each).
    Rows already on screen recomposed 0 times.

### Baseline Profiles

- `:benchmark` produces the profile, and `:engine` consumes it (`androidx.baselineprofile`,
  filter `dev.app8.engine.**`; engine-core's classes are included because the rules apply
  in the host's dex).
- The sample ships `profileinstaller`.
- The profile has not been committed yet (see *Unfinished*).

## AAR size

| | bytes |
|---|---:|
| `engine-release.aar` | 7,728,095 |
| `res/font/material_symbols_rounded.ttf`, compressed in the AAR | 6,641,474 (86 % of the AAR) |
| same font, uncompressed (what the APK carries) | 15,235,612 |
| `classes.jar`, compressed | 1,080,168 |

Subsetting the font to the 402 Material names that `sf-to-material.json` maps (506 SF names),
measured with fontTools: keep those 400 ligature glyphs plus the letters, and keep all axes.

| Variant | uncompressed | compressed (≈ AAR share) | AAR saving |
|---|---:|---:|---:|
| current font (4304 ligatures, axes FILL/GRAD/opsz/wght) | 15.2 MB | 6.64 MB | — |
| subset, all axes | 0.86 MB | 0.47 MB | ≈ 6.2 MB (−80 % of the AAR) |
| subset, `GRAD` pinned to 0 (the engine always sets 0) | 0.48 MB | 0.28 MB | ≈ 6.4 MB |

Subsetting drops every icon that the table does not map, which means bare Material names
used as `android` overrides, too. That is the product decision still pending, so the font
is unchanged.

## Remaining hotspots

1. **Opening a product**: 21–47 % jank and p90 frames of 17–21 ms. The solver accounts for
   only ~4 ms of this. The rest is composition of the 26 KB product screen during the push
   transition, plus image decode. The next step is a Perfetto pass on
   `openProduct*.perfetto-trace`.
2. **Cell creation on long lists**: `App8.layoutTree` (60 ms over 271 cells) builds the
   cache signature by `toString()` of every node's layout and traits on each solve. A
   structural key, using data-class equality instead of strings, would remove most of it.
3. **Startup**: TTFD is ~165 ms, of which the engine's solve and layout tree are ~6 ms. The
   rest is bundle load and decode on a background coroutine, plus first composition.
4. **Font**: 15 MB in every host APK (see above).

## Unfinished

- On-device after numbers and the generated `baseline-prof.txt`. The generator was running
  on emulator-5558 when another session's app took over the device, and the run was
  stopped. To finish, on an exclusive device:
  `ANDROID_SERIAL=… ./gradlew :engine:generateBaselineProfile`, commit
  `engine/src/main/generated/baselineProfiles/baseline-prof.txt`, rebuild, and re-run
  `Benchmarks` as above.
