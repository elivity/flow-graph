package com.oskiapps.flowgraph.ui


import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer
import com.oskiapps.flowgraph.model.*
import com.oskiapps.flowgraph.service.FlowGraphProjectService
import com.oskiapps.flowgraph.service.ComposeRenderService
import com.oskiapps.flowgraph.service.ComposeRenderSnapshot
import com.oskiapps.flowgraph.service.ComposeRenderState
import com.oskiapps.flowgraph.service.LiveTraceService
import com.oskiapps.flowgraph.service.LiveTraceStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

internal class GraphCanvas(
    internal val project: Project,
    internal val onNodeSelected: (String) -> Unit,
    internal val onZoomChanged: (Double) -> Unit,
    internal val onUserViewportInteraction: () -> Unit,
    internal val onClustersChanged: (List<ClusterNavigationEntry>) -> Unit,
    internal val onActiveClusterChanged: (Int?) -> Unit,
) : JPanel() {
    internal val composeRenderService: ComposeRenderService = project.getService(ComposeRenderService::class.java)
    var graph: FlowGraph? = null
        set(value) {
            cancelPendingWheelZoom()
            field = value
            focusedVisualEdge = null
            cachedLayout = null
            circuitRoutingCache = null
            activeClusterIndex = null
            onActiveClusterChanged(null)
            onClustersChanged(emptyList())
        }
    var selectedId: String? = null
        set(value) {
            field = value
            cachedLayout = null
            if (value != null) {
                setActiveCluster(null)
            } else {
                SwingUtilities.invokeLater { updateActiveClusterFromViewport() }
            }
            repaint()
        }
    /** The exact rendered edge instance selected by the user. Keeping the visual ids is crucial
     * in lane mode because several lanes may intentionally repeat the same canonical FlowEdge. */
    internal var focusedVisualEdge: VisualEdge? = null

    var compactLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                cachedLayout = null
                circuitRoutingCache = null
            }
        }
    var projectOverviewVertical: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                frozenLayout = null
                cachedLayout = null
                circuitRoutingCache = null
                activeClusterIndex = null
                onActiveClusterChanged(null)
            }
        }
    var includePossibleTraversal: Boolean = false
        set(value) { field = value; repaint() }
    var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
        set(value) { field = value; repaint() }
    /** Null = live wall clock. Non-null = historical scrub reference time. */
    var runtimeReferenceMillis: Long? = null
        set(value) { field = value; repaint() }
    var eventLensEnabled: Boolean = false
        set(value) { field = value; repaint() }
    var magnifierEnabled: Boolean = false
        set(value) { field = value; repaint() }
    var circuitViewEnabled: Boolean = true
        set(value) { field = value; repaint() }
    var searchQuery: String = ""
        set(value) {
            field = value.trim()
            repaint()
        }
    var recentFocusEnabled: Boolean = false
        set(value) { field = value; repaint() }
    var recentFocusIds: Set<String> = emptySet()
        set(value) { field = value; repaint() }

    internal val boundsById = mutableMapOf<String, Rectangle>()
    internal val nodeW = 250
    internal val nodeH = 76
    internal val colGap = 170
    internal val rowGap = 34
    internal var cachedLayout: NodeLayout? = null
    internal var activeClusterIndex: Int? = null
    internal var circuitRoutingCache: CircuitRoutingCache? = null
    /**
     * Snapshot of the map before a temporary visibility-only filter is applied.
     * Visible nodes reuse these exact coordinates so the user's spatial memory remains valid.
     */
    internal var frozenLayout: NodeLayout? = null

    internal var zoom = 1.0
    // Wheel/trackpad input can arrive much faster than Swing can resize the canvas + JViewport.
    // Keep only one visual zoom commit per display frame and fold all wheel deltas received during
    // that frame into it. This removes the resize/anchor/repaint feedback loop that made zoom feel
    // jittery on dense graphs.
    internal var pendingWheelZoomExponent = 0.0
    internal var pendingWheelModelX = 0.0
    internal var pendingWheelModelY = 0.0
    internal var pendingWheelAnchorInViewport: Point? = null
    internal var zoomApplyGeneration = 0L
    internal val wheelZoomTimer = Timer(WHEEL_ZOOM_FRAME_MS) {
        flushPendingWheelZoom()
    }.apply {
        isRepeats = false
        isCoalesce = true
    }
    internal var unscaledPreferredSize = Dimension(1, 1)
    // Symmetric device-pixel breathing room around the graph. Without this, when the scaled graph
    // is smaller than the viewport JViewport clamps viewPosition to (0, 0), making it impossible
    // to keep the logical point under the mouse fixed during low-level zoom. Keeping one viewport
    // worth of virtual canvas on every side gives cursor-centered zoom the same camera freedom at
    // 20% that it has at 150%+.
    internal var canvasPaddingX = 0
    internal var canvasPaddingY = 0
    internal var panStartOnScreen: Point? = null
    internal var panStartViewPosition: Point? = null
    internal var panDragged = false
    internal var suppressNextClick = false
    internal var magnifierMousePoint: Point? = null

    internal data class RuntimeLensTarget(
        val node: FlowNode,
        val bounds: Rectangle,
        val ageMs: Long,
    )

    internal data class EdgePathFocus(
        val seed: VisualEdge,
        val edges: Set<VisualEdge>,
        /** Visual instance ids, not canonical FlowNode ids. */
        val nodes: Set<String>,
    )

    internal data class CircuitRoutingCache(
        val routes: Map<FlowEdge, List<Point>>,
        val junctions: Set<Point>,
    )

    internal enum class EdgePathStyle {
        NONE,
        DIMMED,
        PATH,
        SEED,
    }

    init {
        background = UIManager.getColor("Panel.background")
        toolTipText = "Wheel to zoom; drag empty canvas (or middle-drag) to pan; click a node to isolate its causal connections; click an edge to highlight its path; use the square icon on a node to open source"

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val shouldPan = SwingUtilities.isMiddleMouseButton(e) ||
                    (SwingUtilities.isLeftMouseButton(e) && !isInteractiveAt(e.point))
                if (!shouldPan) return

                onUserViewportInteraction()
                cancelPendingWheelZoom()
                panStartOnScreen = e.locationOnScreen
                panStartViewPosition = viewport()?.viewPosition?.let(::Point)
                panDragged = false
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                e.consume()
            }

            override fun mouseDragged(e: MouseEvent) {
                val startScreen = panStartOnScreen ?: return
                val startView = panStartViewPosition ?: return
                val viewport = viewport() ?: return

                val dx = e.locationOnScreen.x - startScreen.x
                val dy = e.locationOnScreen.y - startScreen.y
                if (kotlin.math.abs(dx) > PAN_DRAG_THRESHOLD || kotlin.math.abs(dy) > PAN_DRAG_THRESHOLD) {
                    panDragged = true
                }

                viewport.viewPosition = clampViewPosition(
                    viewport,
                    startView.x - dx,
                    startView.y - dy,
                )
                magnifierMousePoint = Point(e.point)
                if (magnifierEnabled) repaint()
                e.consume()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (panStartOnScreen == null) return
                suppressNextClick = panDragged
                panStartOnScreen = null
                panStartViewPosition = null
                panDragged = false
                updateCursor(e.point)
                e.consume()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (suppressNextClick) {
                    suppressNextClick = false
                    return
                }
                val model = graph ?: return
                val point = toModelPoint(e.point)

                // Source navigation has priority over selection and edge focus.
                // The icon click must not modify any graph interaction state.
                val sourceHit = boundsById.entries.firstOrNull { (visualId, bounds) ->
                    bounds.contains(point) && sourceIconRect(bounds).contains(point) &&
                        model.node(sourceNodeId(
                            cachedLayout ?: layoutNodes(model).also { cachedLayout = it },
                            visualId,
                        ))?.source != null
                }
                if (sourceHit != null) {
                    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
                    val source = model.node(sourceNodeId(layout, sourceHit.key))?.source
                    if (source != null) {
                        OpenFileDescriptor(project, source.file, source.offset).navigate(true)
                        e.consume()
                        return
                    }
                }

                val visualNodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
                if (visualNodeId != null) {
                    focusedVisualEdge = null
                    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
                    val nodeId = sourceNodeId(layout, visualNodeId)
                    val node = model.node(nodeId) ?: return
                    if (node.kind !in setOf(NodeKind.CYCLE, NodeKind.CLUSTER)) {
                        onNodeSelected(nodeId)
                    }
                    repaint()
                    return
                }

                val edge = findEdgeAt(model, point)
                if (edge == null) {
                    if (focusedVisualEdge != null) {
                        focusedVisualEdge = null
                        repaint()
                    }
                    return
                }
                if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                    navigationSource(model, edge.source)?.let { source ->
                        OpenFileDescriptor(project, source.file, source.offset).navigate(true)
                        e.consume()
                    }
                    return
                }
                focusedVisualEdge = edge
                repaint()
            }

            override fun mouseMoved(e: MouseEvent) {
                magnifierMousePoint = Point(e.point)
                updateCursor(e.point)
                if (magnifierEnabled) repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                magnifierMousePoint = null
                if (magnifierEnabled) repaint()
            }

            override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) {
                if (e.preciseWheelRotation == 0.0) return
                onUserViewportInteraction()
                queueWheelZoom(e.preciseWheelRotation, Point(e.point))
                e.consume()
            }
        }

        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }



    data class ViewAnchor(
        val nodeId: String,
        val viewportOffsetX: Int,
        val viewportOffsetY: Int,
    )

    /** Capture where a node currently appears inside the viewport so incremental expansion can
     * reveal a new outer level without making the user's focal node jump across the screen. */

























    internal data class ComponentCluster(
        val bounds: Rectangle,
        val label: String,
    )

    internal data class VisualEdge(
        val fromVisualId: String,
        val toVisualId: String,
        val source: FlowEdge,
    )

    internal data class NodeLayout(
        /** Bounds are keyed by visual instance id. Most views use the canonical node id directly;
         * Compose lanes may contain several visual instances of the same source composable. */
        val bounds: Map<String, Rectangle>,
        val readCluster: Rectangle?,
        val componentClusters: List<ComponentCluster>,
        val preferredSize: Dimension,
        val clusterByNodeId: Map<String, Int> = emptyMap(),
        /** visual instance id -> canonical FlowNode id */
        val sourceNodeIdByVisualId: Map<String, String> = emptyMap(),
        /** Edges between visual instances. Empty means use the canonical model edges. */
        val visualEdges: List<VisualEdge> = emptyList(),
    )



    internal data class EdgeLine(
        val edge: FlowEdge,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
    )

    internal enum class EdgeDirection {
        NONE,
        FORWARD,
        BACKWARD,
        BOTH,
        POSSIBLE,
    }





    internal data class ComponentPlacement(
        val bounds: Map<String, Rectangle>,
        val width: Int,
        val height: Int,
        val label: String,
    )

    internal data class ComposeLanePlacement(
        val bounds: Map<String, Rectangle>,
        val sourceNodeIdByVisualId: Map<String, String>,
        val visualEdges: List<VisualEdge>,
        val width: Int,
        val height: Int,
        val label: String,
        val localFlowSourceIds: Set<String> = emptySet(),
    )

    internal data class FlowTreePlacement(
        val bounds: Map<String, Rectangle>,
        val sourceNodeIdByVisualId: Map<String, String>,
        val visualEdges: List<VisualEdge>,
        val sinkVisualId: String,
        val sourceIds: Set<String>,
        val width: Int,
        val height: Int,
    )

    /**
     * Overview-only de-duplicated Flow/state graph. A canonical state/coroutine stage appears once
     * inside one screen lane, causal edges may branch/merge/cycle, and the observed terminal stage
     * then feeds Compose. This deliberately differs from [FlowTreePlacement], which may duplicate
     * upstream nodes to explain a detailed path.
     */
    internal data class StateMachinePlacement(
        val bounds: Map<String, Rectangle>,
        val sourceNodeIdByVisualId: Map<String, String>,
        val visualEdges: List<VisualEdge>,
        val visualIdBySourceNodeId: Map<String, String>,
        val sourceIds: Set<String>,
        val width: Int,
        val height: Int,
    )

    internal data class FlowLanePlacement(
        val bounds: Map<String, Rectangle>,
        val sourceNodeIdByVisualId: Map<String, String>,
        val visualEdges: List<VisualEdge>,
        val width: Int,
        val height: Int,
        val label: String,
        val sourceIds: Set<String>,
    )

    /**
     * Builds the concise coroutine/state side of a Compose lane as one de-duplicated causal graph.
     *
     * In the ordinary State Changes projection this naturally contains STATE nodes only. Runtime
     * branches use flowStateArchitectureProjection(), so the same layout can additionally retain
     * operators, collectors, behaviors, exposures and write sites. One canonical node gets one
     * visual instance per screen lane: shared upstream flows therefore fan out/fan in like a real
     * graph instead of being duplicated once per Compose observer.
     *
     * Geometry is authored left-to-right (upstream coroutine/state graph -> observed state/stage ->
     * Compose). Vertical overview transposes the *inside* of each cluster later, which turns the
     * exact same dependency graph into top-to-bottom flow -> state -> Compose while clusters remain
     * stacked under each other.
     */



    internal data class PackedComponent(
        val placement: ComponentPlacement,
        val x: Int,
        val y: Int,
    )


    /**
     * Keep overview clusters in one deterministic vertical stack.
     *
     * The graph orientation now changes only how each cluster reads internally:
     * Horizontal = left-to-right inside a cluster, Vertical = top-to-bottom inside a cluster.
     * The clusters themselves always remain one below another so viewport navigation, the
     * right-side bookmark rail and visual scanning use the same stable order in both modes.
     */


    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val model = graph ?: return

        val sceneGraphics = graphics.create() as Graphics2D
        try {
            sceneGraphics.translate(canvasPaddingX.toDouble(), canvasPaddingY.toDouble())
            sceneGraphics.scale(zoom, zoom)
            sceneGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            drawGraphScene(sceneGraphics, model, updateBounds = true)
        } finally {
            sceneGraphics.dispose()
        }

        if (eventLensEnabled) {
            val overlayGraphics = graphics.create() as Graphics2D
            try {
                overlayGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                drawRuntimeEventLens(overlayGraphics, model)
            } finally {
                overlayGraphics.dispose()
            }
        }

        if (magnifierEnabled) {
            val magnifierGraphics = graphics.create() as Graphics2D
            try {
                magnifierGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                drawMouseMagnifier(magnifierGraphics, model)
            } finally {
                magnifierGraphics.dispose()
            }
        }
    }






    override fun getToolTipText(event: MouseEvent): String? {
        val model = graph ?: return null
        val point = toModelPoint(event.point)
        val visualNodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
        if (visualNodeId != null) {
            val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
            return model.node(sourceNodeId(layout, visualNodeId))?.detail
        }

        val visualEdge = findEdgeAt(model, point) ?: return null
        val edge = visualEdge.source
        val source = navigationSource(model, edge)
        val where = source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
        return "Click to highlight this visual flow • double-click to open ${edgeDisplayLabel(edge)}$where"
    }



    internal data class CircuitRouteSeed(
        val edge: FlowEdge,
        val points: List<Point>,
        val preferredTrunkX: Int,
        val meanY: Int,
    )











































    internal companion object {
        const val EDGE_HIT_DISTANCE = 12.0
        const val DRAW_CULL_MARGIN = 80
        const val MIN_ZOOM = 0.001
        const val MAX_ZOOM = 100.0
        const val ZOOM_STEP = 1.18
        // Wheel/trackpad zoom is intentionally finer than +/- toolbar zoom. exp(0.075) ~= 1.078,
        // so a traditional one-notch wheel changes scale by ~8% instead of 18%, while precise
        // trackpad rotations remain fully fractional.
        const val WHEEL_ZOOM_SENSITIVITY = 0.075
        const val WHEEL_ZOOM_FRAME_MS = 16
        const val MIN_VIRTUAL_CANVAS_PADDING = 320
        const val PAN_DRAG_THRESHOLD = 3
        const val FIT_PADDING = 18
        const val PROJECT_CLUSTER_PADDING = 24
        const val PROJECT_CLUSTER_HEADER = 42

        // Dense project overview. Nodes are intentionally smaller than focused-flow nodes and the
        // overview is spatially compressed enough to keep the same hard 20% zoom floor as focused graphs.
        const val PROJECT_OVERVIEW_NODE_W = 150
        const val PROJECT_OVERVIEW_NODE_H = 42
        const val PROJECT_OVERVIEW_HORIZONTAL_GAP = 62
        const val PROJECT_OVERVIEW_VERTICAL_GAP = 14
        const val PROJECT_OVERVIEW_CLUSTER_PADDING = 12
        const val PROJECT_OVERVIEW_CLUSTER_HEADER = 28
        const val PROJECT_OVERVIEW_CLUSTER_GAP = 44
        const val PROJECT_OVERVIEW_OUTER_PADDING = 14
        const val PROJECT_OVERVIEW_MIN_ZOOM = 0.001
        const val PROJECT_OVERVIEW_MAX_GRID_COLUMNS = 18
        const val PROJECT_OVERVIEW_MAX_CLUSTER_NODES = 24
        const val PROJECT_OVERVIEW_COMPOSE_CONTEXT_NODES = 12
        const val PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS = 6

        // Compose overview is intentionally a forest of screen-oriented horizontal lanes rather
        // than one globally de-duplicated DAG. Shared nodes are repeated per lane for readability.
        const val PROJECT_OVERVIEW_COMPOSE_HORIZONTAL_GAP = 74
        const val PROJECT_OVERVIEW_COMPOSE_LANE_GAP = 26
        const val PROJECT_OVERVIEW_COMPOSE_SECTION_GAP = 70
        const val PROJECT_OVERVIEW_COMPOSE_PREFIX_DEPTH = 3
        const val PROJECT_OVERVIEW_COMPOSE_INFLUENCE_GAP = 36

        // Concise Compose lanes render collapsed Flow propagation as a StateFlow state machine.
        // State nodes are de-duplicated inside a lane and transition left-to-right before the
        // terminal/observed states fan into the Compose tree.
        const val PROJECT_OVERVIEW_STATE_MACHINE_HORIZONTAL_GAP = 54
        const val PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP = 18
        const val PROJECT_OVERVIEW_STATE_MACHINE_TO_COMPOSE_GAP = 48
        const val MAX_PROJECT_STATE_MACHINE_DEPTH = 10
        const val MAX_PROJECT_STATE_MACHINE_NODES = 72

        // Project-wide Flow overview uses consumer-oriented trees instead of one de-duplicated DAG.
        const val PROJECT_OVERVIEW_FLOW_HORIZONTAL_GAP = 74
        const val PROJECT_OVERVIEW_FLOW_LANE_GAP = 24
        const val PROJECT_OVERVIEW_FLOW_SECTION_GAP = 54
        const val PROJECT_OVERVIEW_FLOW_TREE_GAP = 12
        const val PROJECT_OVERVIEW_FLOW_FOREST_GAP = 18
        const val MAX_PROJECT_FLOW_LANES = 64
        const val MAX_PROJECT_FLOW_LANE_DEPTH = 12
        const val MAX_PROJECT_FLOW_INSTANCES_PER_LANE = 220

        const val MAX_PROJECT_COMPOSE_INFLUENCE_DEPTH = 10
        const val MAX_PROJECT_COMPOSE_INFLUENCE_BRIDGE_DEPTH = 6
        const val MAX_PROJECT_COMPOSE_INFLUENCE_NODES = 72
        const val MAX_PROJECT_COMPOSE_LANES = 48
        const val MAX_PROJECT_COMPOSE_INSTANCES_PER_LANE = 320
        const val MAX_PROJECT_COMPOSE_DESCENDANT_DEPTH = 24
    }




}
