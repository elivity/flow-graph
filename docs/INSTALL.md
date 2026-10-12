# Installation

1. In Android Studio open **Settings -> Plugins -> Marketplace**, search for **Flow Graph**, and install it.
2. Add JitPack to dependency repositories.
3. Add the Flow Graph instrumentation plugin and debug runtime to the Android application module.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.5"
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.20.5")
}
```

If plugin versions are centralized in the root build file:

```kotlin
// root build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.5" apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation")
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.20.5")
}
```

For live tracing, the inspected Android app must declare `android.permission.INTERNET`, even though Flow Graph connects only to the local TCP endpoint (`127.0.0.1:50737`) via `adb reverse`. If the app doesn't already have the permission, add this to `app/src/debug/AndroidManifest.xml` (keeping it debug-only):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

No runtime permission prompt or external Internet service is required. Without this permission, Android may block the socket and prevent the **Live** view from receiving events; static analysis still works.

Then sync, run the debug app, and run:

```bash
adb reverse tcp:50737 tcp:50737
```
