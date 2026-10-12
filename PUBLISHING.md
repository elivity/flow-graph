# Publishing Flow Graph 0.20.11

The **single `elivity/flow-graph` Git repository** contains three release artifacts: an Android Studio IDE plugin, a Gradle instrumentation plugin, and the client runtime. The latter two are separate Maven modules and are versioned with the same Git tag.

| Artifact | Publication | Coordinates / identifier |
| --- | --- | --- |
| IDE plugin | JetBrains Marketplace (or installable ZIP) | Flow Graph |
| Gradle instrumentation | JitPack; optionally Gradle Plugin Portal | `com.github.elivity.flow-graph:flowgraph-gradle-plugin:0.20.11` |
| Debug runtime | JitPack | `com.github.elivity.flow-graph:flowgraph-runtime:0.20.11` |

The plugin adds the runtime to `debugImplementation` automatically. Its packaged release metadata, not the consuming app's version, chooses the compatible runtime. Runtime code has no IntelliJ/Gradle compile-time dependencies.

## 1. Validate locally

The IDE plugin continues to compile against Panda 4, and now declares compatibility through Rabbit 1 (`262.*`). The `verifyPlugin` task checks the compiled plugin against Android Studio Rabbit 1 `2026.2.1.8`. Verify the result before tagging; metadata alone cannot prove API compatibility.

```bash
./gradlew clean test buildPlugin verifyPlugin
./gradlew -p instrumentation clean build
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

Expect local Maven publications under `~/.m2/repository/com/github/elivity/flow-graph/flowgraph-runtime/0.20.11/` and `.../flowgraph-gradle-plugin/0.20.11/`. Verify both JAR/POM pairs and their contents before tagging.

## 2. Release both modules to JitPack

Push code to `https://github.com/elivity/flow-graph`, then push a matching release tag:

```bash
git tag 0.20.11
git push origin 0.20.11
```

Open `https://jitpack.io/#elivity/flow-graph` and request the `0.20.11` build. JitPack checks out the tag and executes `jitpack.yml`, publishing **both** Maven modules from one build. Do not assume the artifacts exist until JitPack reports a successful build.

The JitPack build invokes the repository's `./gradlew` (currently Gradle 9.0.0),
not a separately installed SDKMAN Gradle version. If it fails resolving a published
Gradle plugin such as `org.jetbrains.kotlin.jvm`, inspect the `--info` log for
repository/network failures before changing dependency versions. A failed JitPack
build does not publish either artifact. Prefer a new release tag for retries after
source changes rather than moving an existing public release tag.

The plugin's JitPack module contains its implementation and standard Gradle plugin descriptor, but normal Gradle `plugins {}` syntax resolves marker coordinates first. Set up `pluginManagement.resolutionStrategy` so Gradle resolves the plugin ID to the JitPack implementation module:

```kotlin
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
```

Also add `maven("https://jitpack.io")` to `dependencyResolutionManagement.repositories`, since the plugin injects a debug runtime dependency. Then the app needs only:

```kotlin
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.11"
}
```

Remove the old explicit `debugImplementation("com.github.elivity:flow-graph:...")` line when upgrading.

## 3. Optional Gradle Plugin Portal release

For users who prefer no JitPack plugin mapping, you can also publish the same instrumentation plugin ID/version to the Gradle Plugin Portal:

```bash
./gradlew -p instrumentation :flowgraph-gradle-plugin:publishPlugins --validate-only
# Supply GRADLE_PUBLISH_KEY and GRADLE_PUBLISH_SECRET:
./gradlew -p instrumentation :flowgraph-gradle-plugin:publishPlugins
```

Plugin Portal consumers can omit the JitPack `resolutionStrategy`, **but must retain JitPack in normal dependency repositories** to resolve the automatically injected runtime. The Portal is a separate distribution channel from JetBrains Marketplace.

## 4. IDE plugin release

```bash
./gradlew buildPlugin
```

For a first Marketplace release, upload the ZIP from `build/distributions/`; later releases can use `./gradlew publishPlugin -PintellijPlatformPublishingToken=...`.

**The ZIP and Git commits produced locally are not themselves a JitPack release.** Publication only occurs after pushing a matching tag and a successful remote JitPack build.
