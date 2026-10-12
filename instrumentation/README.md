# Flow Graph runtime instrumentation

The Android-side integration is split into a normal Gradle plugin plus a debug-only runtime.

## Consumer setup

Add JitPack to dependency repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.6"
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.20.6")
}
```

Optional:

```kotlin
flowGraphInstrumentation {
    traceReads.set(true)
    traceColdFlows.set(true)
    traceSuspendingEmits.set(true)
    traceCompose.set(true)
    traceUiInteractions.set(true)
}
```

The Gradle plugin instruments only the Android `debug` variant. The runtime dependency should therefore also use `debugImplementation`.

Live tracing requires `android.permission.INTERNET` in the client app's merged Android manifest, because the debug runtime opens a TCP connection to the local trace server on `127.0.0.1:50737` (via `adb reverse`). If it is not already present, declare it under `app/src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

This does not contact the public Internet, needs no runtime permission prompt, and does not apply to static analysis. Use the debug manifest to avoid introducing an otherwise unnecessary permission into release builds.

## Published artifacts

- Gradle plugin ID: `com.oskiapps.flowgraph.instrumentation` (Gradle Plugin Portal)
- Runtime: `com.github.elivity:flow-graph:0.20.6` (JitPack)
