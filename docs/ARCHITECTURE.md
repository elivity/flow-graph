# Flow Graph architecture (0.20.14)

Flow Graph has two complementary ways of understanding an Android app: **static analysis** describes relationships supported by its source, and optional **Live tracing** shows events observed in a running debug build. They share one graph UI, but an observed event is not automatically proof that two states are causally related.

For a quick introduction and installation, start with the [README](../README.md). For exact connection confidence rules, see [Graph semantics](GRAPH_SEMANTICS.md).

## Project structure and distribution

The repository contains three separately built components:

| Component | Source | Distribution |
| --- | --- | --- |
| Android Studio IDE plugin | `src/main/kotlin/com/oskiapps/flowgraph/` | Plugin ZIP from `buildPlugin`, optionally JetBrains Marketplace |
| Gradle instrumentation plugin | `instrumentation/flowgraph-gradle-plugin/` | JitPack Maven module (`com.oskiapps.flowgraph.instrumentation` plugin ID) |
| Android debug runtime | `instrumentation/flowgraph-runtime/` | JitPack Maven module, injected into `debugImplementation` |

Both Maven modules are versioned from the same Git tag. Static analysis only requires the IDE plugin. Live tracing needs the Gradle plugin and runtime; publishing to JetBrains Marketplace does **not** publish those Maven modules. The old manual runtime helpers have been retired.

## Static graph discovery

**Kotlin K2 / symbol resolution** is used to locate Flow and Compose constructs instead of matching variable names in text. The analyzer builds nodes for relevant state sources, propagation operations, collectors, reads, and writes. Where symbols resolve, it can follow field-level provenance (including `copy`, `combine`, and transformations) and side-effect paths that read one state and write another.

Compose discovery is **independent** of Flow discovery: production `@Composable` declarations can appear even if they are not related to any Flow. Static calls between Composables and resolved navigation destinations—including `NavHost`, `NavGraphBuilder` helpers, `composable`, `dialog`, `navigation`, `bottomSheet`, and `setContent` roots—can form a UI hierarchy. A static call or declared route is *not* evidence that the destination appeared on screen.

Flow-to-UI relationships are added only when state collection or consumption can be established. Collecting state is not by itself a verified recomposition; a confirmed Compose State value read and a possible UI impact are shown differently. Unresolved calls can leave a declaration visible without a connecting edge. Analysis limits also apply to very large projects.

### State and operations recognized

- **Coroutines:** `Flow`, `StateFlow`, `MutableStateFlow`, `SharedFlow`, `MutableSharedFlow`.
- **Compose state:** `State`, `MutableState`, primitive `IntState`/`LongState`/`FloatState`/`DoubleState` variants, and common delegates/factories such as `mutableStateOf`, `derivedStateOf`, `rememberUpdatedState`, `produceState`, and `mutableIntStateOf`/`mutableLongStateOf`/`mutableFloatStateOf`/`mutableDoubleStateOf`.
- **Operators:** Common transforms and combinations, including `map`, `mapLatest`, `mapNotNull`, `filter`, `transform`, `combine`, `zip`, `merge`, `flatMapLatest`, `flatMapConcat`, `flatMapMerge`, `stateIn`, `shareIn`, `distinctUntilChanged`, `debounce`, `sample`, `scan`, `runningFold`, `onEach`, `catch`, `retry`, `buffer`, `conflate`, and `flowOn`.
- **Observation:** `collect`, `collectLatest`, `launchIn`, `collectAsState`, `collectAsStateWithLifecycle`, `first`, `single`, `produceIn`, and other terminal operations.
- **Writes:** `.value =`, `update`, `getAndUpdate`, `updateAndGet`, `emit`, and `tryEmit`.

These are supported patterns, not a promise of complete program execution or support for every custom operator.

## Graph views and interaction

### All Flows overview

**Load all flows** scans production sources and presents a project-wide overview. With **Concise overview** enabled, the overview folds duplicate Compose call sites and read-only observer leaves, compresses verbose causal captions, and keeps relevant Compose subtrees in view. It is an architecture map, not a literal call graph; disable that setting for a less condensed overview.

The overview may use multiple **visual copies** of a canonical state or Composable to make lanes readable. Graph semantics and source navigation refer to canonical symbols; layout and hit-testing use the visible instance. The **Clusters** navigation rail appears in a project overview when navigation entries exist and hides in selected-node detail view.

### Detail Flow

Selecting a node focuses the graph on upstream **Causes ↑** and downstream **Affected ↓**. Depth controls limit traversal; additional filters can show operators, reads, possible connections, and library detail. Clicking an edge selects its visible route; double-clicking opens its source. The node's square source icon navigates to the declaration without changing selection.

Node dragging changes the display layout, not the underlying dependency. Edges use node-avoiding routes where possible and hit-testing follows their visible bends. If the router cannot find a clear route, visibility takes priority over perfect obstacle avoidance. Zoom-to-cursor, pan, fit, a magnifier, low-zoom name tags, and viewport culling make large graphs easier to navigate; exceptionally dense views can still omit overlapping labels.

### View Settings defaults

| Control | Default | Effect |
| --- | --- | --- |
| Compose state | **Off** | Include/exclude standalone Compose State nodes |
| Coroutine Flow/StateFlow | On | Show coroutine state and Flow paths |
| Compose views | On | Show independently discovered Composables |
| Project composables only | On | Collapse framework Compose call sites |
| Flow-influenced UI only | Off | Restrict UI nodes to those with analyzed Flow influence |
| Possible connections | On | Include inferred/possible relationships |
| Expand possible | **Off** | Traverse possible relationships in a focused graph |
| Concise overview | On | Condense overview projections |
| Circuit | On | Circuit-style graph and layout |
| Reads | On | Display read-only observation sites |

**Expand possible** is subordinate to **Possible connections**. With it off, a focused graph retains one-hop possible neighbors as context; turning it on traverses possible relationships within the configured depth **while retaining that context**. Disabling Possible connections removes the inferred paths, not just their dashed paint, and repacks the graph.

### Runtime branches only

The **Runtime branches only** view in All Flows retains branches with observed runtime evidence since the last **Clear runtime**. It preserves the associated causal Flow/StateFlow structure and UI endpoints while hiding inactive siblings. This filter is session-persistent, rather than a short recent-activity window. Selecting a node still opens the complete detail graph.

## Live tracing

Live tracing is **optional and debug-only**. The Gradle plugin instruments the debug variant and injects its matching runtime. With the Live server enabled, the Android runtime connects through `adb reverse tcp:50737 tcp:50737` to the IDE endpoint at `127.0.0.1:50737`. Android requires `android.permission.INTERNET` even for this local TCP socket; the permission can be restricted to the app's debug manifest. No external Internet service or Android runtime permission prompt is required.

The **ADB reverse** toolbar button appears after enabling **Live** and performs the port forwarding, or you can run the equivalent ADB command yourself. The active graph is overlaid with observed events rather than replaced by a separate profiler.

Typical event kinds include:

| Event | Meaning |
| --- | --- |
| `emit` / `emit-request` | State/SharedFlow write or suspending emit request |
| `collect` / `deliver` | Collection boundary or delivered Flow value |
| `read` | Read of Flow/Compose state |
| `state-change` | Effective Compose state change |
| `compose` | Observed Compose body execution |
| `ui` | Touch, key, or scroll interaction marker |

Events are associated with relevant graph elements for inspection and timeline scrubbing. **Time proximity is not automatically converted into a causal edge.** Pause the timeline and scrub to compare what changed around an interaction.

### Runtime Compose identity and UI inspection

Compose source activity uses the `@compose2|package|file|functionName|sourceLine` identity so similarly named Composables are distinguishable. Legacy `@compose|...` identifiers are accepted only when unambiguous; rebuilding the debug APK yields the more precise identity.

**Current UI** aims to identify a substantial visible project Composable (not necessarily the root). It can link that runtime surface back to the graph and render a preview when layout geometry is available. Effect-only Composables can have no renderable bounds. The **ⓘ** diagnostics panel retains full failure/counter details when UI inspection cannot complete.

Geometry lookup supports the Compose `LayoutInfo_androidKt.getView(LayoutInfo)` bridge, `opaqueOwner`, older owner fallbacks, and a `LayoutCoordinates → NodeCoordinator → LayoutNode → owner` lookup. Activity decor/AndroidComposeView can be used as a fallback. Diagnostic counts cover group matches, LayoutInfo nodes, coordinates, owners, and geometry matches.

### Optional instrumentation switches

The defaults enable Live tracing features. For lower overhead you can disable features in the Android app's build configuration:

```kotlin
flowGraphInstrumentation {
    enabled.set(true)
    traceReads.set(true)
    traceColdFlows.set(true)
    traceSuspendingEmits.set(true)
    traceCompose.set(true)
    traceUiInteractions.set(true)
}
```

See [instrumentation/README.md](../instrumentation/README.md) for the full setup and the debug runtime's dependency configuration.

### Quit and restart

**Quit** closes the plugin's active Live server and clients, stops background workers/UI timers, cancels analysis, unregisters listeners, and clears graph and preview caches. Reopening the tool window presents **Start Flow Graph** for a fresh session. Merely hiding the tool window does not perform this cleanup. Quit does not stop the Android app, uninstall instrumentation, or remove the device's ADB reverse mapping (`adb reverse --remove tcp:50737` removes that mapping).

## Semantic limits

Flow Graph is a debugging/comprehension tool, not an execution simulator or formal proof system.

- Reflection, dynamic dispatch, unusual type aliases/subtypes, and arbitrary custom operators can be unresolved or conservatively omitted.
- Higher-order operators with complex nested Flow-valued lambdas can have incomplete provenance.
- A Compose State read inside a deferred non-Composable callback or effect can be over-attributed to its enclosing Composable; separating every callback from composition requires deeper lambda analysis.
- The presence of a navigation route does not mean the destination ran. A state collector is not automatically a confirmed recomposition.
- Runtime tracing adds overhead and relies on the available Compose/compiler/tooling metadata. Not all Composables have renderable geometry.
- Visual routing may cross another node or edge when no safe route can be found; keeping dependencies visible takes priority over silently hiding them.

When a relationship cannot be established with reasonable confidence, the analyzer should avoid inventing certainty. See [Graph semantics](GRAPH_SEMANTICS.md) for the edge meanings.

## Source map and validation

- `analysis/`: Kotlin K2 Flow and independent Compose discovery, call-site/navigation analysis, state consumption, confidence.
- `model/`: canonical node and edge kinds, confidence, field-level impact, focus cones, graph projections.
- `ui/FlowGraphPanel.kt`: Swing composition root and settings.
- `ui/GraphDisplayController.kt`: filters and layout orchestration.
- `ui/DetailGraphController.kt` and `model/GraphFocus.kt`: upstream/downstream focus behavior.
- `ui/GraphCanvas.kt`, `ui/GraphSceneRenderer.kt`, `ui/NodeAvoidingEdgeRouter.kt`: drawing, hit-testing, dragging, and edge routing.
- `ui/GraphNavigationControls.kt`, `ui/ClusterViewportNavigation.kt`: Clusters navigation.
- `service/`: Live transport and runtime/Compose capture services.

```bash
./gradlew clean test buildPlugin verifyPlugin
./gradlew -p instrumentation clean build
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

The first command creates an IDE plugin ZIP under `build/distributions/`. Local Maven publication is **not** a remote JitPack release. Source-size and organization invariants are tested under `src/test/kotlin/com/oskiapps/flowgraph/architecture/`. For release procedures, see [Publishing](../PUBLISHING.md).
