# Flow Graph

<p align="center">
  <strong>Understand Kotlin state as a graph — then watch the graph come alive at runtime.</strong>
</p>

<p align="center">
  <a href="https://jitpack.io/#elivity/flow-graph"><img alt="JitPack" src="https://jitpack.io/v/elivity/flow-graph.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-yellow.svg"></a>
  <img alt="Android Studio plugin" src="https://img.shields.io/badge/Android%20Studio-Plugin-3DDC84?logo=androidstudio&logoColor=white">
  <img alt="Kotlin K2" src="https://img.shields.io/badge/Kotlin-K2-7F52FF?logo=kotlin&logoColor=white">
</p>

**Source version: 0.20.14** (publication status must be verified separately) · [Installation](docs/INSTALL.md) · [Architecture](docs/ARCHITECTURE.md) · [Publishing](PUBLISHING.md)

Flow Graph is an Android Studio plugin for exploring **Kotlin `Flow`, `StateFlow`, `SharedFlow`, and Jetpack Compose state**.

It combines two views of the same program:

- **static analysis** — where state can flow, which operators transform it, which collectors observe it, and which side effects can write other state;
- **live tracing** — what actually emitted, delivered, recomposed, changed, or was triggered by a UI interaction while the debug app was running.

The goal is to make large reactive Android codebases easier to reason about without mentally following chains across ViewModels, repositories, operators, collectors, helper functions, and Compose.

---

## Why Flow Graph?

A state change that looks simple in code can become difficult to follow once it passes through several layers:

```text
MutableStateFlow
      ↓
asStateFlow
      ↓
combine / map / flatMapLatest
      ↓
stateIn
      ↓
collectAsStateWithLifecycle
      ↓
@Composable
      ↓
side effect
      ↓
another StateFlow
```

Flow Graph turns those relationships into a navigable graph and overlays runtime activity on top of it.

### Compose discovery and navigation

The **Compose views** layer in **All Flows** is built independently of Flow/StateFlow discovery. All production `@Composable` declarations (excluding preview and test sources), including screens not connected to any state, appear as nodes. Calls between source composables are resolved with Kotlin K2; `NavHost`, `NavGraphBuilder` helpers, navigation destinations and `setContent` entry points are included where their declarations resolve. A navigation destination or static call means **declared in source**, not that the screen was actually shown or recomposed.

Flow-to-UI edges are added separately, only when the analyzer identifies an actual state-collection/reference site. Reading a Compose State value is distinguished from collecting or merely passing around a State object; possible relationships remain marked as possible. If Android Studio cannot resolve a call, the Composable declaration still appears, but its call edge may be omitted. The normal responsiveness caps may also truncate exceptionally large projects; the graph diagnostics report this.

To see isolated screens, keep **Compose views** enabled and **Flow-influenced UI only** disabled. **Project composables only** hides framework layout details while preserving navigation destinations and the source hierarchy.

### Highlights

- **All Flows view** — independently scan production `@Composable` functions and Flow/State properties. Every discovered Compose screen remains visible even if it collects no Flow or Compose State. Static calls connect project composables, with navigation destinations (`composable`, `dialog`, `navigation`, `bottomSheet`) and `setContent` roots represented separately. The overview uses left-to-right lanes for Compose and StateFlow connections.
- **Focused detail view** — click a node to isolate its upstream causes and downstream effects.
- **K2 semantic analysis** — resolves Kotlin symbols and Flow APIs instead of relying on text/regex matching.
- **Field-level propagation** — tracks fields through transformations such as `copy(...)`, `combine`, and mapped state where it can resolve provenance.
- **Side-effect tracing** — follows collector/helper paths that read one state and write another.
- **Live runtime overlay** — see real emits, deliveries, reads, collection boundaries, Compose state changes, and Compose body executions.
- **Runtime timeline** — pause and scrub backward through relevant runtime activity.
- **UI interaction markers** — touch, key, and scroll interactions appear as bubbles in the timeline so state changes can be correlated with user input.
- **Compose inspection** — link state to composables and inspect/render live Compose surfaces when geometry is available.
- **Source navigation** — use a node’s square source icon or double-click an edge to open the relevant source without changing node selection.
- **Large-graph tooling** — search, auto-fit, smooth frame-coalesced zoom-to-cursor, pan, magnifier, recent-activity filtering, and compact focused layouts.
- **Debug only** — the instrumentation Gradle plugin targets the Android `debug` build type; release variants are not instrumented.

---

## Namespace

Flow Graph uses the `com.oskiapps.flowgraph` namespace. The Android instrumentation plugin ID is:

```kotlin
id("com.oskiapps.flowgraph.instrumentation")
```

The Android Studio plugin, Gradle instrumentation plugin, and Android runtime live in **one repository**, but are separate build modules. The Gradle plugin and runtime are **configured for publication** as two JitPack artifacts from the same Git tag. Once published, the plugin injects the matching runtime into the app's debug variant.

## Installation

Choose the setup for the features you need. **Static Flow/StateFlow and Compose analysis requires only the Android Studio IDE plugin.** Do not modify your Android app's Gradle files or manifest for static analysis.

| Feature | What to install |
| --- | --- |
| Static Flow, StateFlow, SharedFlow and Compose hierarchy analysis | **IDE plugin only** |
| Live tracing of a running debug app | **IDE plugin + Gradle instrumentation plugin + debug runtime** |

The source tree is at **0.20.14**, but **a source version, Git tag, local ZIP, or JitPack badge is not proof that an installable release exists**. Before installing from a remote repository, verify that the corresponding version is actually available there. See [Publishing](PUBLISHING.md) for the release process.

### A. Static analysis — IDE plugin only

**Local/development installation (available from the source checkout):**

1. Build the IDE plugin with `./gradlew buildPlugin` (the IDE project uses JDK 21).
2. In Android Studio, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**.
3. Select the plugin ZIP under `build/distributions/` and restart Android Studio if prompted.

**JetBrains Marketplace (only after a release is published):** Use **Settings → Plugins → Marketplace**, search for **Flow Graph**, and install a compatible published version. Do not assume Marketplace has version `0.20.14` just because the source uses that version.

You can now use **Show Flow Graph** and **Load all flows** for static analysis, including independent production `@Composable` discovery. No JitPack repository, Gradle instrumentation plugin, runtime dependency, or `INTERNET` permission is required for this path.

### B. Live tracing — additional Android app setup

The following steps are **only for Live runtime tracing**. They assume that the **`0.20.14` JitPack build has succeeded** for *both* artifacts:

- `com.github.elivity.flow-graph:flowgraph-gradle-plugin:0.20.14`
- `com.github.elivity.flow-graph:flowgraph-runtime:0.20.14`

Check the build and artifact availability at [JitPack — elivity/flow-graph](https://jitpack.io/#elivity/flow-graph) **before** using these coordinates. If they are not published yet, use the [local development fallback](#local-development-fallback-before-jitpack-publication) below instead.

#### 1. Configure Gradle plugin and dependency repositories

**Merge** these entries into your Android app's existing **`settings.gradle.kts`**. Do **not** paste a second `pluginManagement` or `dependencyResolutionManagement` block alongside an existing one. Keep any existing repositories, repository policies, and unrelated plugin-resolution rules.

```kotlin
// settings.gradle.kts — merge into existing blocks
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

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

The `resolutionStrategy` maps the instrumentation **plugin ID** to JitPack's **implementation module**; it is not needed if you instead install a corresponding published version from the **Gradle Plugin Portal**. The runtime still needs JitPack in ordinary dependency repositories unless you publish it elsewhere.

#### 2. Apply instrumentation to the Android application module

```kotlin
// app/build.gradle.kts
plugins {
    id("com.oskiapps.flowgraph.instrumentation") version "0.20.14"
}
```

If your project centralizes plugin versions in the root build file or a version catalog, use that existing convention rather than declaring the version twice. The plugin adds the matching `flowgraph-runtime` artifact to **`debugImplementation`** automatically and instruments **debug** builds, not release builds. Do not also add the old `com.github.elivity:flow-graph` runtime dependency: it can conflict with the injected runtime.

#### 3. Allow the debug runtime to use its localhost socket

If the app does not already declare `android.permission.INTERNET`, add it to **`app/src/debug/AndroidManifest.xml`**, so release builds do not acquire the permission solely for tracing:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

Android requires this install-time permission even for TCP to **`127.0.0.1:50737`**. Flow Graph uses the socket through `adb reverse` to exchange trace data with the IDE; it does **not** need cloud access, and Android does **not** show a runtime permission prompt. If the permission is already present in the merged manifest, don't add it again.

#### 4. Connect a device or emulator

Run the **debug** app and configure the device-to-IDE connection using either method:

- **In Flow Graph:** Enable **Live** in the tool window, then click **ADB reverse**. The button appears once Live is enabled and configures the port forwarding for your connected device (prompting you to choose one if necessary).
- **In a terminal:** Run the equivalent command below, then enable **Live** in the Flow Graph tool window:

  ```bash
  adb reverse tcp:50737 tcp:50737
  ```

You only need **one** of these methods; the button performs the same forwarding as the command. Without the instrumentation plugin/runtime and socket permission, **static analysis still works**, but Live trace events will not arrive.

### Local development fallback before JitPack publication

If the `0.20.14` JitPack artifacts are not available yet, you can build and publish both modules to your **local Maven repository** from a Flow Graph source checkout:

```bash
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

Then add **`mavenLocal()` before remote repositories** in both `pluginManagement.repositories` and `dependencyResolutionManagement.repositories` in your app's `settings.gradle.kts`. Keep the same `resolutionStrategy` and `plugins` declaration shown above; the local artifacts use the same version and Maven coordinates. You do **not** need a machine-specific `includeBuild("/home/.../instrumentation")` path for this approach. Re-publish locally when you change the instrumentation modules.

### Build compatibility and requirements

The IDE plugin is **compiled against Android Studio Panda 4 (2025.3.4.7 / IntelliJ build 253)** for backwards compatibility, with a declared compatibility range from **`253.32098` through `262.*`** (Rabbit 1 / 2026.2.1). CI also runs the IntelliJ Plugin Verifier against **Rabbit 1 2026.2.1.8**, but that does not replace a real Android Studio smoke test. **JDK 21** is used to build the IDE plugin; the repository's Gradle wrapper is **9.0.0**. The instrumentation plugin compiles against the **Android Gradle Plugin 9.3.2 API** with **JDK 17**, and JitPack runs the repository's **Gradle 9.0.0 wrapper** to build the Android-side modules. These are **project configuration values, not a guarantee that other Android Studio, AGP, Gradle, or JDK combinations have been tested**. Verify compatibility with your app's toolchain before relying on Live instrumentation.

**Android Studio Rabbit 1 (2026.2.1):** Earlier Flow Graph builds with `untilBuild = "261.*"` are rejected during the IDE upgrade. Flow Graph `0.20.9` and later extend this limit to `262.*`. Build the new IDE plugin with `./gradlew buildPlugin`, then install the ZIP from `build/distributions/` via **Settings → Plugins → Install Plugin from Disk** before upgrading, or after the upgrade if necessary. This changes the **IDE plugin**, not the Recetas app or its Gradle dependencies. Check compatibility using `./gradlew test buildPlugin verifyPlugin`; CI verifies against Rabbit 1 stable `2026.2.1.8`, and manual UI testing remains important.

For advanced options, see [instrumentation/README.md](instrumentation/README.md) and [the detailed installation guide](docs/INSTALL.md).

---

## Usage

### Inspect a Flow directly from code

Place the caret on a Kotlin property backed by a supported Flow or Compose state, then either:

- right-click → **Show Flow Graph**; or
- press **Ctrl+Alt+G**.

Flow Graph opens the tool window and builds a semantic graph around that state.

### All Flows — see the project-wide picture

Use **Load all flows** to scan production project sources and build an overview of discovered `Flow`, `StateFlow`, `SharedFlow`, and Compose state relationships. Test source roots are excluded. All Flows also builds a bounded project Compose topology. The overview deliberately does **not** use shared Flow or Compose declarations as global DAG junctions: each screen/consumer is shown as a hierarchical horizontal lane, and shared nodes may be repeated visually while still mapping back to the same canonical source.

With **Show operators** off (the normal architectural view), the state side of every Compose lane is drawn as a compact **StateFlow dependency graph**. FlowGraph de-duplicates the relevant states inside that screen lane, connects them with semantic state transitions, places the values actually observed by Compose at the graph edge, and then lets them trickle into the Compose tree through short `updates UI` arrows. When **Runtime branches only** is enabled, the graph keeps the observed coroutine machinery too (`map`, `combine`, collectors, behaviors and write sites), so a runtime branch can read `StateFlow -> operator -> writer -> StateFlow -> collector -> Compose` without duplicating shared nodes.

### Horizontal or vertical All Flows layout

When **Circuit** is disabled, use the **Layout** selector in the graph viewport controls to switch each project-overview cluster between **Horizontal** and **Vertical**. The clusters themselves always remain stacked one below another in the same order. Horizontal reads the causal network left-to-right as `upstream Flow/state -> transforms/collectors/writes -> observed state/stage -> Compose`. Vertical transposes that same per-cluster topology into top-to-bottom `Flow/state -> Compose` while keeping node cards upright. Because the same dependency ranks are transposed rather than recomputed as an unrelated layout, branch/fan-in relationships and the right-side cluster navigator stay stable between orientations. Focused/detail graphs intentionally remain horizontal so causal expansion still reads upstream-left and downstream-right.

The graph viewport menu is width-responsive. Visibility filters, zoom/search controls and the layout selector share a wrapping toolbar, so narrowing the IDE/tool window moves controls onto additional rows instead of clipping them or forcing part of the menu off-screen.

A **Clusters** navigator is shown on the right edge of **All Flows** when the overview contains multiple semantic lanes/components. Its buttons use screen names directly and add semantic/source ownership when a lane root is otherwise generic (for example `UserDataRepository — collect` instead of only `collect`). The navigator follows the viewport automatically: panning or scrolling highlights the cluster currently under the camera, scrolls that bookmark into view in the rail, and highlights the same cluster boundary in the main graph. Clicking a bookmark remains a bookmark-style jump: FlowGraph fits that cluster to a readable zoom and centers it without converting it into a focused causal detail graph. The navigator is hidden in focused detail views and regenerated whenever visibility filters or overview packing rebuild the clusters.

The live **UI render** pane is also detail-only. All Flows keeps the graph viewport and ordinary details pane; selecting a node opens the focused detail graph and makes the UI render pane available for that inspection.

Compose topology also follows project lazy-layout DSL helpers that are not themselves `@Composable`. For example, a source helper such as `fun LazyStaggeredGridScope.newsFeed(...) { items(...) { NewsResourceCardExpanded(...) } }` is represented as `LazyVerticalStaggeredGrid -> newsFeed -> items -> NewsResourceCardExpanded`. The helper is static topology only; recomposition counts remain attached to the actual source `@Composable` descendants.

The **View settings** menu contains visibility checkboxes for: **Compose state** (**off by default**), **Coroutine Flow/StateFlow** (on), and **Compose views** (on). They apply consistently to both **All Flows** and focused/detail views. Toggling one rebuilds the currently visible graph and re-packs the remaining content, so hidden layers never leave empty layout holes or reappear when a node is focused.

Use **Flow-influenced UI only** as a structural impact lens when you only want Compose nodes that are causally reached by a coroutine `Flow`/`StateFlow`/`SharedFlow`. FlowGraph follows state-to-state propagation (including an intermediate Compose State) to an `UPDATES_COMPOSE` relationship, removes unrelated Compose branches, then rebuilds, re-packs, and fits the graph. Composition alone does not imply Flow influence: children are not retained merely because an observing parent composes them. The filter still works when the **Coroutine Flow/StateFlow** layer itself is hidden, which is useful for a Compose-only list of affected views.

Use **Project composables only** when you want the Compose architecture without AndroidX/framework layout primitives. Nodes such as `Column`, `Row`, `Text`, `Surface`, `Offset`, and other synthetic Compose UI call sites are removed, but the graph contracts through them so project composables stay connected to their nearest project-composable ancestors/descendants. The same filter applies to All Flows and detail view and triggers a compact relayout.

Tooling-only Compose previews are excluded from the architecture graph: functions annotated with `@Preview` (including common preview-style/multi-preview annotation names) are not admitted as production Compose nodes or roots.

Compose branches are laid out as independent horizontal composition lanes from left to right. Source composables stay as inspectable nodes, while visual AndroidX Compose call sites such as `Column`, `LazyVerticalGrid`, `Button`, and `Text` can appear between them. A lane can therefore read like `ForYouScreen → LazyVerticalGrid → TopicCard → Button → Text`. Reused composables are allowed to appear more than once: each visual copy maps to the same source declaration, but stays local to its lane instead of creating long cross-screen edges. Common infrastructure such as `CompositionLocalProvider`, `…Theme`, `Surface`, `Scaffold`, and `NavHost` is treated as optional lane-local context rather than a mandatory global root.

Each lane also carries its own local Flow/State influence prefix, matching the focused detail view: upstream state and operators are repeated immediately to the left of the screen they can recompose. For example, a lane can read `MutableStateFlow → StateFlow → collectAsStateWithLifecycle → ForYouScreen → LazyVerticalGrid → …`. If the same state feeds several screens, the overview may repeat that state beside each screen rather than drawing long cross-lane connections. A `…Route` that owns `collectAsState*` can remain omitted as the visual root while its update is projected to the nearest visible screen descendant.

This view is useful when you do not yet know which state is responsible for a behavior, or when you want to understand how state crosses feature/module boundaries.

<p align="center">
  <img src="docs/media/all-flows.gif" alt="Flow Graph All Flows view" width="100%">
</p>

> **GIF slot:** replace `docs/media/all-flows.gif` with your real All Flows recording. The README already points to that path.

### Detail Flow — isolate one causal chain

Click a node in the overview to switch into a focused graph. Within the focused graph, clicking another node inspects it without moving the viewport. The detail view keeps the selected state and its causal neighborhood visible while removing unrelated graph noise.

From there you can:

- expand **Causes ↑** to explore upstream state;
- expand **Affected ↓** to follow downstream impact;
- toggle operators, reads, possible relationships, and library detail;
- click an edge to highlight only that rendered flow/lane path; duplicated copies of the same Flow variable in other lanes stay dimmed; double-click the edge to jump to its source call site;
- inspect field provenance, collectors, side-effect writes, and runtime values in the details pane.

<p align="center">
  <img src="docs/media/detail-flow.gif" alt="Flow Graph focused Detail Flow view" width="100%">
</p>

> **GIF slot:** replace `docs/media/detail-flow.gif` with your real focused/detail-flow recording.

### Quit and release resources

Choose **Quit** in the Flow Graph toolbar to **stop the plugin's active session** rather than just hide its window. Quit disconnects the local TCP Live server and clients, shuts down its background worker pool, cancels active All Flows analysis, stops the timeline/zoom/UI refresh timers, unregisters listeners, and clears cached graphs, runtime history and Compose preview images.

The tool window closes and leaves a lightweight **Start Flow Graph** launcher if you reopen it from **View → Tool Windows → Flow Graph**. You can also use **Show Flow Graph** on a source declaration to start a new session automatically. The next session starts with no previous graph or tracing history.

Quit does **not** uninstall the IDE plugin, stop the Android app, remove its debug instrumentation, or uninstall the debug runtime. It also does not undo device-side `adb reverse` configuration; disconnect the device or use `adb reverse --remove tcp:50737` if you need to remove that mapping. Hiding the tool window without using Quit does not release the session resources.

### Live trace

Live-specific controls are hidden until **Live** is enabled. With **Live** enabled, runtime events are overlaid on the same graph rather than shown in a separate profiler.

Depending on configuration, Flow Graph can show:

```text
emit           confirmed StateFlow / SharedFlow write
emit-request   suspending SharedFlow.emit(...) request
collect        collection boundary
 deliver       value delivered through a FlowCollector
read           StateFlow / Compose state read
state-change   effective Compose state change
compose        real Compose body execution
ui             touch / key / scroll interaction
```

Only events relevant to the currently loaded graph are retained for the graph/timeline, which keeps large globally instrumented apps manageable.

### Timeline

The runtime timeline lets you correlate state changes with what happened in the app:

1. run the app with **Live** enabled;
2. interact with the UI;
3. pause the timeline;
4. drag the cursor backward/forward;
5. inspect the graph and details at that point in time.

UI interactions are shown as small bubbles so you can visually line up a tap/scroll/key event with the state changes and Compose work that followed it.

### Current UI / Compose inspection

Current UI targets the **most specific substantial visible project composable**, rather than simply the largest/root composable. This means switching to another tab or nested screen should focus that active tab/screen when it is represented in the graph. Very small child controls are deliberately excluded from taking over the selection.


Current UI diagnostics are also retained in the Live **ⓘ** panel. If Current UI fails or times out, FlowGraph automatically opens **ⓘ** and keeps the full runtime diagnostic/counter message there while leaving only a short status in the header.


When a connected debug app exposes inspectable Compose data, **Current UI** can locate a currently visible composable and connect it back to the graph.

Compose rendering is best-effort: a composable that owns real layout geometry can be captured, while effect-only composables (for example a function that only hosts a `LaunchedEffect`) may correctly have no renderable bounds.

---

## Optional instrumentation configuration

The defaults enable the complete debugging experience. You can reduce runtime overhead when you only need part of it:

```kotlin
flowGraphInstrumentation {
    enabled.set(true)

    // StateFlow / Compose reads
    traceReads.set(true)

    // Flow.collect / FlowCollector.emit boundaries
    traceColdFlows.set(true)

    // Suspended MutableSharedFlow.emit(...) requests
    traceSuspendingEmits.set(true)

    // Compose state + composable execution tracing
    traceCompose.set(true)

    // Touch / key / scroll markers for the timeline
    traceUiInteractions.set(true)
}
```

For example, when investigating only StateFlow writes you can disable cold-flow and Compose tracing to reduce instrumentation work.

---

## What Flow Graph understands

### State types

- `Flow`
- `StateFlow` / `MutableStateFlow`
- `SharedFlow` / `MutableSharedFlow`
- Compose `State` / `MutableState`
- primitive Compose state (`IntState`, `LongState`, `FloatState`, `DoubleState` and mutable variants)
- delegated Compose state created through common state factories

### Common Flow operations

Flow Graph recognizes common propagation APIs including:

`map`, `mapLatest`, `mapNotNull`, `filter`, `transform`, `combine`, `combineTransform`, `zip`, `merge`, `flatMapLatest`, `flatMapConcat`, `flatMapMerge`, `scan`, `runningFold`, `stateIn`, `shareIn`, `distinctUntilChanged`, `debounce`, `sample`, `onEach`, `catch`, `retry`, `buffer`, `conflate`, `flowOn`, `take`, `drop`, and related operators.

Collectors include `collect`, `collectLatest`, `launchIn`, `collectAsState`, `collectAsStateWithLifecycle`, `first`, `single`, `toList`, `count`, `reduce`, `fold`, `produceIn`, and related terminal operations.

Writes include `.value =`, `update`, `getAndUpdate`, `updateAndGet`, `emit`, and `tryEmit`.

### Compose state factories

Static analysis recognizes common factories including:

`mutableStateOf`, `derivedStateOf`, `rememberUpdatedState`, `produceState`, `mutableIntStateOf`, `mutableLongStateOf`, `mutableFloatStateOf`, and `mutableDoubleStateOf`.

---

## Static analysis vs. runtime truth

Flow Graph intentionally keeps these concepts separate:

- **Static edges** answer: *what can affect what according to the code?*
- **Runtime activity** answers: *what actually happened during this run?*

Some static relationships are marked **possible** rather than definite. This is important for imperative helper functions, branches, DI/runtime dispatch, and other cases where a read and write can be related in code without proving that the relationship executed on a particular run.

The runtime overlay never turns an unrelated runtime event into a causal edge just because the timestamps happen to be close; observed activity is mapped back onto relationships already supported by the graph.

---

## Advanced graph behavior

### Filtering inferred connections

Use **Possible connections** in the graph header to show/hide inferred read→write causality. Turning it off structurally rebuilds and repacks the graph instead of merely hiding dashed lines, so possible-only helper nodes and the empty space they occupied disappear as well. **Expand possible** in View settings separately controls recursive traversal of visible possible edges in a focused causal cone. With it off, one-hop possible context remains visible without traversal; turning it on retains that context and adds reachable possible dependencies up to the selected depth. It never removes nodes displayed with the toggle off.


### Compose runtime identity

Source composable activity uses `@compose2|package|file|functionName|sourceLine`. The source line prevents overloaded or repeated same-name composables in the same Kotlin file from sharing one runtime identity. Legacy `@compose|...` events are still accepted when they are unambiguous; rebuild the debug APK to get the precise v2 identity.

### Live Compose geometry compatibility

Live **Current UI / Render UI** capture resolves `LayoutInfo` ownership across Compose UI versions using the current `LayoutInfo_androidKt.getView(LayoutInfo)` tooling bridge, the recent `opaqueOwner` API, and the older internal owner fallback. This keeps live composable bounds/capture working when the Compose tooling owner API changes between releases.


### Live Compose Current UI geometry compatibility

- Current UI no longer discards valid LayoutInfo geometry merely because the owning Android View cannot be resolved immediately.
- Layout owner lookup now also follows `LayoutCoordinates -> NodeCoordinator -> LayoutNode -> owner`, with Activity decor/AndroidComposeView fallback.
- Current UI failures now report diagnostic counts for composition groups, group-key/source matches, LayoutInfo nodes, coordinates, positions, owners, and geometry matches.


### Concise overview

`Concise overview` is enabled by default for **All Flows**. It treats the overview as an architecture map rather than a literal call graph: duplicate Compose call sites are folded, read-only observer leaves are grouped by owner, long causal captions are reduced to semantic verbs, and StateFlow propagation is arranged as a compact state machine feeding the Compose hierarchy. Compose subtrees stay visible in the overview rather than being replaced by descendant summary controls. Selecting a normal node switches to the focused graph, where the complete paths/call sites are still available. Disable the checkbox at any time to see the literal overview.

### Runtime-observed branches

Use **Runtime branches only** in **All Flows** to rebuild the overview from branches that have concrete runtime evidence since the last **Clear runtime**. FlowGraph now keeps the causal coroutine graph itself—StateFlow/SharedFlow states, transforms, collectors, behaviors, exposures and write sites—rather than collapsing runtime evidence to state boxes only. Field-detail nodes are contracted into semantic edges such as `writes state`/`produces state`, and retained Flow/state nodes keep their static `updates UI` endpoints plus the Compose ancestor chain, so you can see both what triggered what and which Compose view that live branch can influence. Inactive Flow and Compose sibling branches are omitted. The filter is session-persistent rather than a 2.5-second hot window: once a branch has executed it remains visible until runtime history is cleared. Selecting a node still opens the complete detail graph.


---

## Current limitations

Flow Graph is a debugging and comprehension tool, not a coroutine virtual machine or a full program verifier.

- Reflection-heavy or highly dynamic code may not resolve statically.
- Arbitrary custom Flow operators may be shown conservatively or omitted when their semantics cannot be established.
- Runtime instrumentation adds overhead and should remain debug-only.
- Compose source/group information depends on compiler/runtime information available in the app and Android Studio version.
- Not every composable has renderable geometry; effect-only composables can be inspectable without producing a UI capture.
- Static "possible" relationships are intentionally not presented as proof of runtime execution.
- Direct Compose State reads are attributed to the enclosing composable. A read that exists only inside a later non-composable callback/effect lambda (for example an `onClick` callback) can therefore be conservatively over-attributed as a recomposition dependency; distinguishing every callback lambda from composable content requires deeper function-type/call-site analysis.
- Flow values returned from nested lambdas of higher-order operators (for example an arbitrary inner Flow selected inside a complex `flatMapLatest` lambda) may be incomplete when the dependency is not a direct receiver or direct Flow-valued call argument.
- Exact Flow/State classification is based on resolved coroutine/Compose state types; unusual custom subtypes/typealiases may be omitted rather than guessed.

If Flow Graph cannot establish a relationship with reasonable confidence, the preferred behavior is to leave it unmatched rather than invent a connection.

---

## Development

The current IDE plugin targets the Android Studio 253 platform and uses the Kotlin K2 Analysis API.

Build the Android Studio plugin:

```bash
./gradlew clean test buildPlugin
```

The installable ZIP is created under:

```text
build/distributions/
```

Build the Android-side instrumentation and runtime:

```bash
./gradlew -p instrumentation clean build
```

Test both JitPack module publications locally:

```bash
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal \
  :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

This checks local Maven artifacts; it does **not** publish to JitPack. Push the `0.20.14` tag to GitHub to make the release buildable there.

Release/publishing notes are in [PUBLISHING.md](PUBLISHING.md).

---

## License

Flow Graph is released under the [MIT License](LICENSE).

Contributions and bug reports are welcome at [github.com/elivity/flow-graph](https://github.com/elivity/flow-graph).
