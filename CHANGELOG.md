## Runtime Flow/state dependency graph

- **Runtime branches only** now retains the causal coroutine graph instead of projecting runtime activity down to StateFlow boxes before filtering. Active/connector `STATE`, `OPERATOR`, `COLLECTOR`, `BEHAVIOR`, `EXPOSURE` and `WRITER` nodes remain visible.
- FIELD detail is contracted while the complete chain is still available, preserving semantic `writer -> state` / `operator -> state` links without schema noise.
- Runtime Flow/state branches retain their `updates UI` Compose endpoints even when the target did not itself emit a runtime counter, then keep only the Compose ancestor path rather than inactive siblings.
- Compose lanes render that retained causal network as one de-duplicated graph per screen lane, so shared flows fan out/fan in instead of being repeated per collector.
- Horizontal mode reads `Flow/state -> coroutine stages -> observed state/stage -> Compose`; Vertical mode transposes the same dependency ranks into top-to-bottom `Flow/state -> Compose` while clusters remain stacked one below another.
- Concise labels now expose semantic coroutine relationships (`transforms`, `collects`, `triggers`, `writes`, `exposes`) while detail view remains the source of exact field/call-site plumbing.

## Responsive graph menu + vertically stacked clusters

- The graph viewport menu now uses one width-aware wrapping toolbar. Filters, zoom/search controls and the layout selector wrap onto additional rows as the tool window narrows instead of being clipped or pushed off-screen.
- `WrapLayout` now prefers the narrower live parent width, so both the main header and graph controls immediately reflow when the IDE/tool-window width changes.
- Overview clusters are always stacked **one below another** in both layout modes. Horizontal/Vertical now changes only the reading direction *inside* each cluster.
- Vertical mode transposes each cluster independently and then re-stacks the transformed clusters vertically, preserving the same cluster order, right-side navigation order and viewport-synced highlight.

## Horizontal / vertical overview layout

- Added a **Layout: Horizontal / Vertical** selector to the graph viewport controls.
- Horizontal keeps the existing left-to-right StateFlow/state-machine → Compose reading order. Vertical rebuilds the same All Flows topology into a top-to-bottom reading order while keeping node cards upright.
- Switching direction clears cached/frozen overview geometry, rebuilds cluster bounds, and fits the result. Runtime highlighting, cluster navigation, viewport-synced cluster selection, filters and edge/source identity continue to use the same canonical nodes.
- Focused/detail graphs remain horizontal so upstream/downstream expansion controls keep their existing left/right semantics.

## Runtime-observed branch expansion

- Added **Runtime branches only** for project-wide All Flows. The graph is structurally rebuilt from nodes that have produced runtime evidence since the last Clear runtime.
- Active Compose leaves retain their Compose ancestor path so the current UI branch stays recognizable, while inactive sibling branches are omitted.
- Short causal connector paths preserve Flow/State relationships between observed nodes without reopening the whole static component.
- The filter grows automatically when a new runtime branch is first observed, but does not auto-fit/jump the camera while live events arrive. Focused detail views remain complete.

### Viewport-synced cluster navigation

- The right-side **Clusters** rail now follows the All Flows camera automatically while panning or scrolling: the cluster under the viewport centre is selected, with visible-overlap/nearest-cluster fallback while crossing whitespace.
- The active bookmark is automatically scrolled into view in the navigation rail and rendered with a stronger selected style.
- The same active cluster receives a highlighted border and subtle background in the main graph, so the overview and navigation always show the same location.
- Clicking a cluster bookmark still centers/fits it; after that, manual viewport movement immediately resumes automatic location tracking.

### StateFlow state-machine overview

- Concise All Flows now renders the state side of each Compose screen lane as a de-duplicated StateFlow state machine instead of repeating one upstream Flow tree per `collectAsState*` target.
- Semantic `state -> state` propagation edges form the machine; the observed/terminal states sit on its right edge and then feed the Compose tree with short `updates UI` arrows.
- Shared upstream states appear once per screen lane, so branches and fan-in read like a state machine rather than duplicated plumbing. Cycles remain visible as transition edges.
- **Show operators** keeps the previous detailed Flow-path representation, so collectors, transforms, behaviors and exact write sites are still available when drilling into implementation details.

- The **UI render** pane is now detail-only: it is hidden in All Flows and appears only after selecting a node/focused causal view.
- Cluster navigation labels now include semantic/source ownership for otherwise generic lane roots/sinks (`NiaApp — navigationContentColor`, `UserDataRepository — collect`, `GetNewsResourcesUseCase — invoke()`), instead of exposing bare method names.
- The right-side **Clusters** navigator is now an All Flows overview control and is hidden in focused detail views.

### Concise project overview

- Added a default-on **Concise overview** mode inspired by the survey-then-drill-down pattern used by Compose analysis tools: All Flows stays architectural, while focused detail remains complete.
- Repeated Compose call sites from the same parent to the same child collapse to one overview edge with an `×N` badge; exact call sites are preserved in detail view.
- Read-only observer leaves are grouped by semantic/source owner so `collect`, `first`, `invoke()` and similar leaves do not each become their own project-wide cluster.
- Overview edge captions are shortened to semantic labels (`changes`, `updates UI`, `composes ×N`); full causal plumbing remains available when a node is focused or Concise overview is disabled.

## Right-side cluster navigation

- Added a fixed, scrollable **Clusters** navigation rail on the right edge of the graph viewport for project-wide overview lanes.
- Each visible Compose/Flow cluster is listed by its semantic name (for example `ForYouScreen`, `InterestsScreen`, or the Flow consumer name); the complete cluster header remains available as a tooltip.
- Clicking a cluster bookmark stays in All Flows, fits that cluster to a readable zoom (without enlarging past 100%), and centers it in the viewport. It does not enter focused/detail mode.
- The navigator is rebuilt together with graph filters/repacking and disappears automatically when the current graph has no overview clusters.

### Compose lazy-scope DSL topology

- Project extension helpers on `LazyListScope`, `LazyGridScope`, and `LazyStaggeredGridScope` are now treated as Compose topology owners even when the helper itself is not annotated `@Composable`.
- Lazy item builder calls such as `item`, `items`, `itemsIndexed`, and `stickyHeader` are represented as static Compose DSL nodes because their content lambdas are composable.
- This preserves hierarchies such as `LazyVerticalStaggeredGrid -> newsFeed -> items -> NewsResourceCardExpanded` instead of dropping the feed/item content behind a non-composable DSL extension.
- Compose DSL helper nodes are project-source nodes (kept by **Project composables only**) but intentionally have no runtime recomposition key because the helper function itself is not a Compose restart group.

### Current UI selects the active specific view

- Current UI no longer treats the largest visible composable as the automatic winner. That behavior favored app/root composables even after navigating to another tab.
- The IDE now keeps the runtime-reported visible area for every source composable, maps all candidates into the project Compose tree, and prefers a deeper visible project view that still occupies a substantial part of the window.
- Tiny child controls cannot displace their screen merely because they are deeper. Screen/view-like boundaries (`Screen`, `Route`, `Page`, `Tab`, `Content`, `Pane`, `View`) are preferred when available.
- Live ⓘ diagnostics report the chosen node, composition depth, relative visible area and candidate counts.


### Current UI diagnostics in Live ⓘ

- Current UI request status and failures are now stored in the existing Live **ⓘ** diagnostics panel instead of relying on the short header hint.
- A Current UI failure, timeout, missing source-composable match, or disconnected-client error automatically opens the **ⓘ** panel so the full diagnostic counters are visible and copyable.
- The header hint stays short (for example, `Current UI failed • see ⓘ diagnostics`) while the panel retains the complete runtime message.

### Live Compose Current UI geometry compatibility

- Current UI no longer discards valid LayoutInfo geometry merely because the owning Android View cannot be resolved immediately.
- Layout owner lookup now also follows `LayoutCoordinates -> NodeCoordinator -> LayoutNode -> owner`, with Activity decor/AndroidComposeView fallback.
- Current UI failures now report diagnostic counts for composition groups, group-key/source matches, LayoutInfo nodes, coordinates, positions, owners, and geometry matches.

## Unreleased
- Added **Flow-influenced UI only**: a structural/rebuild filter that keeps only composables causally reached from coroutine Flow/StateFlow/SharedFlow state, including propagation through intermediate state, while removing unrelated Compose branches and repacking the graph. `COMPOSES` edges do not count as Flow influence, so descendants are not included just because an observing parent composes them.
- Removed the Compose `▸ N descendants` progressive-disclosure controls from All Flows; concise overview now keeps the Compose tree visible while retaining the StateFlow state-machine view.

- Fixed live Compose **Current UI / Render UI** geometry lookup on newer Compose UI releases. FlowGraph now resolves the owning Android view through `LayoutInfo_androidKt.getView(LayoutInfo)` first, supports the intermediate `LayoutInfo.opaqueOwner` API, and keeps the legacy `LayoutNode.owner` reflection fallback. This prevents attached `LayoutInfo` nodes from being discarded solely because their owner API changed.

# Changelog

### Connection correctness audit

- Framework Compose call-site nodes are now admitted only when the resolved callable actually carries `@Composable`; PascalCase AndroidX value factories/constructors such as `Offset(...)`, `Color(...)`, or `DpSize(...)` are no longer mistaken for composition nodes.
- Ordinary values whose initializer merely contains `mutableStateOf(...)` are no longer misclassified as Compose State; the untyped fallback is now restricted to delegated state properties.
- Reads inside `derivedStateOf` now connect source state -> derived state without also inventing a direct source state -> owning composable recomposition edge.
- Collapsed Flow -> Compose projection now preserves POSSIBLE confidence end-to-end, so disabling **Possible connections** also removes possible-only Compose influences. If both a definite and possible route exist, definite evidence wins.
- Compound/unary state writes (`+=`, `-=`, `++`, `--`, etc.) are recognized for delegated Compose state and `.value` state writes.
- Direct recursive project composable calls are retained and bounded by the existing visual cycle guard instead of being silently dropped.
- Edge-path highlighting stops at downstream fan-out points, so selecting one consumer branch does not light sibling consumers of the same Flow variable.
- Unknown/custom Flow stages now propagate POSSIBLE confidence onto their structural edges, so a custom inferred stage cannot become definite merely because it was projected into the compact graph.
- Multiple calls from the same composable to the same child declaration are preserved as distinct composition call sites instead of being merged into one edge.
- When the same semantic edge is found through both definite and possible evidence, definite evidence wins instead of being downgraded to possible.
- Added regression tests for possible-vs-definite Flow -> Compose projection confidence and derived Compose-state boundaries.

### Preview exclusion and lane-local edge focus

- `@Preview` and common multi-preview annotated composable functions are excluded from Compose discovery and focused Compose ownership, so tooling-only preview wrappers no longer appear as production graph roots/nodes.
- Edge focus is now tied to the exact rendered `VisualEdge` instance instead of only its canonical `FlowEdge`. In duplicated Flow/Compose lanes, clicking one edge highlights only that lane/path; other visual copies backed by the same Flow variable/edge remain dimmed.
- While an edge is focused, visual-path dimming takes precedence over shared runtime activity and canonical node selection, preventing another hot copy of the same StateFlow from looking selected.

### Project-composables filter

- Added a **Project composables only** checkbox beside the Compose visibility controls.
- Enabling it removes synthetic AndroidX/framework Compose call-site nodes such as `Column`, `Row`, `Text`, `Surface`, `Offset`, and similar library UI primitives from both All Flows and focused detail graphs.
- Removed framework nodes are contracted rather than simply hidden: project `@Composable` ancestors reconnect directly to their nearest project `@Composable` descendants, preserving the readable hierarchy.
- Toggling the filter clears cached geometry, rebuilds/re-packs the visible graph, and auto-fits the compact result.

### Possible-connections filter

- Added a **Possible connections** checkbox beside the semantic overview filters.
- Unchecking it removes inferred/possible causal edges from both All Flows and focused detail graphs.
- The graph is rebuilt, orphaned possible-only helper nodes are pruned, geometry is repacked from scratch, and the result is auto-fitted.
- Renamed the older **Possible** focus control to **Expand possible** to distinguish traversal from visibility.

## Unreleased
### Build fix

- Fixed a Kotlin parser break introduced by the preview-exclusion documentation comment. The text `*Preview/*Previews` accidentally opened a nested block comment, which made the compiler treat the remainder of `FlowSemantics.kt` as unterminated and caused the cascade of unresolved-reference errors reported by `compileKotlin`.


- The **Compose state**, **Coroutine Flow/StateFlow**, and **Compose views** checkboxes now apply in focused/detail view as well as All Flows. Detail cones are built from the already-filtered semantic layers, and hiding the currently selected layer exits the now-invalid focus cleanly.
- Wheel/trackpad zoom is coalesced to at most one visual scale commit per ~16 ms frame. Raw wheel deltas are accumulated in log-space around the latest cursor anchor, and stale post-layout anchor corrections are discarded, removing the dense-graph zoom jitter caused by overlapping Swing resize/revalidate callbacks.
- Graph painting is viewport-culled: off-screen lane boxes, nodes, and edge paths are skipped before expensive drawing. This keeps zoom responsive on the much larger duplicated hierarchical overview instead of repainting the entire graph for every zoom frame.
- Added independent **Compose state**, **Coroutine Flow/StateFlow**, and **Compose views** checkboxes above the graph. Changing any filter rebuilds the project overview from the remaining semantic layers, repacks all lanes from scratch, and auto-fits the result so hidden categories leave no empty layout holes.
- The hierarchical project overview now also supports pure Flow-only and pure Compose-only filter combinations instead of falling back to the generic global layout.
- Flow-only consumers now use hierarchical left-to-right lanes instead of one globally de-duplicated Flow DAG, removing dense spider-web fan-out in All Flows.
- Flow/State influence beside Compose screens is now tree-projected per target and may repeat shared upstream nodes locally, matching the readability model used by Compose lanes.

- Compose topology now follows the actual visual composition hierarchy instead of flattening every descendant `@Composable` directly under its owning source function.
- Visual AndroidX Compose call sites such as `Column`, `Row`, `LazyVerticalGrid`, `Button`, and `Text` are represented as static composition nodes without expanding library internals.
- Compose-heavy graphs use a dedicated left-to-right hierarchy layout: larger/parent composables are placed before descendants and parents are vertically centered over their subtree.
- All Flows now separates the Flow/State causal DAG from the Compose visualization instead of forcing both into one global DAG layout.
- Compose is rendered as independent horizontal screen lanes. Shared composables and common wrappers may be repeated as visual instances in different lanes, while every copy still maps back to the same canonical source node for navigation/runtime data.
- State-connected `…Route` owners prefer their downstream `…Screen` as the lane root when available, so UI trees read from the visual screen toward its descendants.
- Common infrastructure such as `CompositionLocalProvider`, `…Theme`, `Surface`, `Scaffold`, and `NavHost` no longer acts as global layout glue; only a short lane-local wrapper prefix is repeated when it adds useful context.
- Cross-lane `COMPOSES` edges are removed from the project overview. Each lane contains only local parent → child connections, avoiding long-distance edges whose only purpose was to prove that a declaration is shared.
- All Flows now repeats the Flow/State nodes that can invalidate a Compose lane immediately to that lane's left, mirroring the focused detail view. Shared Flow nodes may therefore appear beside multiple screens, but their `UPDATES_COMPOSE` edges stay local instead of crossing the canvas.
- When state is collected in an omitted `…Route`, Theme, or other wrapper, the overview projects that influence onto the nearest visible descendant in the lane so the state → screen relationship remains visible without restoring a common global Compose root.

## 0.19.2

- All Flows now scans only production Kotlin source roots; unit-test, Android-test, test-fixture and other IDE-marked test source files are excluded before Flow/Compose discovery.
- References from production state into test source roots are also ignored, so tests cannot pull test collectors, writers or Compose trees into the production architecture graph.
- Project-wide All Flows no longer materializes every `setContent { ... }` lambda as a visible Compose node. Named production `@Composable` caller/callee relationships build the global UI topology without repeating framework host nodes.
- Focused analysis still supports a real production `Activity.setContent` / `ComposeView.setContent` lambda when state is consumed directly there, but `setContent` is now resolved semantically to Compose APIs instead of accepted by short name alone.
- Removing test/host scanning reduces K2 work and graph size on Compose-heavy projects.

## 0.19.1

- All Flows now builds a project-wide Compose call topology and joins state-connected UI islands through their shared project `@Composable` callers/callees.
- Compose topology discovery is performance-bounded: project files are enumerated once, call sites are prefiltered by project-composable name, calls are K2-resolved in per-function batches, and only components reachable from existing state-connected composables are materialized.
- State-to-state transition projection now treats `UPDATES_COMPOSE` / `COMPOSES` as terminal UI edges so a large Compose tree is never traversed once per StateFlow.
- Direct Compose `State` → composable edges are preserved in the collapsed State Changes / All Flows projection instead of disappearing and creating false UI islands.
- `setContent { ... }` hosts are included as optional Compose roots, while hard caps and cancellation checks keep very large/generated projects responsive.
- Compose projection now uses the graph's incoming-edge index instead of rescanning every edge for every consumer, reducing All Flows projection cost on large graphs.

## 0.19.0

- Moved the public code namespace from `dev.flowgraph` to `com.oskiapps.flowgraph`.
- Gradle instrumentation plugin ID is now `com.oskiapps.flowgraph.instrumentation`.
- Android Studio plugin ID is now `com.oskiapps.flowgraph`.
- Runtime implementation packages and ASM internal names now use `com.oskiapps.flowgraph.runtime`.
- JitPack runtime coordinates stay `com.github.elivity:flow-graph:0.19.0`; only the Java/Kotlin namespace and plugin IDs changed.
- Marketplace vendor metadata now uses `OskiApps`.

## 0.18.6

- Fixed the JitPack build environment to use Java 17, matching the runtime toolchain requirement.
- Bumped current release, installation, publishing, and CI version references to `0.18.6`.

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


### Build fix after `@compose2` runtime identity

- Moved `isSourceComposeRuntimeKey` to file scope so both `FlowGraphPanel` and the separate `GraphCanvas` layout/renderer can use it.
- Fixed the nullable Compose child check used while selecting readable screen roots.
- This resolves the `compileKotlin` failures reported at `FlowGraphPanel.kt:3387`, `3419`, `3421`, and `4657`.

### Compose runtime identity (`@compose2`)

- Source composable runtime keys now include the source line: `@compose2|package|file|functionName|sourceLine`.
- Same-name composables/overloads in one file no longer collide and silently lose recomposition events.
- The analyzer keeps exact source-derived line aliases for Kotlin/Compose LineNumberTable variance.
- Legacy `@compose|package|file|functionName` events remain supported only when they resolve unambiguously.
