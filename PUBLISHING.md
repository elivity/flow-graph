# Publishing Flow Graph

Flow Graph uses three standard distribution channels:

| Component | Public distribution |
| --- | --- |
| Android Studio IDE plugin | JetBrains Marketplace |
| `com.oskiapps.flowgraph.instrumentation` | Gradle Plugin Portal |
| debug runtime | JitPack from `elivity/flow-graph` |

The repository is MIT licensed. JitPack removes the Maven Central namespace, signing, staging, and GPG-key work for the runtime. JitPack builds a tagged Git commit on demand and serves the artifact from its Maven repository.

The instrumentation **Gradle plugin stays on the Gradle Plugin Portal intentionally**. A plugin resolved through the normal `plugins { id(...) version(...) }` DSL needs a plugin marker artifact under the plugin ID coordinates. JitPack serves GitHub builds under `com.github.<owner>...`; putting only the plugin implementation on JitPack would therefore require consumers to add a custom `pluginManagement.resolutionStrategy` mapping. Keeping the plugin on the Portal preserves the simple install syntax.

## Consumer coordinates

For release `0.19.0`:

```kotlin
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.19.0"
}

dependencies {
    debugImplementation("com.github.elivity:flow-graph:0.19.0")
}
```

The consumer also adds:

```kotlin
maven { url = uri("https://jitpack.io") }
```

to `dependencyResolutionManagement.repositories`.

## Build checks

```bash
./gradlew clean test buildPlugin
./gradlew -p instrumentation clean build
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  -Pgroup=com.github.elivity \
  -Partifact=flow-graph \
  -Pversion=0.19.0
```

## 1. Runtime release on JitPack

There is no upload task and no JitPack credential. Push the source to:

`https://github.com/elivity/flow-graph`

Then create and push a tag matching the public version:

```bash
git tag 0.19.0
git push origin 0.19.0
```

Open `https://jitpack.io/#elivity/flow-graph`, select `0.19.0`, and click **Get it** once if you want to force the first build immediately. Otherwise the first Gradle dependency request triggers it.

`jitpack.yml` runs only the runtime publication and passes JitPack's `$GROUP`, `$ARTIFACT`, and `$VERSION` values into Gradle, so the produced artifact matches:

`com.github.elivity:flow-graph:0.19.0`

## 2. Publish the Gradle instrumentation plugin

Create a Gradle Plugin Portal account/API key and expose the standard credentials:

```bash
export GRADLE_PUBLISH_KEY='...'
export GRADLE_PUBLISH_SECRET='...'
```

Validate:

```bash
./gradlew -p instrumentation :flowgraph-gradle-plugin:publishPlugins --validate-only
```

Publish:

```bash
./gradlew -p instrumentation :flowgraph-gradle-plugin:publishPlugins
```

This is what makes the normal consumer syntax work without custom plugin-resolution rules:

```kotlin
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.19.0"
}
```

## 3. Publish the Android Studio plugin

Build it:

```bash
./gradlew buildPlugin
```

For the first JetBrains Marketplace release, upload the ZIP from `build/distributions/`. For later releases:

```bash
./gradlew publishPlugin -PintellijPlatformPublishingToken='...'
```

## Release order

For each version, use the same version everywhere. A practical order is:

1. push the Git tag and verify the JitPack runtime build;
2. publish `com.oskiapps.flowgraph.instrumentation` with the same version;
3. publish the Marketplace plugin with the same version.
