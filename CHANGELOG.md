# Changelog

## 0.18.5

- Fixed Gradle isolated-projects compatibility for the instrumentation included build.
- Subproject build scripts no longer access `rootProject.file(...)`; release metadata is loaded from the repository file via each subproject's own `ProjectLayout`.
- Legacy targeted instrumentation no longer accesses the consumer `rootProject` model either; it locates the nearest Gradle build root by walking the filesystem from the application module.
- Keeps the default global instrumentation mode unchanged.

## 0.18.3

- Fixed Kotlin DSL compilation of both instrumentation subprojects when used as an included build.
- Avoids the Gradle `java` extension shadowing the `java` package by importing `java.util.Properties` explicitly.
- Replaced the overloaded `use(::load)` method reference with an explicit `load(input)` call for reliable Kotlin DSL type resolution.

## 0.18.2

- Reworked the repository README with a full quick start, feature overview, usage guide, limitations, and dedicated All Flows / Detail Flow GIF slots.
- Runtime distribution moved from Maven Central to JitPack, matching the `elivity/ffmpegimporter` release style.
- Runtime coordinate is `com.github.elivity:flow-graph:0.18.2`.
- Added JitPack build configuration using tagged releases and `publishToMavenLocal`.
- Added the MIT license.
- Gradle instrumentation plugin remains on the Gradle Plugin Portal so consumers can use the normal `plugins {}` DSL.
- Android Studio plugin remains distributed through JetBrains Marketplace.

## 0.18.1

- Reworked distribution to use normal public package ecosystems.
- Android Studio plugin is prepared for JetBrains Marketplace publishing.
- `dev.flowgraph.instrumentation` is prepared for the Gradle Plugin Portal.
- `dev.flowgraph:flowgraph-runtime` is prepared for Maven Central.
- Consumer setup is now just the Marketplace plugin, one Gradle plugin declaration, and one `debugImplementation` dependency.
- Removed the copied included-build/setup-script distribution model.
- Runtime is no longer silently injected using the consuming project's version; the matching runtime dependency is explicit and visible in the app build.

## 0.18.0

- Distribution preparation release.
