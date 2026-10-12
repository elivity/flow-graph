# Flow Graph instrumentation and debug runtime

This repository builds two independently consumable JitPack modules from the same `0.20.10` Git tag:

- `com.github.elivity.flow-graph:flowgraph-gradle-plugin:0.20.10` — Gradle/AGP instrumentation plugin implementation (plugin ID `com.oskiapps.flowgraph.instrumentation`).
- `com.github.elivity.flow-graph:flowgraph-runtime:0.20.10` — lightweight Java runtime used only in Android debug builds.

The Android Studio IDE plugin is a separate artifact in this same repository.

## Consumer setup

```kotlin
// settings.gradle.kts (in the Android app consuming Flow Graph)
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    // JitPack publishes the plugin implementation, not a Gradle plugin marker.
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

```kotlin
// app/build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.10"
}

// The plugin adds the matching flowgraph-runtime to debugImplementation automatically.
// Do not add the old com.github.elivity:flow-graph dependency manually.
```

The Gradle plugin automatically adds the version-matched runtime to the Android application's `debugImplementation` and instruments only debug variants. Do not add the runtime explicitly. Remove the old single-artifact runtime dependency when upgrading.

Optional configuration:

```kotlin
flowGraphInstrumentation {
    traceReads.set(true)
    traceColdFlows.set(true)
    traceSuspendingEmits.set(true)
    traceCompose.set(true)
    traceUiInteractions.set(true)
    // addRuntimeDependency.set(false) // advanced use: manually manage the debug runtime
}
```

If you disable automatic runtime injection, you must explicitly add the matching JitPack runtime artifact yourself. For standard use, leave it enabled.

Live tracing needs `android.permission.INTERNET` in the inspected app's merged debug manifest because the runtime connects to the local trace endpoint `127.0.0.1:50737` over `adb reverse`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

Declare it under `app/src/debug/AndroidManifest.xml` if the app does not already have the permission. No external Internet connection or permission prompt is required. Static graph discovery works without the instrumentation plugin.

## Publication

`jitpack.yml` publishes both `:flowgraph-runtime` and `:flowgraph-gradle-plugin` to the local Maven repository in a JitPack build. Gradle plugin ID resolution from JitPack needs the `pluginManagement.resolutionStrategy` above, unlike a Gradle Plugin Portal publication.

To exercise publications locally:

```bash
gradle -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```
