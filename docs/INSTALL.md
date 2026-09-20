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
    id("dev.flowgraph.instrumentation") version "0.18.5"
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.18.5")
}
```

If plugin versions are centralized in the root build file:

```kotlin
// root build.gradle.kts
plugins {
    id("dev.flowgraph.instrumentation") version "0.18.5" apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("dev.flowgraph.instrumentation")
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.18.5")
}
```

Then sync, run the debug app, and run:

```bash
adb reverse tcp:50737 tcp:50737
```
