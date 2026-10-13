# Flow Graph architecture (0.20.14)

The single repository contains three separately built components:

| Component | Source | Deployment |
| --- | --- | --- |
| Android Studio IDE plugin | `src/main/kotlin/com/oskiapps/flowgraph/` | IDE plugin ZIP from `buildPlugin`; optional JetBrains Marketplace release |
| Gradle debug instrumentation plugin | `instrumentation/flowgraph-gradle-plugin/` | Maven module built/published with JitPack |
| Android debug runtime | `instrumentation/flowgraph-runtime/` | Maven module automatically added to `debugImplementation` by the instrumentation plugin |

No manual runtime-helper source copy is supported or required. **Static analysis only requires the IDE plugin**; the app needs the two instrumentation-side components only for Live tracing. Publishing to JetBrains Marketplace does not publish the Maven modules, and testing Maven publication locally does not make those modules available on JitPack.

## Analysis and graph model

- `analysis/`: K2 PSI/symbol discovery, project-wide Flow and independent production `@Composable` declaration discovery, static Composable calls and navigation entry points, Compose State consumption and confidence handling.
- `model/`: canonical nodes, edge kinds, definite/possible confidence, state transitions, focus cones and graph projections.
- `ui/FlowGraphPanel.kt`: Swing tool-window composition root and view settings.
- `ui/GraphDisplayController.kt`: structural filters and layout/render orchestration.
- `ui/DetailGraphController.kt`, `model/GraphFocus.kt`: directional detail-graph traversal and depth-limited possible-context behavior.
- `ui/GraphCanvas.kt`, `ui/GraphSceneRenderer.kt`, `ui/NodeAvoidingEdgeRouter.kt`: drawing, hit-testing, draggable node bounds and best-effort edge routing.
- `ui/GraphNavigationControls.kt`, `ui/ClusterViewportNavigation.kt`: All Flows cluster navigation; the rail appears in a project overview with navigation entries, not in a selected-node detail view.
- `service/`: Live transport, event data and optional Compose capture services.

The overview may display multiple **visual copies** of a canonical Flow or Composable to keep separate lanes readable. Source navigation and analysis use canonical identities; layout, edge selection, and routing use visual identities. A static composition call or navigation destination is **not** a claim that a screen is currently visible or was recomposed.

## View Settings defaults

| Control | Default | Meaning |
| --- | --- | --- |
| Compose state | **Off** | Show/hide standalone Compose `State`/`MutableState` nodes |
| Coroutine Flow/StateFlow | On | Show coroutine Flow and state dependencies |
| Compose views | On | Show independently discovered production `@Composable` functions |
| Project composables only | On | Collapse AndroidX/framework Compose call-site nodes |
| Flow-influenced UI only | Off | When on, keep only UI nodes with an analyzed Flow influence |
| Possible connections | On | Include inferred edges in the visible graph |
| Expand possible | **Off** | Traverse possible edges through the selected focus cone instead of showing only one-hop context |
| Concise overview | On | Compact overview projection; detail remains richer |
| Circuit | On | Use circuit-style grid, routing and left-to-right layout |
| Reads | On | Include read-only observer sites |

**Expand possible** has no effect when **Possible connections** is off. With possible connections on, the off setting keeps one-hop inferred neighbors of the definite causal cone as context, but does not traverse through them. Turning it on traverses possible dependencies within the selected upstream/downstream depth **and retains all previously visible contextual nodes**. The scope is bounded by depth and known static relationships; it does not discover new source symbols.

## Runtime and Quit

The runtime opens a local TCP socket to `127.0.0.1:50737` through `adb reverse tcp:50737 tcp:50737`; the inspected app needs `android.permission.INTERNET` in its **debug merged manifest** even though no external Internet connection is needed. The runtime is injected only into debug builds by the Gradle plugin. The IDE **Quit** action stops its session listeners, timers, in-flight analysis, Live server and associated workers and clears retained graph/runtime state. It does not stop the Android app or remove device-side `adb reverse`.

## Validation

```bash
./gradlew clean test buildPlugin verifyPlugin
./gradlew -p instrumentation clean build
./gradlew -p instrumentation :flowgraph-runtime:publishToMavenLocal :flowgraph-gradle-plugin:publishToMavenLocal -PflowGraphJitPack=true
```

The first command produces the IDE ZIP in `build/distributions/`; the third command checks **local** Maven publications. CI runs these checks, but remote JitPack availability and Marketplace approval must be verified separately. Source organization and size invariants are enforced by Kotlin tests under `src/test/kotlin/com/oskiapps/flowgraph/architecture/`.
