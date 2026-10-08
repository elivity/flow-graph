# Responsibility-based refactor

The earlier numeric code fragments have been replaced by named units grouped by the functionality they implement, **not** by file length. Each extracted file contains related extension functions and retains the original package and function signatures to minimize caller changes.

- `ui/GraphCanvas.kt`: canvas state and Swing component lifecycle.
- `ui/CanvasViewport.kt`, `ClusterViewportNavigation.kt`: zoom, fit, scroll, preserved anchors, cluster navigation.
- `ui/CircuitLayerLayout.kt`, `ProjectOverviewLayout.kt`, `ComposeLaneLayout.kt`, `CompositionLayout.kt`, `FlowTreeLayout.kt`: layout strategies.
- `ui/CircuitEdgeRouting.kt`, `GraphHitTesting.kt`: edge geometry and interaction.
- `ui/NodeRenderer.kt`, `EdgeRenderer.kt`, `GraphSceneRenderer.kt`, `RuntimeActivityRenderer.kt`: rendering concerns.
- `ui/GraphViewHistory.kt`, `NodeSelectionController.kt`, `GraphNavigationControls.kt`, `DetailGraphController.kt`: navigation and selection.
- `ui/LiveControls.kt`, `LiveDiagnostics.kt`, `LiveEventController.kt`, `TimelinePlaybackController.kt`: live sessions, diagnostics and timeline.
- `model/StateTransitionAnalysis.kt`, `GraphReachability.kt`, `GraphFocus.kt`, `GraphProjection.kt`: graph domain operations.
- `analysis/ProjectSymbolDiscovery.kt`, `FlowGraphBuilder.kt`, `ComposeHierarchyAnalysis.kt`, `ComposeStateConsumption.kt`: PSI analysis stages.

The existing `FlowGraphPanel.kt` remains the Swing composition root. A future behavioral refactor could replace its field-heavy coupling with injected collaborators; doing so safely requires a working IDE build and integration tests. This change instead groups methods by responsibility while preserving behavior.

Validation: `SourceFileSizeTest`, `SourceOrganizationTest` and existing model tests are included. Full IDE compilation has not been demonstrated.
