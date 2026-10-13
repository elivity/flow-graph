# Flow Graph

<p align="center">
  <a href="https://jitpack.io/#elivity/flow-graph"><img alt="JitPack" src="https://jitpack.io/v/elivity/flow-graph.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-yellow.svg"></a>
  <img alt="Android Studio plugin" src="https://img.shields.io/badge/Android%20Studio-Plugin-3DDC84?logo=androidstudio&logoColor=white">
  <img alt="Kotlin K2" src="https://img.shields.io/badge/Kotlin-K2-7F52FF?logo=kotlin&logoColor=white">
</p>

**See where your Android app's state comes from, where it goes, and what it changes.**

Following a value through ViewModels, Flow operators, collectors, and Compose screens can take hours. **Flow Graph** brings those relationships into a navigable graph inside Android Studio, so you can explore a dependency without jumping between files.

It uses **Kotlin K2 static analysis** to discover `Flow`, `StateFlow`, `SharedFlow`, and Compose relationships—including Composables with no Flow connection. Select a node to explore causes and effects, or jump straight to the source. For a running debug app, optional **Live tracing** adds observed state events, Compose activity, and a timeline. Static possibilities and runtime observations remain distinct.

## See it in action

### All Flows — find the bigger picture

Explore state propagation, Compose screens, and navigation relationships across the project.

<p align="center">
  <img src="docs/media/all-flows.gif" alt="Flow Graph project-wide All Flows overview" width="100%">
</p>

### Detail Flow — follow a change

Focus on one node to trace upstream causes, downstream effects, and the code behind each connection.

<p align="center">
  <img src="docs/media/detail-flow.gif" alt="Flow Graph focused Detail Flow graph" width="100%">
</p>

## Installation

### Static analysis — install the Android Studio plugin

No changes to your Android app are required.

1. **From source:** With JDK 21, run `./gradlew buildPlugin` in the Flow Graph repository.
2. In Android Studio, choose **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the ZIP in `build/distributions/`.
3. Open a Kotlin file and use **Show Flow Graph**, or select **Load all flows** in the tool window.

When a compatible release is available on **[JetBrains Marketplace](https://plugins.jetbrains.com/)**, you can install it from Android Studio's Marketplace tab instead.

### Live tracing — optional debug-app setup

To see runtime events, you also need the **Flow Graph Gradle instrumentation plugin**. It automatically adds the matching runtime to your app's **debug** build; release builds are not instrumented.

1. Follow the [Live tracing installation guide](docs/INSTALL.md#live-tracing-additional-gradle-setup) to configure JitPack and apply `com.oskiapps.flowgraph.instrumentation` version **`0.20.14`**. Verify that the tagged artifacts are available on [JitPack](https://jitpack.io/#elivity/flow-graph) first.
2. Ensure the debug app has `android.permission.INTERNET` for its localhost tracing socket (add it to `app/src/debug/AndroidManifest.xml` if needed).
3. Run the debug app, enable **Live** in Flow Graph, and click **ADB reverse**. Alternatively, run `adb reverse tcp:50737 tcp:50737` in a terminal.

The socket connects to the IDE over ADB, **not a cloud service**. Static analysis works without Live tracing or any app-side setup.

## Further reading

[Installation & troubleshooting](docs/INSTALL.md) · [Architecture & advanced features](docs/ARCHITECTURE.md) · [Graph semantics & limitations](docs/GRAPH_SEMANTICS.md) · [Instrumentation options](instrumentation/README.md) · [Publishing](PUBLISHING.md) · [Changelog](CHANGELOG.md)

## License

[MIT License](LICENSE) · [Source, issues, and contributions](https://github.com/elivity/flow-graph)
