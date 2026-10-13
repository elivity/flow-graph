# What Flow Graph edges mean

This is a **source-level dependency view**, not proof that every edge executed.

- **Flow/StateFlow propagation** is constructed from Kotlin source and resolved calls. An
  operator may suppress values (`distinctUntilChanged`, filtering), or run only when collected.
- **Compose State value read (can invalidate)** means a recognizable snapshot value read was
  found at the collection site/owning composable. It is not an observed recomposition and does
  not prove an actual visual change occurred.
- **State collected (value read not verified)** is **possible**, not definite, UI causality.
  Collecting `State<T>` does not by itself read `State.value` during composition. Values
  forwarded to descendants and custom read abstractions may require deeper analysis.
- **Composition call (static, may be conditional)** means a call site exists; the child may
  be absent from the live composition due to conditional branches.
- **Live Compose invocation counts** are runtime instrumentation observations, not proof
  that an input edge was responsible for any particular recomposition.
- **Dashed / possible** links represent weaker static evidence. Do not interpret them as
  established causes. When a scope is ambiguous, the analyzer prefers an explicit possible
  relationship to silently claiming definite causality.

## Layout and interaction

Routes are based on **displayed node instances**, which may differ from canonical nodes in
Project Composables views. Both painting and hit-testing use the same obstacle-avoiding
path. Nodes are treated as routing obstacles, but an impossible arrangement (e.g. dragged
nodes overlapping a required port) can force a visible fallback route. Routed edges are
culled by their actual polyline bounds, not by a rectangle enclosing their endpoint nodes.

These semantics and visual checks should be validated together in Android Studio and CI.

## Expand possible versus Possible connections

**Possible connections** is a structural visibility filter: disabling it removes inferred edges before building the graph and can remove possible-only intermediary nodes. **Expand possible** only affects the selected/focused causal cone while Possible connections is enabled. Disabled: preserve one-hop possible connections around the definite cone as context, without traversing through them. Enabled: also traverse possible edges up to the selected depth, **without removing any of the off-state nodes**. The same upstream/downstream depth limits still apply to traversal; context nodes can sit one hop past those limits.

The presence of a possible edge is a statement about static uncertainty, **not** observed execution. Edge routing avoids unrelated nodes when a valid detour exists, but a visible fallback can still cross obstacles, and wires may cross each other in crowded layouts.
