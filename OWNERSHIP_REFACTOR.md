# State ownership refactor (incremental)

## Implemented

- `GraphNavigationHistory` owns a bounded LIFO history of immutable `GraphViewSnapshot` values. Back and Show All now use `back()` and `clear()` rather than manipulating a deque in the panel.
- `RuntimeEventBuffer` owns a thread-safe bounded queue, pending count, and dropped-event counter. The live transport listener only offers events and the UI consumer polls; neither accesses queue implementation directly.
- `StateOwnershipTest` covers history ordering, bounded retention, clearing, bounded event delivery, and live-session clearing.

## Remaining architectural work

`FlowGraphPanel` still owns widget controls, selected graph metadata, and a number of live diagnostics; `GraphCanvas` still owns viewport and cached layout state. The extension functions for selection, rendering, and live diagnostics still operate on the panel/canvas and should be migrated with behavior-specific integration tests. Do not treat this as completed full decoupling.

## Local verification

Run `./gradlew test buildPlugin` using the project's Gradle wrapper. Build and IDE behavior were not verified in this environment.
