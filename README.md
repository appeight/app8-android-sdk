# App8 Android SDK

The App8 Android SDK is a dynamic UI rendering engine for Android. It turns a
JSON-based declarative UI language, the **App8 DSL**, into native Jetpack
Compose UI at runtime, with reactive variables, expression evaluation,
component state, and flow-based navigation.

It is the Android twin of [`app8-ios-sdk`](https://github.com/appeight/app8-ios-sdk):
one DSL, two native renderers, one version. An app written once renders the
same brand content on both platforms, with system chrome native to each.

- **Native rendering**: no web views; the DSL becomes real Compose UI.
- **Reactive**: `{{expression}}` bindings, variables, and component states.
- **Host-driven content**: you supply DSL JSON through a single interface, from
  assets, the network, or anywhere else.
- **Same layout as iOS**: DSL constraints are solved by a Cassowary solver in a
  custom Compose `Layout`.

## Requirements

- Android 12 (API 31) or newer (`minSdk 31`)
- Kotlin 2.4+
- Jetpack Compose (the SDK brings the Compose BOM it was built against);
  hosts built on Views use `App8View`

## Installation

The SDK is published to GitHub Packages as three artifacts:

| Artifact | Contents |
|---|---|
| `dev.app8:engine` | The renderer: `App8`, `App8DataSource`, `App8View` (pulls in `engine-core`) |
| `dev.app8:engine-core` | Platform-neutral DSL models, expressions, variables, layout solver (JVM) |
| `dev.app8:engine-maps` | Optional: draws `map` components with Google Maps |

GitHub Packages requires a token for Maven downloads, even for public
packages: create a personal access token with the `read:packages` scope and
put it in `~/.gradle/gradle.properties` as `gpr.user` / `gpr.key` (or the
`GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables on CI).

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/appeight/app8-android-sdk") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

In your app module:

```kotlin
dependencies {
    implementation("dev.app8:engine:0.4.0")
    // implementation("dev.app8:engine-maps:0.4.0") // Google Maps for `map` components
}
```

> The SDK is at an early release (`0.x`); the public API may change between
> minor versions.

## Quick Start

The SDK never fetches content itself. You implement `App8DataSource` to feed it
DSL JSON, then draw an `App8Instance`. This code is compiled with the sample app
([`QuickStart.kt`](sample/src/main/kotlin/dev/app8/sample/readme/QuickStart.kt)).

### 1. Provide content with `App8DataSource`

```kotlin
import android.content.res.AssetManager
import dev.app8.engine.App8DataSource

/**
 * Serves DSL JSON bundled in the app's assets (`assets/app8/app.json`, `screens/<id>.json`, ...).
 * Swap the asset reads for network calls to update the UI without an app release.
 */
class AssetsDataSource(
    private val assets: AssetManager,
    private val root: String = "app8",
) : App8DataSource {
    private fun read(path: String): ByteArray = assets.open("$root/$path").use { it.readBytes() }

    override suspend fun getApp(): ByteArray = read("app.json")
    override suspend fun getStyles(): List<ByteArray> = listOf(read("styles.json"))
    override suspend fun getComponents(): List<ByteArray> = emptyList()
    override suspend fun getScreen(screenId: String): ByteArray = read("screens/$screenId.json")
    override suspend fun getComponent(componentId: String): ByteArray = read("components/$componentId.json")
    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = null

    // getDatasource, getAllScreenIds, getTranslations and the stream* methods have default
    // implementations: override them only when you need those features.
}
```

Every method is called off the main thread, so implementations may do IO
directly.

### 2. Render

With Compose, `App()` draws the whole app from its start flow: navigation,
tabs, sheets, and alerts.

```kotlin
class MainActivity : ComponentActivity() {
    private val app8: App8Instance by lazy { App8.instance(this, AssetsDataSource(assets)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            app8.App(onError = { error -> Log.e("App8", "App8 failed to load", error) })
        }
    }
}
```

`Flow(flowId)` draws one flow of the manifest and `Screen(screenId, params)` a
single screen; both can still navigate.

In a View hierarchy, use `App8View`:

```kotlin
val view = App8View(this).apply {
    instance = App8.instance(context, AssetsDataSource(assets))
    onError = { error -> Log.e("App8", "App8 failed to load", error) }
}
setContentView(view)
```

Set `screenId` (or call `setScreen(screenId, params)`) to show one screen
instead of the whole app.

Below the SDK floor, or below the app's own `platforms.android.minSdk`,
`App8.instance(...)` throws (or `onError` receives)
`App8Error.UnsupportedPlatform`; show your own native fallback. `App8.isSupported`
checks the SDK floor up front.

### 3. Receive events and analytics

DSL screens fire two kinds of typed events into the host. Subscribe at app
launch and route by `event.name`.

```kotlin
// Host action events from `emit` actions in the DSL.
app8.eventBus.subscribe { event ->
    when (event.name) {
        "connect.tapped" -> startStripeFlow()
        "user.selected" -> showProfile(event.payload["name"] as? String)
    }
}
// Analytics events, author-declared and auto `app8.*`. `properties` is the canonical,
// fully merged payload: forward it as is.
app8.analyticsBus.observe { event ->
    analytics.track(event.name, event.properties)
}
```

Both return an `App8Subscription`; call `cancel()` to stop. Every event carries
`screenId`, `componentId`, `componentType`, `locale` and `engineVersion`; the
analytics bus merges them into `event.properties` under stable snake_case keys
(`screen_id`, `engine_version`, ...), the same contract as iOS.

## The App8 DSL

The DSL describes an app as JSON: an app manifest, navigation flows, and
screens built from components (`label`, `button`, `image`, `collection`,
`stackView`, ...). A minimal screen:

```json
{
  "type": "screen",
  "content": {
    "navigationBar": { "title": "Home" },
    "variables": { "count": { "type": "number", "initialValue": 0 } },
    "children": [
      {
        "type": "label",
        "content": { "properties": { "text": "Count: {{count}}" } }
      },
      {
        "type": "button",
        "content": {
          "properties": { "text": "Increment" },
          "actions": { "tap": { "type": "incrementVariable", "variableName": "count", "by": 1 } }
        }
      }
    ]
  }
}
```

The DSL specification lives in its own repository,
[`appeight/app8-dsl`](https://github.com/appeight/app8-dsl): components,
styles, layout, variables, actions, navigation, collections, animations, forms,
localization, and host integration. This SDK pins the spec version it
implements in [`dsl.lock`](dsl.lock).

## Platforms

Brand content (views, labels, images, colors, fonts, corner radii, layout,
animations) renders identically on iOS and Android. System chrome follows each
platform:

| Concern | Android |
|---|---|
| Navigation bar | Material 3 top app bar |
| Back navigation | System back gesture with predictive back |
| Tab bar | Material 3 `NavigationBar` |
| Press feedback | Ripple |
| Scroll edges | Stretch overscroll |
| `system` font | Roboto |
| Sheets and alerts | Material 3 bottom sheet and `AlertDialog` |
| `openURL` in a sheet | Custom Tabs |

An app can raise its Android floor (`platforms.android.minSdk`) and override a
node per platform (`"android": { ... }`). The full comparison and the feature
matrix are in
[`app8-dsl/docs/platforms.md`](https://github.com/appeight/app8-dsl/blob/main/docs/platforms.md).

## Versioning

The SDKs ride one release train (see
[`app8-dsl/VERSIONING.md`](https://github.com/appeight/app8-dsl/blob/main/VERSIONING.md)):
Android `0.4.0` renders exactly what iOS `0.4.0` renders.

- `EngineVersion.CURRENT` (`engine-core`) is the single source of the version.
  Gradle reads it for the Maven version; the git tag, the iOS
  `EngineVersion.current`, and the app8-dsl tag all equal it.
- `dsl.lock` names the app8-dsl tag of the same train, so it always equals
  `EngineVersion.CURRENT`; a version bump changes both in one commit. Until
  that app8-dsl tag is cut, CI tests against app8-dsl `main` with a warning; a
  release requires the tag.
- `EngineVersion.MAX_SUPPORTED_DSL` is the newest DSL language version
  (app8-dsl `DSL_VERSION`) the engine renders, a separate number.
- Tags are SemVer without a `v` prefix (`0.4.0`), matching iOS.

`scripts/check-version.sh` enforces these rules in CI.

## Building and testing

```bash
./gradlew :engine-core:test          # pure JVM: models, expressions, variables, layout solver
./gradlew :engine:testDebugUnitTest  # renderer unit tests
./gradlew :engine:lint apiCheck      # lint and public API compatibility
./gradlew :sample:installDebug       # sample app on a running emulator or device
```

The doc-example tests decode every JSON example in the DSL spec; they look for
an `app8-dsl` checkout next to this repository (`scripts/fetch-dsl.sh` clones
the tag in `dsl.lock`) and skip when there is none.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 App8 Ltd.
