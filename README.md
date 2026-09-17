# Flow Graph Analysis API Prototype v0.14.2

## v0.14.2 — Incremental left/right expansion in the detail graph

The focused/detail Flow graph now has a causal expander on each side of the canvas:

- **`<` on the left** reveals exactly one additional upstream/cause level.
- **`>` on the right** reveals exactly one additional downstream/affected level.
- Upstream and downstream depth are independent, so expanding one side does not make the other side grow.
- The existing **Depth** selector is the starting radius for a newly selected node. For example, `Depth = 1` starts with one level on each side; subsequent `<` / `>` clicks grow that side one level at a time.
- `Depth = All` already exposes the complete cone, so the side buttons remain visible but disabled.
- A side button disables automatically once there are no more causal nodes to reveal in that direction.
- Field-focused StateFlow views use the same independent expansion while preserving field-sensitive provenance filtering.

### Stable focal point while expanding

Incremental expansion deliberately does **not** run `Fit` after every click. Before adding a level, the canvas captures the selected node's position inside the viewport. After relayout, the viewport is shifted so that selected node stays at the same screen location as closely as the scroll bounds allow.

That means this:

```text
       B -> [C] -> D
            selected
```

can grow like this without recentering the whole map:

```text
A -> B -> [C] -> D
```

and then independently:

```text
A -> B -> [C] -> D -> E
```

The title shows the current asymmetric radius, for example `←3 | 5→`.

## Existing v0.14.1 behavior retained

- profiler-style pause when timeline scrubbing starts
- retained history remains frozen until **LIVE** is pressed
- bottom three-lane runtime history and draggable playhead
- historical event-slice overlays without moving the Flow map
- stable semantic project-wide clusters
- recently-active visibility filtering without relayout
- runtime activity cooldown
- live StateFlow / SharedFlow / cold Flow tracing

## Build / install

```bash
./gradlew clean buildPlugin
```

Install the ZIP from `build/distributions/` with Android Studio's **Install Plugin from Disk…**.

## Android runtime instrumentation

v0.14.2 is an IDE graph-navigation/UI change. The runtime wire protocol and Android instrumentation are unchanged. If your debug APK already uses the compatible v0.11.x+ instrumentation, **no APK rebuild is needed**.

For a physical device:

```bash
adb reverse tcp:50737 tcp:50737
```

Then enable **Live trace** as before.
