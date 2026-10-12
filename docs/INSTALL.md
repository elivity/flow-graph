# Installation — Flow Graph 0.20.8

Flow Graph's **IDE plugin** runs in Android Studio. Its two separate Android-side modules (Gradle instrumentation and debug runtime) are built and released together from the same repository.

1. Install the IDE plugin from JetBrains Marketplace (once published), or **Settings → Plugins → Install Plugin from Disk** with your `buildPlugin` ZIP.
2. Configure JitPack as the source of the instrumentation Gradle plugin and runtime.
3. Apply the Gradle plugin to the Android **application** module.

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
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.8"
}

// The plugin adds the matching flowgraph-runtime to debugImplementation automatically.
// Do not add the old com.github.elivity:flow-graph dependency manually.
```

The Gradle plugin automatically adds `com.github.elivity.flow-graph:flowgraph-runtime:0.20.8` to `debugImplementation`. **Do not** add the old `com.github.elivity:flow-graph` dependency. The project no longer needs a local `pluginManagement { includeBuild("/absolute/path/instrumentation") }`.

The Gradle plugin can additionally be published to the Gradle Plugin Portal. If installing from there, keep `gradlePluginPortal()` and omit the custom `resolutionStrategy` mapping; keep JitPack in dependency repositories for the runtime.

For live tracing, the Android client still requires `android.permission.INTERNET` for its **localhost** TCP socket, even though no external Internet connection is used. Add to `app/src/debug/AndroidManifest.xml` if missing:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

This is not a runtime permission prompt; it need only be declared in the debug variant. Static analysis doesn't need the client runtime or permission.

Run the debug app and configure the trace port:

```bash
adb reverse tcp:50737 tcp:50737
```

For optional instrumentation flags, see [instrumentation/README.md](../instrumentation/README.md).
