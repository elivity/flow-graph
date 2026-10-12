# Installation — Flow Graph 0.20.9

**Static analysis and Live tracing have different installation requirements.** This is the detailed companion to the [README installation section](../README.md#installation). Version `0.20.9` refers to the checked-out source; remote artifacts must be verified before installation.

## Static Flow/StateFlow and Compose analysis

Only the **Flow Graph Android Studio IDE plugin** is needed. **Do not change the Android app's `settings.gradle.kts`, `build.gradle.kts`, or manifest** for static analysis.

From the Flow Graph source checkout:

```bash
./gradlew buildPlugin
```

In Android Studio, use **Settings → Plugins → ⚙ → Install Plugin from Disk…**, select the ZIP under `build/distributions/`, and restart if prompted. **Marketplace → Flow Graph → Install** is an alternative *only once a compatible release is actually published there*.

The IDE plugin finds production `@Composable` functions independently of state/Flow discovery. In **Load all flows**, enable **Compose views** and disable **Flow-influenced UI only** if you want unconnected screens too.

## Live tracing: additional Gradle setup

Live tracing requires the IDE plugin **plus** two independently published artifacts from the same version:

- `com.github.elivity.flow-graph:flowgraph-gradle-plugin:0.20.9`
- `com.github.elivity.flow-graph:flowgraph-runtime:0.20.9`

First confirm that the tagged `0.20.9` [JitPack build](https://jitpack.io/#elivity/flow-graph) succeeded. **A source ZIP or local Git tag does not publish these artifacts.** The Gradle plugin adds the runtime to `debugImplementation` automatically; the app must not add it manually.

### 1. Merge repository configuration into the existing app settings

If you already have `pluginManagement` or `dependencyResolutionManagement` blocks, **merge** these entries into them rather than adding duplicate blocks:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.oskiapps.flowgraph.instrumentation") {
                useModule("com.github.elivity.flow-graph:flowgraph-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

The plugin-resolution mapping is for the JitPack implementation module; Gradle Plugin Portal consumers do not need it once the plugin is published there. JitPack remains necessary for the runtime unless it is published to another configured Maven repository.

### 2. Apply the plugin in the Android application module

```kotlin
// app/build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.9"
}
```

Or use the project's existing centralized plugin version convention. Remove the **old** explicit `debugImplementation("com.github.elivity:flow-graph:...")` line if present.

### 3. Grant the debug socket permission

If your app does not already have `android.permission.INTERNET`, add it to `app/src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

The client uses `127.0.0.1:50737` over a TCP socket; **Android still requires `INTERNET` for localhost**. It is not an external Internet service and does not trigger a runtime permission dialog. Static analysis needs neither the client runtime nor the permission.

### 4. Start the debug app and connect tracing

```bash
adb reverse tcp:50737 tcp:50737
```

Enable **Live** in Flow Graph. Release variants are not instrumented.

## Local fallback when JitPack is not published

From a Flow Graph checkout, build both versioned modules into local Maven:

```bash
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

Add `mavenLocal()` **before remote repositories** to both `pluginManagement.repositories` and `dependencyResolutionManagement.repositories`. Use the same `resolutionStrategy` and plugin declaration above. No absolute `includeBuild()` path is necessary.

## Compatibility information

Configured build targets (not a guarantee of broad compatibility): **IDE plugin** — compiled against Android Studio Panda 4 `2025.3.4.7`, declared compatible through Rabbit 1 `2026.2.1` (IntelliJ build `262.*`), with Rabbit binary verification in CI, JDK 21, Gradle wrapper 9.0.0; **instrumentation plugin** — AGP API `9.3.2`, JDK 17; **JitPack instrumentation build** — Gradle 9.4.1. Verify your Android project's Gradle/AGP compatibility before enabling Live tracing.

See [instrumentation/README.md](../instrumentation/README.md) for advanced tracing options and [PUBLISHING.md](../PUBLISHING.md) for release steps.


## Android Studio Rabbit 1 (2026.2.1)

Flow Graph `0.20.9` expands the IDE plugin's declared IntelliJ compatibility from `261.*` to `262.*` while retaining the minimum supported Panda platform (`253.32098`). To validate the new platform APIs before installing, build and verify against Rabbit 1:

```bash
./gradlew clean test buildPlugin verifyPlugin
```

The `verifyPlugin` task checks the packaged plugin against Android Studio `2026.2.1.8`. It may download the IDE and verifier on first use. Run a manual smoke test for the tool window, **Load all flows**, Compose discovery, node selection, and Live tracing after upgrading Android Studio.

Older plugin builds with `untilBuild = "261.*"` remain incompatible with Rabbit at the metadata level: install the `0.20.9` IDE plugin ZIP or upgrade it from Marketplace once that release is published. The Android app's Gradle instrumentation and debug runtime remain separate artifacts.
