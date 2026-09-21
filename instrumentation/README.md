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
    id("com.oskiapps.flowgraph.instrumentation") version "0.19.0"
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.19.0")
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

## Published artifacts

- Gradle plugin ID: `com.oskiapps.flowgraph.instrumentation` (Gradle Plugin Portal)
- Runtime: `com.github.elivity:flow-graph:0.19.0` (JitPack)
