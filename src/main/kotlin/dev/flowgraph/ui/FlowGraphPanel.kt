package dev.flowgraph.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import dev.flowgraph.analysis.AnalysisApiFlowAnalyzer
import dev.flowgraph.model.*
import dev.flowgraph.service.FlowGraphProjectService
import dev.flowgraph.service.LiveTraceService
import dev.flowgraph.service.LiveTraceStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import kotlin.math.max
import kotlin.math.sqrt

private const val RUNTIME_ACTIVITY_REPAINT_MS = 100
private const val RUNTIME_ACTIVITY_HOT_MS = 700L
private const val RUNTIME_ACTIVITY_COOLDOWN_MS = 2_500L

private fun isAllProjectFlows(label: String?): Boolean =
    label?.startsWith("All project flows") == true

class FlowGraphPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val title = JLabel("Put the caret on a Flow/StateFlow and choose Show Flow Graph")
    private val hint = JLabel("Click a node to isolate its causal cone • wheel to zoom • drag to pan • click an edge to open its call site")
    private val showOperators = JToggleButton("Show operators").apply {
        toolTipText = "Expand Flow-to-Flow propagation arrows into operators, collectors, behaviors and write sites"
    }
    private val showReads = JToggleButton("Reads").apply {
        isSelected = true
        toolTipText = "Show read-only observers in a separate graph cluster"
    }
    private val includePossible = JToggleButton("Possible").apply {
        isSelected = false
        toolTipText = "Allow inferred/possible coupling to expand the causal cone. Off by default to prevent graph flooding."
    }
    private val expandLibraries = JToggleButton("Expand libs").apply {
        isSelected = false
        toolTipText = "Expand framework/dependency Flow nodes instead of collapsing them into boundary clusters"
    }
    private val collapseCycles = JToggleButton("Collapse cycles").apply {
        isSelected = true
        toolTipText = "Collapse strongly connected state cycles into compact nodes"
    }
    private val depth = JComboBox(arrayOf("1", "2", "3", "All")).apply {
        selectedItem = "3"
        toolTipText = "Maximum causal distance shown upstream/downstream from the selected node"
        preferredSize = Dimension(58, preferredSize.height)
    }
    private val fieldFocus = JComboBox(arrayOf("All fields")).apply {
        isEnabled = false
        toolTipText = "Restrict the selected StateFlow to one discovered state field"
        preferredSize = Dimension(130, preferredSize.height)
    }
    private val allFlows = JButton("All flows").apply {
        toolTipText = "Analyze every project Flow/StateFlow/SharedFlow and show disconnected groups as separate clusters"
    }
    private val showAll = JButton("Show all").apply { isEnabled = false }
    private val affected = JButton("Affected ↓").apply { isEnabled = false }
    private val causes = JButton("Causes ↑").apply { isEnabled = false }
    private val fieldWriters = JButton("Field writers").apply { isEnabled = false }
    private val simulate = JButton("▶ Simulate").apply {
        isEnabled = false
        toolTipText = "Symbolically simulate an emission from the selected Flow/StateFlow"
    }
    private val stopSimulation = JButton("■").apply {
        isEnabled = false
        toolTipText = "Stop / clear simulation overlay"
        margin = Insets(2, 8, 2, 8)
    }
    private val liveTrace = JToggleButton("Live trace").apply {
        toolTipText = "Listen on localhost:50737 for debug runtime Flow/StateFlow events"
    }
    private val recentOnly = JToggleButton("Recently active").apply {
        toolTipText = "Show only nodes active in the last 2.5s, plus short connector paths between them"
    }
    private val liveDiagnostics = JTextArea(5, 88).apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Live trace diagnostics"),
            BorderFactory.createEmptyBorder(3, 6, 3, 6),
        )
        background = UIManager.getColor("Panel.background")
        isVisible = false
    }
    private val clearRuntime = JButton("Clear live").apply {
        isEnabled = false
        toolTipText = "Clear runtime emission counts and observed paths"
    }
    private val zoomOut = JButton("−").apply {
        toolTipText = "Zoom out"
        margin = Insets(2, 8, 2, 8)
    }
    private val zoomReset = JButton("100%").apply {
        toolTipText = "Reset zoom to 100%"
        margin = Insets(2, 8, 2, 8)
    }
    private val zoomFit = JButton("Fit").apply {
        toolTipText = "Fit the whole graph in the viewport"
        margin = Insets(2, 8, 2, 8)
    }
    private val zoomIn = JButton("+").apply {
        toolTipText = "Zoom in"
        margin = Insets(2, 8, 2, 8)
    }
    private val expandLeft = JButton("<").apply {
        isVisible = false
        isEnabled = false
        toolTipText = "Expand one more upstream causal level"
        margin = Insets(8, 8, 8, 8)
        preferredSize = Dimension(34, 58)
        font = font.deriveFont(Font.BOLD, 18f)
    }
    private val expandRight = JButton(">").apply {
        isVisible = false
        isEnabled = false
        toolTipText = "Expand one more downstream causal level"
        margin = Insets(8, 8, 8, 8)
        preferredSize = Dimension(34, 58)
        font = font.deriveFont(Font.BOLD, 18f)
    }

    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "Select a Flow/StateFlow to see which other flows it can affect, runtime deliveries, side-effect writes, collectors and upstream causes."
    }

    private var fullGraph: FlowGraph? = null
    /** Full-detail graph currently in scope. The visible graph may be its collapsed state projection. */
    private var activeGraph: FlowGraph? = null
    private var selectedNodeId: String? = null
    private var selectionFocusActive: Boolean = false
    /** Independent causal radius for the focused detail map. Null means all levels. */
    private var detailUpstreamDepth: Int? = 3
    private var detailDownstreamDepth: Int? = 3
    private var simulationResult: SimulationResult? = null
    private var simulationStep: Int = 0
    private var simulationTimer: Timer? = null
    private var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
    private var historicalRuntimeOverlay: RuntimeOverlay? = null
    private var historyCursorMillis: Long? = null
    private val recentRuntimeEmissionNanos = mutableMapOf<String, Long>()
    private val liveTraceService: LiveTraceService = project.getService(LiveTraceService::class.java)
    private val runtimeTimeline = RuntimeTimelinePanel(
        onScrubStarted = ::pauseRuntimeRecordingForHistory,
        onScrubbed = ::onTimelineScrubbed,
    )
    private var liveTransportStatus: LiveTraceStatus = liveTraceService.status()
    private var runtimeEventsSeen: Int = 0
    private var matchedRuntimeEvents: Int = 0
    private var unmatchedRuntimeEvents: Int = 0
    private val unmatchedRuntimeKeys = linkedMapOf<String, Int>()
    private var lastRecentFilterIds: Set<String> = emptySet()
    private var lastRecentFilterRefreshMillis: Long = 0L

    // Deep Flow instrumentation can produce thousands of deliveries per second. Never enqueue
    // one Swing Runnable per event: that starves the EDT and can make Android Studio appear hung.
    private val pendingRuntimeEvents = ConcurrentLinkedQueue<RuntimeTraceEvent>()
    private val pendingRuntimeEventCount = AtomicInteger(0)
    private val droppedRuntimeUiEvents = AtomicLong(0)
    private val runtimeFlushTimer = Timer(LIVE_UI_FLUSH_MS) { flushRuntimeEvents() }.apply {
        isRepeats = true
        start()
    }
    private val liveTraceListener: (RuntimeTraceEvent) -> Unit = { event ->
        pendingRuntimeEvents.add(event)
        val queued = pendingRuntimeEventCount.incrementAndGet()
        if (queued > MAX_PENDING_RUNTIME_EVENTS) {
            if (pendingRuntimeEvents.poll() != null) {
                pendingRuntimeEventCount.decrementAndGet()
                droppedRuntimeUiEvents.incrementAndGet()
            }
        }
    }
    private val liveStatusListener: (LiveTraceStatus) -> Unit = { status ->
        SwingUtilities.invokeLater {
            liveTransportStatus = status
            updateLiveTraceDiagnostics()
        }
    }
    private val canvas = GraphCanvas(
        project = project,
        onNodeSelected = ::selectNode,
        onZoomChanged = { zoom ->
            zoomReset.text = "${(zoom * 100).toInt()}%"
        },
    )
    /**
     * Runtime activity is intentionally transient. Counts/history remain, but the cyan "hot"
     * outline fades away after a short cooldown so old emissions do not look permanently active.
     * This timer only runs while at least one node is cooling down.
     */
    private val runtimeActivityCooldownTimer = Timer(RUNTIME_ACTIVITY_REPAINT_MS) { event ->
        canvas.repaint()
        val now = System.currentTimeMillis()
        if (recentOnly.isSelected) refreshRecentActivityFilterIfNeeded(now = now)
        val stillCooling = runtimeOverlay.lastActivityAtMillis.values.any {
            now - it <= RUNTIME_ACTIVITY_COOLDOWN_MS
        }
        if (!stillCooling) (event.source as? Timer)?.stop()
    }.apply { isRepeats = true }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // Use a width-aware wrapping layout. Keeping this panel in BorderLayout.SOUTH gives it
        // the full tool-window width; when controls no longer fit, they continue on the next row
        // instead of being clipped off-screen with no horizontal scrollbar.
        val actions = JPanel(WrapLayout(FlowLayout.RIGHT, 6, 4)).apply {
            add(zoomOut)
            add(zoomReset)
            add(zoomFit)
            add(zoomIn)
            add(showReads)
            add(includePossible)
            add(JLabel("Depth"))
            add(depth)
            add(JLabel("Field"))
            add(fieldFocus)
            add(collapseCycles)
            add(expandLibraries)
            add(showOperators)
            add(liveTrace)
            add(recentOnly)
            add(clearRuntime)
            add(stopSimulation)
            add(simulate)
            add(fieldWriters)
            add(causes)
            add(affected)
            add(allFlows)
            add(showAll)
        }
        val header = JPanel(BorderLayout(8, 4)).apply {
            border = BorderFactory.createEmptyBorder(0, 2, 8, 2)
            val text = JPanel(GridLayout(0, 1, 0, 2)).apply {
                isOpaque = false
                add(title)
                add(hint)
            }
            add(text, BorderLayout.NORTH)
            add(actions, BorderLayout.SOUTH)
        }

        // WrapLayout calculates its height from the current available width. Revalidate the
        // header whenever the tool window changes width so newly wrapped/unwrapped rows get space.
        header.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                actions.revalidate()
                header.revalidate()
            }
        })

        zoomOut.addActionListener { canvas.zoomOut() }
        zoomReset.addActionListener { canvas.resetZoom() }
        zoomFit.addActionListener { canvas.fitToViewport() }
        zoomIn.addActionListener { canvas.zoomIn() }

        showReads.addActionListener { renderActive(autoFit = selectionFocusActive) }
        includePossible.addActionListener { renderActive(autoFit = selectionFocusActive) }
        depth.addActionListener {
            if (selectionFocusActive) resetDetailExpansionDepths()
            renderActive(autoFit = selectionFocusActive)
        }
        fieldFocus.addActionListener {
            if (fieldFocus.isEnabled) renderActive(autoFit = selectionFocusActive)
        }
        collapseCycles.addActionListener { renderActive(autoFit = selectionFocusActive) }
        expandLibraries.addActionListener { renderActive(autoFit = selectionFocusActive) }
        showOperators.addActionListener {
            hint.text = if (showOperators.isSelected) {
                "Full chain view • click a node to isolate its causal cone • wheel to zoom • click edges to open call sites"
            } else {
                "State changes view • click a node to isolate its causal cone • wheel to zoom • click edges to open causal call sites"
            }
            renderActive()
        }
        allFlows.addActionListener { analyzeAllFlows() }
        showAll.addActionListener { restoreFullGraph() }
        affected.addActionListener { focusDownstream() }
        causes.addActionListener { focusUpstream() }
        expandLeft.addActionListener { expandDetail(upstream = true) }
        expandRight.addActionListener { expandDetail(upstream = false) }
        fieldWriters.addActionListener { focusFieldWriters() }
        simulate.addActionListener { startSimulation() }
        stopSimulation.addActionListener { clearSimulation() }
        liveTrace.addActionListener { toggleLiveTrace() }
        recentOnly.addActionListener {
            lastRecentFilterIds = if (recentOnly.isSelected) recentActiveNodeIds() else emptySet()
            lastRecentFilterRefreshMillis = System.currentTimeMillis()
            if (recentOnly.isSelected) {
                // Recent activity is a visibility layer over the existing map, not a new layout.
                // Freeze the currently visible coordinates before filtering so nodes never jump
                // while runtime activity appears/expires.
                canvas.freezeCurrentLayout()
            } else {
                canvas.clearFrozenLayout()
            }
            renderActive(autoFit = false)
        }
        clearRuntime.addActionListener { clearRuntimeOverlay() }
        liveTraceService.addListener(liveTraceListener)
        liveTraceService.addStatusListener(liveStatusListener)

        val graphScroll = JScrollPane(canvas)
        val leftExpander = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 0, 4)
            add(expandLeft)
        }
        val rightExpander = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
            add(expandRight)
        }
        val graphWithExpanders = JPanel(BorderLayout()).apply {
            add(leftExpander, BorderLayout.WEST)
            add(graphScroll, BorderLayout.CENTER)
            add(rightExpander, BorderLayout.EAST)
        }
        val detailsScroll = JScrollPane(details).apply {
            preferredSize = Dimension(360, 220)
            minimumSize = Dimension(270, 120)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphWithExpanders, detailsScroll).apply {
            resizeWeight = 0.76
            isContinuousLayout = true
            dividerSize = 7
        }

        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(liveDiagnostics, BorderLayout.SOUTH)
        }

        val center = JPanel(BorderLayout()).apply {
            add(split, BorderLayout.CENTER)
            add(runtimeTimeline, BorderLayout.SOUTH)
        }

        runtimeTimeline.setEvents(liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS))

        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    private fun analyzeAllFlows() {
        if (DumbService.isDumb(project)) {
            hint.text = "Wait until indexing finishes, then choose All flows again."
            return
        }

        allFlows.isEnabled = false
        allFlows.text = "Scanning…"
        title.text = "Scanning all project Flow / StateFlow / SharedFlow properties…"
        hint.text = "Project-wide analysis runs in the background and can be cancelled by normal IDE write actions."

        ReadAction.nonBlocking<FlowGraph> {
            AnalysisApiFlowAnalyzer(project).analyzeAllProjectFlows()
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { graph ->
                allFlows.text = "All flows"
                allFlows.isEnabled = true
                if (graph.nodes.isEmpty()) {
                    title.text = "All project flows • none found"
                    hint.text = graph.diagnostics.joinToString(" • ").ifBlank { "No project Flow properties found." }
                    details.text = graph.diagnostics.joinToString("\n")
                } else {
                    project.getService(FlowGraphProjectService::class.java).publish(graph)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun setGraph(graph: FlowGraph) {
        fullGraph = graph
        runtimeOverlay = RuntimeOverlay.EMPTY
        historicalRuntimeOverlay = null
        recentRuntimeEmissionNanos.clear()
        lastRecentFilterIds = emptySet()
        lastRecentFilterRefreshMillis = 0L
        resetRuntimeMatchingDiagnostics()
        activeGraph = graph
        showAll.isEnabled = false
        showOperators.isSelected = false
        selectedNodeId = null
        selectionFocusActive = false
        resetDetailExpansionDepths()
        fieldFocus.removeAllItems()
        fieldFocus.addItem("All fields")
        fieldFocus.isEnabled = false
        canvas.selectedId = null
        runtimeTimeline.setRelevantRuntimeKeys(graph.nodes.mapNotNullTo(linkedSetOf()) { it.runtimeKey })
        clearSimulation(render = false)
        renderActive(autoFit = isAllProjectFlows(graph.rootLabel))
        replayRuntimeHistory()
        updateLiveTraceDiagnostics()
        details.text = if (isAllProjectFlows(graph.rootLabel)) {
            "Project-wide Flow view. Disconnected Flow groups are laid out as separate clusters. Select any node to isolate its causal cone; Show all restores the complete project graph."
        } else {
            "Select a node to isolate its causal cone. Only upstream/downstream connections will remain and the graph will re-layout compactly. Live history is remapped automatically when you switch flows."
        }
        historyCursorMillis?.let { onTimelineScrubbed(it) }
    }

    private fun selectNode(nodeId: String) {
        clearSimulation(render = false)
        selectedNodeId = nodeId
        selectionFocusActive = true
        resetDetailExpansionDepths()
        showAll.isEnabled = true
        updateFieldFocusChoices(nodeId)
        hint.text = "Focused detail • < expands one cause level • > expands one affected level • existing nodes stay anchored"
        renderActive(autoFit = true)
    }

    private fun focusDownstream() {
        val graph = fullGraph ?: return
        val id = selectedNodeId ?: return
        activeGraph = graph.focusDownstream(id, selectedDepth(), includePossible.isSelected)
        selectionFocusActive = false
        showAll.isEnabled = activeGraph?.nodes?.size != graph.nodes.size
        renderActive(autoFit = true)
    }

    private fun focusUpstream() {
        val graph = fullGraph ?: return
        val id = selectedNodeId ?: return
        activeGraph = graph.focusUpstream(id, selectedDepth(), includePossible.isSelected)
        selectionFocusActive = false
        showAll.isEnabled = activeGraph?.nodes?.size != graph.nodes.size
        renderActive(autoFit = true)
    }

    private fun focusFieldWriters() {
        val graph = fullGraph ?: return
        val id = selectedNodeId ?: return
        activeGraph = graph.focusOnField(id)
        selectionFocusActive = false
        showAll.isEnabled = activeGraph?.nodes?.size != graph.nodes.size
        showOperators.isSelected = true
        renderActive(autoFit = true)
    }

    private fun restoreFullGraph() {
        clearSimulation(render = false)
        activeGraph = fullGraph ?: return
        selectionFocusActive = false
        selectedNodeId = null
        resetDetailExpansionDepths()
        fieldFocus.removeAllItems()
        fieldFocus.addItem("All fields")
        fieldFocus.isEnabled = false
        canvas.selectedId = null
        showAll.isEnabled = false
        renderActive(autoFit = true)
        hint.text = "Click a node to isolate its causal cone • wheel to zoom • drag to pan • click an edge to open its call site"
        details.text = "Select a node to isolate its causal cone. Only upstream/downstream connections will remain and the graph will re-layout compactly."
    }

    private fun renderActive(
        autoFit: Boolean = false,
        preserveFocusedViewport: Boolean = false,
    ) {
        val base = activeGraph ?: return
        val readFiltered = if (showReads.isSelected) base else base.withoutReads()
        var projected = if (showOperators.isSelected) {
            readFiltered
        } else {
            readFiltered.stateChangeProjection(includeReads = showReads.isSelected)
        }

        val selected = selectedNodeId
        if (selected != null && projected.node(selected) == null) {
            selectedNodeId = null
            selectionFocusActive = false
        }

        // Collapse framework/dependency internals before causal traversal. This prevents a framework
        // state such as Recomposer._state from acting as a highway that floods the selected cone.
        if (!expandLibraries.isSelected) {
            val projectBase = project.basePath?.let { java.io.File(it).canonicalFile.path }
            projected = projected.collapseExternalNodes(
                isProjectNode = { node ->
                    when {
                        node.id == selectedNodeId -> true
                        node.kind in setOf(NodeKind.READ, NodeKind.CYCLE, NodeKind.CLUSTER) -> true
                        else -> {
                            val path = node.source?.file?.path
                            val key = node.runtimeKey.orEmpty()
                            val frameworkKey = key.startsWith("androidx.") ||
                                key.startsWith("android.") ||
                                key.startsWith("kotlinx.") ||
                                key.startsWith("java.") ||
                                key.startsWith("kotlin.")
                            when {
                                path != null && projectBase != null -> {
                                    try {
                                        java.io.File(path).canonicalFile.path.startsWith(projectBase)
                                    } catch (_: Throwable) {
                                        path.startsWith(projectBase)
                                    }
                                }
                                frameworkKey -> false
                                else -> true
                            }
                        }
                    }
                },
                classifyExternal = { node ->
                    val key = node.runtimeKey.orEmpty() + " " + node.detail
                    when {
                        "androidx." in key || "android." in key -> "AndroidX / Android"
                        "kotlinx.coroutines" in key -> "kotlinx.coroutines"
                        "kotlin." in key || "java." in key -> "Kotlin / Java runtime"
                        else -> "Dependencies"
                    }
                },
            )
        }

        val field = selectedField()

        var visible = if (selectionFocusActive) {
            focusedDetailGraph(projected, field)
        } else {
            projected
        }

        if (recentOnly.isSelected) {
            visible = visible.focusRecentActivity(recentActiveNodeIds())
        }

        if (collapseCycles.isSelected) {
            visible = visible.collapseCausalCycles(preserveNodeId = selectedNodeId)
        }

        canvas.selectedId = selectedNodeId
        canvas.compactLayout = selectionFocusActive
        canvas.includePossibleTraversal = includePossible.isSelected
        canvas.simulationResult = simulationResult
        canvas.simulationStep = simulationStep
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        canvas.runtimeReferenceMillis = historyCursorMillis
        updateDetailExpansionControls(projected, field)
        showAll.isEnabled = selectionFocusActive || base !== fullGraph

        val focusedLabel = when {
            recentOnly.isSelected -> "Recently active (${recentActiveNodeIds().size})"
            selectionFocusActive -> {
                val nodeLabel = selectedNodeId?.let { projected.node(it)?.label }
                val baseLabel = if (field != null && nodeLabel != null) "$nodeLabel.$field" else nodeLabel
                baseLabel?.let { "$it • ←${depthLabel(detailUpstreamDepth)} | ${depthLabel(detailDownstreamDepth)}→" }
            }
            base !== fullGraph -> base.rootLabel
            else -> null
        }
        publishGraph(visible, focusedLabel)
        if ((autoFit || selectionFocusActive) && !preserveFocusedViewport) {
            SwingUtilities.invokeLater { canvas.fitToViewport() }
        }
        val historyCursor = historyCursorMillis
        if (historyCursor != null) updateHistoricalDetails(historyCursor) else updateDetails()
    }

    private fun resetDetailExpansionDepths() {
        val initial = selectedDepth()
        detailUpstreamDepth = initial
        detailDownstreamDepth = initial
    }

    private fun depthLabel(value: Int?): String = value?.toString() ?: "all"

    private fun focusedDetailGraph(projected: FlowGraph, field: String?): FlowGraph {
        val id = selectedNodeId ?: return projected
        return if (field != null && projected.node(id)?.kind == NodeKind.STATE) {
            projected.focusStateFieldDirectional(
                stateId = id,
                fieldName = field,
                upstreamDepth = detailUpstreamDepth,
                downstreamDepth = detailDownstreamDepth,
                includePossible = includePossible.isSelected,
            )
        } else {
            projected.focusConnectionsDirectional(
                nodeId = id,
                upstreamDepth = detailUpstreamDepth,
                downstreamDepth = detailDownstreamDepth,
                includePossible = includePossible.isSelected,
                includePossibleContext = true,
            )
        }
    }

    private fun expandDetail(upstream: Boolean) {
        if (!selectionFocusActive || selectedNodeId == null) return
        val current = if (upstream) detailUpstreamDepth else detailDownstreamDepth
        if (current == null) return

        val anchor = canvas.captureViewportAnchor(selectedNodeId ?: return)
        if (upstream) detailUpstreamDepth = current + 1 else detailDownstreamDepth = current + 1
        renderActive(autoFit = false, preserveFocusedViewport = true)
        if (anchor != null) {
            SwingUtilities.invokeLater { canvas.restoreViewportAnchor(anchor) }
        }
    }

    private fun updateDetailExpansionControls(projected: FlowGraph, field: String?) {
        val id = selectedNodeId
        val show = selectionFocusActive && id != null && projected.node(id) != null
        expandLeft.isVisible = show
        expandRight.isVisible = show
        if (!show || id == null) {
            expandLeft.isEnabled = false
            expandRight.isEnabled = false
            return
        }

        val currentGraph = focusedDetailGraph(projected, field)
        val currentIds = currentGraph.nodes.mapTo(linkedSetOf()) { it.id }

        fun canExpand(upstream: Boolean): Boolean {
            val currentDepth = if (upstream) detailUpstreamDepth else detailDownstreamDepth
            if (currentDepth == null) return false
            val next = if (field != null && projected.node(id)?.kind == NodeKind.STATE) {
                projected.focusStateFieldDirectional(
                    stateId = id,
                    fieldName = field,
                    upstreamDepth = if (upstream) currentDepth + 1 else detailUpstreamDepth,
                    downstreamDepth = if (upstream) detailDownstreamDepth else currentDepth + 1,
                    includePossible = includePossible.isSelected,
                )
            } else {
                projected.focusConnectionsDirectional(
                    nodeId = id,
                    upstreamDepth = if (upstream) currentDepth + 1 else detailUpstreamDepth,
                    downstreamDepth = if (upstream) detailDownstreamDepth else currentDepth + 1,
                    includePossible = includePossible.isSelected,
                    includePossibleContext = true,
                )
            }
            return next.nodes.any { it.id !in currentIds }
        }

        val canLeft = canExpand(upstream = true)
        val canRight = canExpand(upstream = false)
        expandLeft.isEnabled = canLeft
        expandRight.isEnabled = canRight
        expandLeft.toolTipText = if (canLeft) {
            "Expand one upstream causal level (currently ${depthLabel(detailUpstreamDepth)})"
        } else {
            "No more upstream causal levels"
        }
        expandRight.toolTipText = if (canRight) {
            "Expand one downstream causal level (currently ${depthLabel(detailDownstreamDepth)})"
        } else {
            "No more downstream causal levels"
        }
    }

    private fun displayedRuntimeOverlay(): RuntimeOverlay = historicalRuntimeOverlay ?: runtimeOverlay

    private fun selectedDepth(): Int? = when (depth.selectedItem?.toString()) {
        "1" -> 1
        "2" -> 2
        "3" -> 3
        else -> null
    }

    private fun selectedField(): String? =
        fieldFocus.selectedItem?.toString()?.takeUnless { it == "All fields" }

    private fun updateFieldFocusChoices(nodeId: String) {
        val graph = fullGraph ?: return
        val node = graph.node(nodeId)
        val previous = fieldFocus.selectedItem?.toString()
        fieldFocus.removeAllItems()
        fieldFocus.addItem("All fields")
        if (node?.kind == NodeKind.STATE) {
            val discovered = linkedSetOf<String>()
            graph.fieldsForState(nodeId).mapTo(discovered) { it.label }
            graph.transitionsFrom(nodeId).flatMapTo(discovered) { it.affectedFields }
            graph.transitionsTo(nodeId).flatMapTo(discovered) { it.affectedFields }
            graph.fieldDependencies
                .filter { it.outputStateId == nodeId }
                .mapTo(discovered) { it.outputField }
            discovered.remove("\$value")
            discovered.filter { it.isNotBlank() }.sorted().forEach(fieldFocus::addItem)
        }
        fieldFocus.isEnabled = fieldFocus.itemCount > 1
        if (previous != null && (0 until fieldFocus.itemCount).any { fieldFocus.getItemAt(it) == previous }) {
            fieldFocus.selectedItem = previous
        } else {
            fieldFocus.selectedIndex = 0
        }
    }

    private fun publishGraph(graph: FlowGraph, focusedLabel: String? = null) {
        val extra = if (graph.diagnostics.isEmpty()) "" else " • ${graph.diagnostics.size} note(s)"
        val mode = if (showOperators.isSelected) "full chain" else "state changes"
        title.text = if (focusedLabel == null) {
            "${fullGraph?.rootLabel ?: graph.rootLabel} • $mode • ${graph.nodes.size} nodes • ${graph.edges.size} connections$extra"
        } else {
            "$focusedLabel • $mode • ${graph.nodes.size} nodes • ${graph.edges.size} connections"
        }
        canvas.graph = graph
        canvas.relayout()
    }

    private fun updateDetails() {
        val graph = fullGraph ?: return
        val id = selectedNodeId
        if (id == null) {
            affected.isEnabled = false
            causes.isEnabled = false
            fieldWriters.isEnabled = false
            simulate.isEnabled = false
            return
        }
        val impact = graph.impactFor(id) ?: return
        val node = impact.node
        val fieldCauses = graph.dependenciesForField(id)
        val fieldEffects = graph.fieldEffectsFrom(id)
            .distinctBy { listOf(it.sourceNodeId, it.sourceField, it.viaOperatorId, it.outputFieldId) }
        val stateChangesOut = if (node.kind == NodeKind.STATE) graph.transitionsFrom(id) else emptyList()
        val stateChangesIn = if (node.kind == NodeKind.STATE) graph.transitionsTo(id) else emptyList()
        val reads = if (node.kind == NodeKind.STATE) graph.readsFrom(id) else emptyList()
        val shownRuntime = displayedRuntimeOverlay()

        affected.isEnabled = impact.outgoing.isNotEmpty() ||
            impact.downstreamStates.isNotEmpty() || impact.downstreamCollectors.isNotEmpty() ||
            impact.downstreamBehaviors.isNotEmpty() || impact.downstreamReads.isNotEmpty() ||
            fieldEffects.isNotEmpty() || stateChangesOut.isNotEmpty()
        causes.isEnabled = impact.incoming.isNotEmpty() ||
            impact.upstreamWriters.isNotEmpty() || impact.upstreamStates.isNotEmpty() ||
            fieldCauses.isNotEmpty() || stateChangesIn.isNotEmpty()
        fieldWriters.isEnabled = node.kind == NodeKind.FIELD
        simulate.isEnabled = node.kind == NodeKind.STATE

        details.text = buildString {
            appendLine(node.label)
            appendLine(node.kind.name.lowercase().replace('_', ' '))
            appendLine(node.detail)
            node.source?.let { appendLine("${it.file.path}:${it.line + 1}") }
            node.runtimeKey?.let { appendLine("runtime key: $it") }
            shownRuntime.nodeCounts[node.id]?.let { count -> appendLine("runtime emissions: $count") }
            shownRuntime.deliveryCounts[node.id]?.let { count -> appendLine("runtime deliveries: $count") }
            shownRuntime.collectCounts[node.id]?.let { count -> appendLine("runtime collections: $count") }
            shownRuntime.emitRequestCounts[node.id]?.let { count -> appendLine("suspending emit requests: $count") }
            shownRuntime.readCounts[node.id]?.let { count -> appendLine("runtime reads: $count") }
            shownRuntime.lastEventByNode[node.id]?.valueSummary?.let { appendLine("last value: $it") }

            if (node.kind == NodeKind.STATE) {
                appendLine()
                appendLine("FLOW / STATE PROPAGATION OUT (${stateChangesOut.size})")
                appendTransitions(graph, stateChangesOut, outgoing = true)

                appendLine()
                appendLine("FLOW / STATE PROPAGATION IN (${stateChangesIn.size})")
                appendTransitions(graph, stateChangesIn, outgoing = false)

                appendLine()
                appendLine("READ-ONLY OBSERVERS (${reads.size})")
                appendNodeList(reads)
            }

            if (fieldCauses.isNotEmpty()) {
                appendLine()
                appendLine("FIELD PROVENANCE (${fieldCauses.size})")
                fieldCauses.take(MAX_DETAIL_ITEMS).forEach { dependency ->
                    val source = graph.node(dependency.sourceNodeId)
                    val operator = graph.node(dependency.viaOperatorId)
                    val sourceField = dependency.sourceField?.let { ".$it" } ?: " (whole value)"
                    val confidence = dependency.confidence.name.lowercase()
                    appendLine(
                        "  ${source?.label ?: "input"}$sourceField → ${node.label}" +
                            " via ${operator?.label ?: "operator"} [$confidence]",
                    )
                }
            }

            if (fieldEffects.isNotEmpty()) {
                appendLine()
                appendLine("FIELDS AFFECTED DOWNSTREAM (${fieldEffects.size})")
                fieldEffects.take(MAX_DETAIL_ITEMS).forEach { dependency ->
                    val outputState = graph.node(dependency.outputStateId)
                    val operator = graph.node(dependency.viaOperatorId)
                    val sourceField = dependency.sourceField?.let { ".$it" } ?: ""
                    val outputField = if (dependency.outputField == "\$value") "value" else dependency.outputField
                    appendLine(
                        "  ${node.label}$sourceField → ${outputState?.label ?: "derived"}.$outputField" +
                            " via ${operator?.label ?: "operator"}",
                    )
                }
                if (fieldEffects.size > MAX_DETAIL_ITEMS) {
                    appendLine("  … +${fieldEffects.size - MAX_DETAIL_ITEMS} more")
                }
            }

            appendLine()
            appendLine("ALL AFFECTED FLOWS / STATE (${impact.downstreamStates.size})")
            appendNodeList(impact.downstreamStates)

            appendLine()
            appendLine("AFFECTED COLLECTORS (${impact.downstreamCollectors.size})")
            appendNodeList(impact.downstreamCollectors)

            appendLine()
            appendLine("CAUSAL BEHAVIORS (${impact.downstreamBehaviors.size})")
            appendNodeList(impact.downstreamBehaviors)

            appendLine()
            appendLine("READS / OBSERVERS (${impact.downstreamReads.size})")
            appendNodeList(impact.downstreamReads)

            appendLine()
            appendLine("UPSTREAM WRITERS (${impact.upstreamWriters.size})")
            appendNodeList(impact.upstreamWriters)

            if (showOperators.isSelected) {
                appendLine()
                appendLine("DIRECT INPUTS (${impact.incoming.size})")
                if (impact.incoming.isEmpty()) appendLine("  —")
                impact.incoming.forEach { (edge, other) ->
                    appendLine("  ${other.label} --${edge.pretty()}→ ${node.label}")
                }

                appendLine()
                appendLine("DIRECT OUTPUTS (${impact.outgoing.size})")
                if (impact.outgoing.isEmpty()) appendLine("  —")
                impact.outgoing.forEach { (edge, other) ->
                    appendLine("  ${node.label} --${edge.pretty()}→ ${other.label}")
                }
            }
        }.trimEnd()
        details.caretPosition = 0
    }


    private fun startSimulation() {
        val graph = fullGraph ?: return
        val stateId = selectedNodeId ?: return
        val state = graph.node(stateId)?.takeIf { it.kind == NodeKind.STATE } ?: return
        val fields = graph.fieldsForState(stateId)
        val fieldOptions = mutableListOf("<whole value>").apply { addAll(fields.map { it.label }) }
        val combo = JComboBox(fieldOptions.toTypedArray())
        val equal = JCheckBox("Equal assignment (StateFlow only)").apply {
            isEnabled = state.detail.contains("StateFlow")
            toolTipText = "StateFlow suppresses equal values; ordinary cold Flow has no stored current value."
        }
        val panel = JPanel(GridLayout(0, 1, 4, 4)).apply {
            add(JLabel("Simulate emission from ${state.label}"))
            add(JLabel("Changed field (optional):"))
            add(combo)
            add(equal)
        }
        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            "Simulate Flow emission",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) return

        val field = combo.selectedItem?.toString()?.takeUnless { it == "<whole value>" }
        simulationResult = graph.simulate(
            SimulationSpec(
                sourceStateId = stateId,
                changedField = field,
                equalToCurrentValue = equal.isSelected,
            ),
        )
        simulationStep = 0
        stopSimulation.isEnabled = true
        showOperators.isSelected = false
        showOperators.isEnabled = false
        activeGraph = graph.focusDownstream(stateId)
        selectionFocusActive = false
        renderActive(autoFit = true)

        simulationTimer?.stop()
        val max = simulationResult?.maxSequence ?: 0
        if (max == 0) {
            canvas.simulationResult = simulationResult
            canvas.simulationStep = 0
            canvas.repaint()
            updateSimulationDetails()
            return
        }
        simulationTimer = Timer(430) {
            simulationStep += 1
            canvas.simulationStep = simulationStep
            canvas.repaint()
            updateSimulationDetails()
            if (simulationStep >= max) {
                simulationTimer?.stop()
            }
        }.also { it.start() }
        updateSimulationDetails()
    }

    private fun clearSimulation(render: Boolean = true) {
        simulationTimer?.stop()
        simulationTimer = null
        simulationResult = null
        simulationStep = 0
        stopSimulation.isEnabled = false
        showOperators.isEnabled = true
        canvas.simulationResult = null
        canvas.simulationStep = 0
        if (render && fullGraph != null) renderActive()
    }

    private fun updateSimulationDetails() {
        val sim = simulationResult ?: return
        val graph = fullGraph ?: return
        val source = graph.node(sim.spec.sourceStateId)
        details.text = buildString {
            appendLine("SIMULATION")
            appendLine("source: ${source?.label ?: sim.spec.sourceStateId}")
            appendLine("field: ${sim.spec.changedField ?: "<whole value>"}")
            if (sim.spec.equalToCurrentValue) appendLine("equal assignment: yes → source emission suppressed")
            appendLine("step: $simulationStep / ${sim.maxSequence}")
            appendLine()
            sim.hopsThrough(simulationStep).forEach { hop ->
                val from = graph.node(hop.sourceStateId)?.label ?: hop.sourceStateId
                val to = graph.node(hop.targetStateId)?.label ?: hop.targetStateId
                val fields = hop.targetFields.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " • $it" }.orEmpty()
                appendLine("${hop.sequence}. $from → $to$fields [${hop.status.pretty()}]")
                appendLine("   ${hop.note}")
            }
            if (sim.hopsThrough(simulationStep).isEmpty()) appendLine("No propagation reached yet.")
            if (sim.diagnostics.isNotEmpty()) {
                appendLine()
                appendLine("NOTES")
                sim.diagnostics.forEach { appendLine("  $it") }
            }
        }.trimEnd()
        details.caretPosition = 0
    }

    private fun SimulationStatus.pretty(): String = when (this) {
        SimulationStatus.DEFINITE -> "definite"
        SimulationStatus.CONDITIONAL -> "conditional"
        SimulationStatus.TIMING_DEPENDENT -> "timing"
        SimulationStatus.CANCELLATION_AWARE -> "latest/cancellation"
        SimulationStatus.POSSIBLE -> "possible"
        SimulationStatus.SUPPRESSED -> "suppressed"
    }

    private fun toggleLiveTrace() {
        if (liveTrace.isSelected) {
            liveDiagnostics.isVisible = true
            liveTraceService.start().onSuccess { port ->
                liveTrace.text = "Live :$port"
                hint.text = if (liveTraceService.isRecordingPaused()) {
                    "Live trace transport listening, but profiler recording is PAUSED • press LIVE on the timeline to resume"
                } else {
                    "Live trace listening • global debug instrumentation • switch existing flows without rebuilding • adb reverse tcp:$port tcp:$port"
                }
                liveTransportStatus = liveTraceService.status()
                updateLiveTraceDiagnostics()
            }.onFailure { error ->
                liveTrace.isSelected = false
                liveTrace.text = "Live trace"
                liveTransportStatus = liveTraceService.status()
                updateLiveTraceDiagnostics()
                JOptionPane.showMessageDialog(
                    this,
                    "Could not start Flow Graph live trace server: ${error.message}",
                    "Flow Graph",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        } else {
            liveTraceService.stop()
            liveTrace.text = "Live trace"
            liveTransportStatus = liveTraceService.status()
            updateLiveTraceDiagnostics()
        }
    }

    private fun clearRuntimeOverlay() {
        runtimeOverlay = RuntimeOverlay.EMPTY
        historicalRuntimeOverlay = null
        historyCursorMillis = null
        recentRuntimeEmissionNanos.clear()
        pendingRuntimeEvents.clear()
        pendingRuntimeEventCount.set(0)
        droppedRuntimeUiEvents.set(0)
        liveTraceService.clearRecentEvents()
        runtimeTimeline.clearHistory()
        resetRuntimeMatchingDiagnostics()
        clearRuntime.isEnabled = false
        canvas.runtimeOverlay = RuntimeOverlay.EMPTY
        canvas.runtimeReferenceMillis = null
        lastRecentFilterIds = emptySet()
        lastRecentFilterRefreshMillis = 0L
        if (recentOnly.isSelected) renderActive(autoFit = false) else canvas.repaint()
        updateLiveTraceDiagnostics()
    }

    private fun resetRuntimeMatchingDiagnostics() {
        runtimeEventsSeen = 0
        matchedRuntimeEvents = 0
        unmatchedRuntimeEvents = 0
        unmatchedRuntimeKeys.clear()
    }

    private fun updateLiveTraceDiagnostics() {
        val status = liveTransportStatus
        val shouldShow = liveTrace.isSelected || status.running || status.lastError != null ||
            runtimeEventsSeen > 0 || status.malformedLines > 0
        liveDiagnostics.isVisible = shouldShow
        if (!shouldShow) return

        val port = status.port ?: LiveTraceService.DEFAULT_PORT
        val serverLine = when {
            status.running && status.recordingPaused -> "⏸ Server listening :$port • RECORDING PAUSED"
            status.running -> "● Server listening :$port"
            else -> "○ Server stopped"
        }
        val clientLine = if (status.activeClients > 0) {
            "● Client connected (${status.activeClients}) • total connections: ${status.totalConnections}"
        } else if (status.running) {
            "○ Client waiting • run: adb reverse tcp:$port tcp:$port • then run an automatically instrumented debug build"
        } else {
            "○ No client"
        }
        val eventLine = "Events recorded: $runtimeEventsSeen • matched: $matchedRuntimeEvents • unmatched: $unmatchedRuntimeEvents" +
            " • UI queue=${pendingRuntimeEventCount.get()} • dropped=${droppedRuntimeUiEvents.get()}"
        val pauseLine = if (status.recordingPaused) {
            "Profiler PAUSED • retained history frozen • incoming ignored while paused=${status.ignoredWhilePaused} • press LIVE to resume"
        } else {
            "Profiler recording: LIVE"
        }
        val deepRuntimeLine = "Current graph: emit=${runtimeOverlay.nodeCounts.values.sum()} • " +
            "deliver=${runtimeOverlay.deliveryCounts.values.sum()} • collect=${runtimeOverlay.collectCounts.values.sum()} • " +
            "emit requests=${runtimeOverlay.emitRequestCounts.values.sum()} • read=${runtimeOverlay.readCounts.values.sum()}"
        val unmatchedLine = if (unmatchedRuntimeKeys.isEmpty()) {
            "Outside current graph: —"
        } else {
            val frameworkBuckets = linkedMapOf<String, Int>()
            val appKeys = mutableListOf<Map.Entry<String, Int>>()
            unmatchedRuntimeKeys.entries.forEach { entry ->
                val bucket = when {
                    entry.key.startsWith("androidx.") || entry.key.startsWith("android.") -> "AndroidX / Android"
                    entry.key.startsWith("kotlinx.coroutines") -> "kotlinx.coroutines"
                    entry.key.startsWith("kotlin.") || entry.key.startsWith("java.") -> "Kotlin / Java runtime"
                    else -> null
                }
                if (bucket == null) appKeys += entry
                else frameworkBuckets[bucket] = (frameworkBuckets[bucket] ?: 0) + entry.value
            }
            val parts = mutableListOf<String>()
            frameworkBuckets.forEach { (bucket, count) -> parts += "$bucket ×$count" }
            appKeys.take(MAX_UNMATCHED_KEYS).forEach { (key, count) -> parts += "$key ×$count" }
            if (appKeys.size > MAX_UNMATCHED_KEYS) parts += "+${appKeys.size - MAX_UNMATCHED_KEYS} app keys"
            "Outside current graph: ${parts.joinToString(" | ")}"
        }
        val transportLine = buildString {
            append("Transport: valid=${status.validEvents} • malformed=${status.malformedLines}")
            status.lastClient?.let { append(" • last client=$it") }
            status.lastError?.let { append(" • ERROR: $it") }
        }

        liveDiagnostics.text = listOf(serverLine, clientLine, eventLine, pauseLine, deepRuntimeLine, unmatchedLine, transportLine).joinToString("\n")
        liveDiagnostics.caretPosition = 0
        liveDiagnostics.revalidate()
        revalidate()
        repaint()
    }

    /**
     * Enter profiler-history mode. Pause the recorder first so no new events can mutate/evict the
     * retained history, then synchronously drain events that were already accepted before the
     * pause. Finally refresh the timeline from the service's frozen authoritative history.
     */
    private fun pauseRuntimeRecordingForHistory() {
        liveTraceService.pauseRecording()

        // Process everything already handed to the UI listener before the pause. The queue is
        // bounded, so this remains finite and avoids losing pre-pause overlay counts.
        while (true) {
            val event = pendingRuntimeEvents.poll() ?: break
            pendingRuntimeEventCount.decrementAndGet()
            onRuntimeEvent(event, updateDetails = false, refreshUi = false, markActivity = true)
        }

        // The service retains a larger authoritative history than the bounded UI queue, so rebuild
        // the profiler strip from it at the exact pause boundary. No pruning occurs while paused.
        runtimeTimeline.setEvents(liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS))
        liveTransportStatus = liveTraceService.status()
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        clearRuntime.isEnabled = liveTraceService.recentEvents(1).isNotEmpty()
        canvas.repaint()
        updateLiveTraceDiagnostics()
    }

    private fun flushRuntimeEvents() {
        var processed = 0
        val batch = ArrayList<RuntimeTraceEvent>(MAX_RUNTIME_EVENTS_PER_FLUSH)
        while (processed < MAX_RUNTIME_EVENTS_PER_FLUSH) {
            val event = pendingRuntimeEvents.poll() ?: break
            pendingRuntimeEventCount.decrementAndGet()
            batch += event
            onRuntimeEvent(event, updateDetails = false, refreshUi = false, markActivity = true)
            processed++
        }
        if (processed == 0) return

        runtimeTimeline.appendEvents(batch)

        // One UI update per batch rather than per Flow emission/delivery. Timeline scrubbing now
        // pauses the recorder itself, so this queue remains empty while the profiler is paused.
        liveTransportStatus = liveTraceService.status()
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        canvas.runtimeReferenceMillis = historyCursorMillis
        clearRuntime.isEnabled = true
        canvas.repaint()
        if (recentOnly.isSelected && historyCursorMillis == null) refreshRecentActivityFilterIfNeeded()
        updateLiveTraceDiagnostics()
        if (selectedNodeId != null && historyCursorMillis == null) updateDetails()
    }

    private fun recentActiveNodeIds(now: Long = System.currentTimeMillis()): Set<String> {
        val historical = historyCursorMillis != null
        val overlay = displayedRuntimeOverlay()
        return overlay.lastActivityAtMillis.asSequence()
            .filter { (_, at) -> historical || now - at <= RUNTIME_ACTIVITY_COOLDOWN_MS }
            .mapTo(linkedSetOf()) { it.key }
    }

    private fun refreshRecentActivityFilterIfNeeded(
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ) {
        if (!recentOnly.isSelected) return
        if (!force && now - lastRecentFilterRefreshMillis < RECENT_FILTER_REFRESH_MS) return
        val current = recentActiveNodeIds(now)
        if (!force && current == lastRecentFilterIds) return
        lastRecentFilterIds = current
        lastRecentFilterRefreshMillis = now
        // Preserve zoom, pan and node coordinates. Runtime activity should move across a stable map.
        renderActive(autoFit = false)
    }

    private fun replayRuntimeHistory() {
        val timelineHistory = liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS)
        runtimeTimeline.setEvents(timelineHistory)
        val history = timelineHistory.takeLast(MAX_REPLAY_EVENTS)
        if (history.isEmpty()) return
        history.forEach { event ->
            onRuntimeEvent(event, updateDetails = false, refreshUi = false, markActivity = false)
        }
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        canvas.runtimeReferenceMillis = historyCursorMillis
        clearRuntime.isEnabled = history.isNotEmpty()
        canvas.repaint()
        updateLiveTraceDiagnostics()
    }

    private fun onRuntimeEvent(
        event: RuntimeTraceEvent,
        updateDetails: Boolean = true,
        refreshUi: Boolean = true,
        markActivity: Boolean = true,
    ) {
        runtimeEventsSeen += 1
        val graph = fullGraph
        if (graph == null) {
            unmatchedRuntimeEvents += 1
            unmatchedRuntimeKeys[event.stateKey] = (unmatchedRuntimeKeys[event.stateKey] ?: 0) + 1
            clearRuntime.isEnabled = true
            if (refreshUi) updateLiveTraceDiagnostics()
            if (updateDetails) {
                details.text = "LIVE TRACE\nEvent received but no Flow Graph is loaded.\nruntime key: ${event.stateKey}"
            }
            return
        }

        val node = graph.nodeByRuntimeKey(event.stateKey)
        if (node == null) {
            // Synthetic @flowop events are intermediate cold-flow stages. They can originate inside
            // kotlinx.coroutines/library code and therefore may intentionally have no node in the
            // currently displayed source graph. Keep them in history, but don't present them as a
            // missing source Flow requiring user action.
            val syntheticOperator = event.stateKey.startsWith("@flowop|") || event.stateKey.startsWith("@collect|")
            if (!syntheticOperator) {
                unmatchedRuntimeEvents += 1
                unmatchedRuntimeKeys[event.stateKey] = (unmatchedRuntimeKeys[event.stateKey] ?: 0) + 1
            }
            clearRuntime.isEnabled = true
            if (refreshUi) updateLiveTraceDiagnostics()
            if (updateDetails && !syntheticOperator) {
                details.text = buildString {
                    appendLine("LIVE TRACE — EVENT OUTSIDE CURRENT GRAPH")
                    appendLine("runtime key: ${event.stateKey}")
                    event.valueSummary?.let { appendLine("value: $it") }
                    appendLine()
                    appendLine("The globally instrumented debug app is sending this Flow, but it is outside the currently displayed causal graph.")
                    appendLine("Put the caret on that Flow and run Show Flow Graph. No app rebuild is required for existing instrumented flows.")
                }.trimEnd()
                details.caretPosition = 0
            }
            return
        }

        matchedRuntimeEvents += 1
        unmatchedRuntimeKeys.remove(event.stateKey)
        if (refreshUi) updateLiveTraceDiagnostics()

        val nodeCounts = runtimeOverlay.nodeCounts.toMutableMap()
        val readCounts = runtimeOverlay.readCounts.toMutableMap()
        val deliveryCounts = runtimeOverlay.deliveryCounts.toMutableMap()
        val collectCounts = runtimeOverlay.collectCounts.toMutableMap()
        val emitRequestCounts = runtimeOverlay.emitRequestCounts.toMutableMap()
        val lastEvents = runtimeOverlay.lastEventByNode.toMutableMap()
        val lastActivityAtMillis = runtimeOverlay.lastActivityAtMillis.toMutableMap()
        val edgeCounts = runtimeOverlay.edgeCounts.toMutableMap()
        val isRead = event.kind == "read"
        val isDelivery = event.kind == "deliver"
        val isCollect = event.kind == "collect-start"
        val isEmitRequest = event.kind == "emit-request"
        val isValueEvent = event.kind == "emit" || event.kind == "deliver" || event.kind == "initial"
        val visuallyActiveEvent = markActivity && !isRead && !isCollect
        if (visuallyActiveEvent) {
            lastActivityAtMillis[node.id] = System.currentTimeMillis()
            if (!runtimeActivityCooldownTimer.isRunning) runtimeActivityCooldownTimer.start()
        }

        when {
            isRead -> readCounts[node.id] = (readCounts[node.id] ?: 0) + 1
            isDelivery -> {
                deliveryCounts[node.id] = (deliveryCounts[node.id] ?: 0) + 1
                lastEvents[node.id] = event
            }
            isCollect -> collectCounts[node.id] = (collectCounts[node.id] ?: 0) + 1
            isEmitRequest -> {
                emitRequestCounts[node.id] = (emitRequestCounts[node.id] ?: 0) + 1
                lastEvents[node.id] = event
            }
            else -> {
                nodeCounts[node.id] = (nodeCounts[node.id] ?: 0) + 1
                lastEvents[node.id] = event
            }
        }

        if (isValueEvent && event.kind != "initial" && node.kind == NodeKind.STATE) {
            // Runtime causality remains conservative: an edge is marked observed only when static
            // analysis already knows source -> target and the source emitted shortly before target.
            graph.stateTransitions()
                .asSequence()
                .filter { it.targetStateId == node.id }
                .forEach { transition ->
                    val sourceTime = recentRuntimeEmissionNanos[transition.sourceStateId] ?: return@forEach
                    val delta = event.timestampNanos - sourceTime
                    if (delta in 0..LIVE_EDGE_WINDOW_NANOS) {
                        val key = transition.sourceStateId to node.id
                        edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                    }
                }

            recentRuntimeEmissionNanos[node.id] = event.timestampNanos
        }
        runtimeOverlay = RuntimeOverlay(
            nodeCounts = nodeCounts,
            readCounts = readCounts,
            deliveryCounts = deliveryCounts,
            collectCounts = collectCounts,
            emitRequestCounts = emitRequestCounts,
            edgeCounts = edgeCounts,
            lastEventByNode = lastEvents,
            lastActivityAtMillis = lastActivityAtMillis,
            lastNodeId = if (isRead || isCollect) runtimeOverlay.lastNodeId else node.id,
        )
        clearRuntime.isEnabled = true
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        canvas.runtimeReferenceMillis = historyCursorMillis
        if (refreshUi) canvas.repaint()

        if (updateDetails) {
            details.text = buildString {
                appendLine("LIVE TRACE")
                when {
                    isRead -> appendLine("${node.label} • read #${readCounts[node.id]}")
                    isDelivery -> appendLine("${node.label} • collector delivery #${deliveryCounts[node.id]}")
                    isCollect -> appendLine("${node.label} • collection #${collectCounts[node.id]}")
                    isEmitRequest -> appendLine("${node.label} • suspended emit request #${emitRequestCounts[node.id]}")
                    else -> appendLine("${node.label} • emission #${nodeCounts[node.id]}")
                }
                appendLine("runtime key: ${event.stateKey}")
                appendLine("event: ${event.kind}")
                event.valueSummary?.let { appendLine("value: $it") }
                if (event.fields.isNotEmpty()) appendLine("fields: ${event.fields.joinToString(", ")}")
                event.site?.let { appendLine("site: $it") }
                appendLine()
                appendLine("Observed static edges: ${edgeCounts.values.sum()}")
                appendLine("The runtime overlay connects observed value deliveries/emissions only when that Flow-to-Flow transition already exists in the static graph.")
            }.trimEnd()
            details.caretPosition = 0
        }
    }

    private fun onTimelineScrubbed(cursorMillis: Long?) {
        val wasPausedInHistory = historyCursorMillis != null || liveTraceService.isRecordingPaused()
        historyCursorMillis = cursorMillis
        if (cursorMillis == null) {
            if (wasPausedInHistory) {
                // LIVE is the only action that resumes event retention after timeline scrubbing.
                liveTraceService.resumeRecording()
                liveTransportStatus = liveTraceService.status()
            }
            historicalRuntimeOverlay = null
            canvas.runtimeReferenceMillis = null
            canvas.runtimeOverlay = runtimeOverlay
            if (recentOnly.isSelected) {
                lastRecentFilterIds = recentActiveNodeIds()
                renderActive(autoFit = false)
            } else {
                canvas.repaint()
                if (selectedNodeId != null) {
                    updateDetails()
                } else {
                    details.text = "LIVE runtime view. Drag the bottom history strip to PAUSE recording and inspect a past moment; graph positions stay fixed while scrubbing."
                    details.caretPosition = 0
                }
            }
            hint.text = if (liveTrace.isSelected) {
                "LIVE runtime view • drag the history strip to pause recording and inspect a past moment"
            } else {
                "Runtime history live position • start Live trace to record new events"
            }
            return
        }

        val graph = fullGraph
        if (graph == null) {
            historicalRuntimeOverlay = RuntimeOverlay.EMPTY
            canvas.runtimeOverlay = RuntimeOverlay.EMPTY
            canvas.runtimeReferenceMillis = cursorMillis
            updateHistoricalDetails(cursorMillis)
            return
        }

        val slice = runtimeTimeline.eventsNear(cursorMillis)
        historicalRuntimeOverlay = buildHistoricalOverlay(graph, slice)
        canvas.runtimeOverlay = historicalRuntimeOverlay ?: RuntimeOverlay.EMPTY
        canvas.runtimeReferenceMillis = cursorMillis
        hint.text = "PAUSED ${HISTORY_TIME_FORMAT.format(Date(cursorMillis))} • history frozen • LIVE resumes recording"

        if (recentOnly.isSelected) {
            lastRecentFilterIds = recentActiveNodeIds(cursorMillis)
            renderActive(autoFit = false)
        } else {
            canvas.repaint()
            updateHistoricalDetails(cursorMillis)
        }
    }

    private fun buildHistoricalOverlay(
        graph: FlowGraph,
        events: List<RuntimeTraceEvent>,
    ): RuntimeOverlay {
        if (events.isEmpty()) return RuntimeOverlay.EMPTY

        val nodeCounts = linkedMapOf<String, Int>()
        val readCounts = linkedMapOf<String, Int>()
        val deliveryCounts = linkedMapOf<String, Int>()
        val collectCounts = linkedMapOf<String, Int>()
        val emitRequestCounts = linkedMapOf<String, Int>()
        val edgeCounts = linkedMapOf<Pair<String, String>, Int>()
        val lastEvents = linkedMapOf<String, RuntimeTraceEvent>()
        val lastActivity = linkedMapOf<String, Long>()
        val recentStateActivity = linkedMapOf<String, Long>()
        var lastNodeId: String? = null

        events.sortedBy { it.receivedAtMillis }.forEach { event ->
            val node = graph.nodeByRuntimeKey(event.stateKey) ?: return@forEach
            val isRead = event.kind == "read"
            val isDelivery = event.kind == "deliver"
            val isCollect = event.kind == "collect-start"
            val isEmitRequest = event.kind == "emit-request"
            val isValueEvent = event.kind == "emit" || event.kind == "deliver" || event.kind == "initial"

            when {
                isRead -> readCounts[node.id] = (readCounts[node.id] ?: 0) + 1
                isDelivery -> {
                    deliveryCounts[node.id] = (deliveryCounts[node.id] ?: 0) + 1
                    lastEvents[node.id] = event
                }
                isCollect -> collectCounts[node.id] = (collectCounts[node.id] ?: 0) + 1
                isEmitRequest -> {
                    emitRequestCounts[node.id] = (emitRequestCounts[node.id] ?: 0) + 1
                    lastEvents[node.id] = event
                }
                else -> {
                    nodeCounts[node.id] = (nodeCounts[node.id] ?: 0) + 1
                    lastEvents[node.id] = event
                }
            }

            if (!isRead && !isCollect) {
                lastActivity[node.id] = event.receivedAtMillis
                lastNodeId = node.id
            }

            if (isValueEvent && event.kind != "initial" && node.kind == NodeKind.STATE) {
                graph.stateTransitions()
                    .asSequence()
                    .filter { it.targetStateId == node.id }
                    .forEach transitionLoop@ { transition ->
                        val sourceTime = recentStateActivity[transition.sourceStateId] ?: return@transitionLoop
                        val delta = event.receivedAtMillis - sourceTime
                        if (delta in 0..HISTORY_EDGE_WINDOW_MS) {
                            val key = transition.sourceStateId to node.id
                            edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                        }
                    }
                recentStateActivity[node.id] = event.receivedAtMillis
            }
        }

        return RuntimeOverlay(
            nodeCounts = nodeCounts,
            readCounts = readCounts,
            deliveryCounts = deliveryCounts,
            collectCounts = collectCounts,
            emitRequestCounts = emitRequestCounts,
            edgeCounts = edgeCounts,
            lastEventByNode = lastEvents,
            lastActivityAtMillis = lastActivity,
            lastNodeId = lastNodeId,
        )
    }

    private fun updateHistoricalDetails(cursorMillis: Long) {
        val graph = fullGraph
        val events = runtimeTimeline.eventsNear(cursorMillis)
        val matched = if (graph == null) emptyList() else events.mapNotNull { event ->
            graph.nodeByRuntimeKey(event.stateKey)?.let { it to event }
        }
        val outsideCount = events.size - matched.size

        details.text = buildString {
            appendLine("RUNTIME HISTORY")
            appendLine("${HISTORY_TIME_FORMAT.format(Date(cursorMillis))}  ±${RuntimeTimelinePanel.HISTORY_RADIUS_MS}ms")
            appendLine("events: ${events.size} • in current graph: ${matched.size} • outside graph: $outsideCount")
            appendLine("Recording is PAUSED. Retained history and map coordinates are frozen; incoming device events are ignored until LIVE is pressed.")

            if (matched.isEmpty()) {
                appendLine()
                appendLine("No current-graph Flow events in this time slice.")
            } else {
                appendLine()
                appendLine("EVENTS")
                matched.take(MAX_HISTORY_DETAIL_EVENTS).forEach { (node, event) ->
                    val delta = event.receivedAtMillis - cursorMillis
                    val sign = if (delta >= 0) "+" else ""
                    append("  $sign${delta}ms  ${event.kind.padEnd(12)} ${node.label}")
                    event.valueSummary?.let { value -> append(" = ${value.take(100)}") }
                    event.site?.let { site -> append(" • ${site.take(120)}") }
                    appendLine()
                }
                if (matched.size > MAX_HISTORY_DETAIL_EVENTS) {
                    appendLine("  … +${matched.size - MAX_HISTORY_DETAIL_EVENTS} more")
                }
            }

            val selected = selectedNodeId
            if (selected != null && graph != null) {
                val selectedNode = graph.node(selected)
                val overlay = historicalRuntimeOverlay ?: RuntimeOverlay.EMPTY
                val selectedActivity = (overlay.nodeCounts[selected] ?: 0) +
                    (overlay.deliveryCounts[selected] ?: 0) +
                    (overlay.emitRequestCounts[selected] ?: 0) +
                    (overlay.collectCounts[selected] ?: 0) +
                    (overlay.readCounts[selected] ?: 0)
                appendLine()
                appendLine("SELECTED NODE")
                appendLine("  ${selectedNode?.label ?: selected} • events in slice: $selectedActivity")
            }
        }.trimEnd()
        details.caretPosition = 0
    }

    private fun StringBuilder.appendTransitions(
        graph: FlowGraph,
        transitions: List<StateTransition>,
        outgoing: Boolean,
    ) {
        if (transitions.isEmpty()) {
            appendLine("  —")
            return
        }
        transitions.take(MAX_DETAIL_ITEMS).forEach { transition ->
            val otherId = if (outgoing) transition.targetStateId else transition.sourceStateId
            val other = graph.node(otherId)
            val kind = when (transition.kind) {
                StateTransitionKind.DERIVED -> "derived"
                StateTransitionKind.EXPOSURE -> "exposed"
                StateTransitionKind.SIDE_EFFECT -> "SIDE EFFECT WRITE"
            }
            val confidence = if (transition.confidence == CausalConfidence.POSSIBLE) " • possible" else ""
            appendLine("  ${if (outgoing) "→" else "←"} ${other?.label ?: "state"} [$kind$confidence]")
            appendLine("      ${transition.summary}")
            transition.viaNodeIds.mapNotNull(graph::node).forEach { via ->
                val where = via.source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
                appendLine("      ${via.label}$where")
            }
        }
        if (transitions.size > MAX_DETAIL_ITEMS) {
            appendLine("  … +${transitions.size - MAX_DETAIL_ITEMS} more")
        }
    }

    private fun StringBuilder.appendNodeList(nodes: List<FlowNode>) {
        if (nodes.isEmpty()) {
            appendLine("  —")
            return
        }
        nodes.take(MAX_DETAIL_ITEMS).forEach { node ->
            val where = node.source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
            appendLine("  ${node.label}$where")
        }
        if (nodes.size > MAX_DETAIL_ITEMS) {
            appendLine("  … +${nodes.size - MAX_DETAIL_ITEMS} more")
        }
    }

    private fun FlowEdge.pretty(): String {
        label?.let { return if (confidence == CausalConfidence.POSSIBLE) "possible: $it" else it }
        val base = kind.name.lowercase().replace('_', ' ')
        if (affectedFields.isEmpty()) return base
        val fields = affectedFields
            .map { if (it == "\$value") "value" else it }
            .sorted()
            .take(4)
            .joinToString(", ")
        val more = if (affectedFields.size > 4) " +${affectedFields.size - 4}" else ""
        return "$base • $fields$more"
    }

    private companion object {
        const val MAX_DETAIL_ITEMS = 20
        const val MAX_UNMATCHED_KEYS = 5
        const val MAX_REPLAY_EVENTS = 2_000
        const val MAX_HISTORY_DETAIL_EVENTS = 80
        const val LIVE_EDGE_WINDOW_NANOS = 750_000_000L
        const val HISTORY_EDGE_WINDOW_MS = 750L
        const val LIVE_UI_FLUSH_MS = 100
        const val MAX_RUNTIME_EVENTS_PER_FLUSH = 250
        const val MAX_PENDING_RUNTIME_EVENTS = 2_500
        const val RECENT_FILTER_REFRESH_MS = 250L
        val HISTORY_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS")
    }
}

private class GraphCanvas(
    private val project: Project,
    private val onNodeSelected: (String) -> Unit,
    private val onZoomChanged: (Double) -> Unit,
) : JPanel() {
    var graph: FlowGraph? = null
        set(value) {
            field = value
            cachedLayout = null
        }
    var selectedId: String? = null
        set(value) {
            field = value
            cachedLayout = null
            repaint()
        }
    var compactLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                cachedLayout = null
            }
        }
    var includePossibleTraversal: Boolean = false
        set(value) { field = value; repaint() }
    var simulationResult: SimulationResult? = null
        set(value) { field = value; repaint() }
    var simulationStep: Int = 0
        set(value) { field = value; repaint() }
    var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
        set(value) { field = value; repaint() }
    /** Null = live wall clock. Non-null = historical scrub reference time. */
    var runtimeReferenceMillis: Long? = null
        set(value) { field = value; repaint() }

    private val boundsById = mutableMapOf<String, Rectangle>()
    private val nodeW = 250
    private val nodeH = 76
    private val colGap = 170
    private val rowGap = 34
    private var cachedLayout: NodeLayout? = null
    /**
     * Snapshot of the map before a visibility-only filter (currently Recently active) is applied.
     * Visible nodes reuse these exact coordinates so the user's spatial memory remains valid.
     */
    private var frozenLayout: NodeLayout? = null

    private var zoom = 1.0
    private var unscaledPreferredSize = Dimension(1, 1)
    private var panStartOnScreen: Point? = null
    private var panStartViewPosition: Point? = null
    private var panDragged = false
    private var suppressNextClick = false

    init {
        background = UIManager.getColor("Panel.background")
        toolTipText = "Wheel to zoom; drag empty canvas (or middle-drag) to pan; click a node to isolate its causal connections; click an edge to navigate"

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val shouldPan = SwingUtilities.isMiddleMouseButton(e) ||
                    (SwingUtilities.isLeftMouseButton(e) && !isInteractiveAt(e.point))
                if (!shouldPan) return

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

                val nodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
                if (nodeId != null) {
                    val node = model.node(nodeId) ?: return
                    if (e.clickCount >= 2) {
                        node.source?.let { src -> OpenFileDescriptor(project, src.file, src.offset).navigate(true) }
                        return
                    }
                    if (node.kind !in setOf(NodeKind.CYCLE, NodeKind.CLUSTER)) {
                        onNodeSelected(nodeId)
                    }
                    return
                }

                val edge = findEdgeAt(model, point) ?: return
                navigationSource(model, edge)?.let { src ->
                    OpenFileDescriptor(project, src.file, src.offset).navigate(true)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                updateCursor(e.point)
            }

            override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) {
                if (e.preciseWheelRotation == 0.0) return
                val factor = Math.pow(ZOOM_STEP, -e.preciseWheelRotation)
                setZoom(zoom * factor, e.point)
                e.consume()
            }
        }

        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    fun freezeCurrentLayout() {
        val model = graph ?: return
        val source = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        frozenLayout = copyLayout(source)
    }

    fun clearFrozenLayout() {
        frozenLayout = null
        cachedLayout = null
    }

    data class ViewAnchor(
        val nodeId: String,
        val viewportOffsetX: Int,
        val viewportOffsetY: Int,
    )

    /** Capture where a node currently appears inside the viewport so incremental expansion can
     * reveal a new outer level without making the user's focal node jump across the screen. */
    fun captureViewportAnchor(nodeId: String): ViewAnchor? {
        val model = graph ?: return null
        val viewport = viewport() ?: return null
        val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        val rect = layout.bounds[nodeId] ?: return null
        val centerX = ((rect.x + rect.width / 2.0) * zoom).toInt()
        val centerY = ((rect.y + rect.height / 2.0) * zoom).toInt()
        return ViewAnchor(
            nodeId = nodeId,
            viewportOffsetX = centerX - viewport.viewPosition.x,
            viewportOffsetY = centerY - viewport.viewPosition.y,
        )
    }

    fun restoreViewportAnchor(anchor: ViewAnchor) {
        val model = graph ?: return
        val viewport = viewport() ?: return
        val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        val rect = layout.bounds[anchor.nodeId] ?: return
        val centerX = ((rect.x + rect.width / 2.0) * zoom).toInt()
        val centerY = ((rect.y + rect.height / 2.0) * zoom).toInt()
        viewport.viewPosition = clampViewPosition(
            viewport,
            centerX - anchor.viewportOffsetX,
            centerY - anchor.viewportOffsetY,
        )
        repaint()
    }

    private fun copyLayout(source: NodeLayout): NodeLayout = NodeLayout(
        bounds = source.bounds.mapValues { (_, r) -> Rectangle(r) },
        readCluster = source.readCluster?.let(::Rectangle),
        componentClusters = source.componentClusters.map { ComponentCluster(Rectangle(it.bounds), it.label) },
        preferredSize = Dimension(source.preferredSize),
        clusterByNodeId = source.clusterByNodeId.toMap(),
    )

    fun relayout() {
        val model = graph ?: return
        val layout = layoutNodes(model)
        cachedLayout = layout
        unscaledPreferredSize = layout.preferredSize
        updateScaledPreferredSize()
        revalidate()
        repaint()
    }

    fun zoomIn() = setZoom(zoom * ZOOM_STEP, viewportCenterInCanvas())

    fun zoomOut() = setZoom(zoom / ZOOM_STEP, viewportCenterInCanvas())

    fun resetZoom() = setZoom(1.0, viewportCenterInCanvas())

    fun fitToViewport() {
        val viewport = viewport() ?: return
        // Repack the project overview against the current viewport. This matters when the initial
        // analysis finishes before the tool-window divider has settled, and also makes the Fit
        // button adapt the cluster tiling after the user resizes the tool window.
        if (isAllProjectFlows(graph?.rootLabel) && selectedId == null) {
            val model = graph ?: return
            cachedLayout = null
            val layout = layoutNodes(model)
            cachedLayout = layout
            unscaledPreferredSize = layout.preferredSize
            updateScaledPreferredSize()
            revalidate()
        }
        if (unscaledPreferredSize.width <= 0 || unscaledPreferredSize.height <= 0) return
        val availableW = (viewport.extentSize.width - FIT_PADDING * 2).coerceAtLeast(1)
        val availableH = (viewport.extentSize.height - FIT_PADDING * 2).coerceAtLeast(1)
        val fitted = minOf(
            availableW.toDouble() / unscaledPreferredSize.width.toDouble(),
            availableH.toDouble() / unscaledPreferredSize.height.toDouble(),
            1.0,
        ).coerceIn(currentMinZoom(), MAX_ZOOM)

        setZoom(fitted, null)
        viewport.viewPosition = Point(0, 0)
    }

    private fun setZoom(requestedZoom: Double, anchorInCanvas: Point?) {
        val newZoom = requestedZoom.coerceIn(currentMinZoom(), MAX_ZOOM)
        if (kotlin.math.abs(newZoom - zoom) < 0.0001) return

        val viewport = viewport()
        val oldZoom = zoom
        val anchor = anchorInCanvas ?: viewportCenterInCanvas() ?: Point(0, 0)
        val anchorInViewport = viewport?.let {
            Point(anchor.x - it.viewPosition.x, anchor.y - it.viewPosition.y)
        }
        val modelX = anchor.x / oldZoom
        val modelY = anchor.y / oldZoom

        zoom = newZoom
        updateScaledPreferredSize()
        revalidate()
        onZoomChanged(zoom)

        if (viewport != null && anchorInViewport != null) {
            val targetX = (modelX * zoom - anchorInViewport.x).toInt()
            val targetY = (modelY * zoom - anchorInViewport.y).toInt()
            viewport.viewPosition = clampViewPosition(viewport, targetX, targetY)
        }
        repaint()
    }

    private fun currentMinZoom(): Double =
        if (isAllProjectFlows(graph?.rootLabel) && selectedId == null) PROJECT_OVERVIEW_MIN_ZOOM else MIN_ZOOM

    private fun updateScaledPreferredSize() {
        preferredSize = Dimension(
            (unscaledPreferredSize.width * zoom).toInt().coerceAtLeast(1),
            (unscaledPreferredSize.height * zoom).toInt().coerceAtLeast(1),
        )
    }

    private fun viewport(): JViewport? =
        SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    private fun viewportCenterInCanvas(): Point? {
        val viewport = viewport() ?: return null
        return Point(
            viewport.viewPosition.x + viewport.extentSize.width / 2,
            viewport.viewPosition.y + viewport.extentSize.height / 2,
        )
    }

    private fun clampViewPosition(viewport: JViewport, x: Int, y: Int): Point {
        val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        return Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    private fun toModelPoint(point: Point): Point = Point(
        (point.x / zoom).toInt(),
        (point.y / zoom).toInt(),
    )

    private fun isInteractiveAt(canvasPoint: Point): Boolean {
        val model = graph ?: return false
        val point = toModelPoint(canvasPoint)
        if (boundsById.values.any { it.contains(point) }) return true
        return findEdgeAt(model, point) != null
    }

    private fun updateCursor(canvasPoint: Point) {
        if (panStartOnScreen != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            return
        }
        cursor = if (isInteractiveAt(canvasPoint)) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private data class ComponentCluster(
        val bounds: Rectangle,
        val label: String,
    )

    private data class NodeLayout(
        val bounds: Map<String, Rectangle>,
        val readCluster: Rectangle?,
        val componentClusters: List<ComponentCluster>,
        val preferredSize: Dimension,
        val clusterByNodeId: Map<String, Int> = emptyMap(),
    )

    private data class EdgeLine(
        val edge: FlowEdge,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
    )

    private enum class EdgeDirection {
        NONE,
        FORWARD,
        BACKWARD,
        BOTH,
        POSSIBLE,
    }

    private fun layoutNodes(model: FlowGraph): NodeLayout {
        frozenLayout?.let { frozen ->
            val visibleIds = model.nodes.mapTo(linkedSetOf()) { it.id }
            val visibleBounds = frozen.bounds
                .filterKeys { it in visibleIds }
                .mapValues { (_, r) -> Rectangle(r) }
            val visibleClusters = frozen.componentClusters.filter { cluster ->
                visibleBounds.values.any { cluster.bounds.intersects(it) }
            }.map { ComponentCluster(Rectangle(it.bounds), it.label) }
            return NodeLayout(
                bounds = visibleBounds,
                readCluster = frozen.readCluster?.let(::Rectangle),
                componentClusters = visibleClusters,
                preferredSize = Dimension(frozen.preferredSize),
                clusterByNodeId = frozen.clusterByNodeId.filterKeys { it in visibleIds },
            )
        }

        val horizontalGap = if (compactLayout) 86 else colGap
        val verticalGap = if (compactLayout) 18 else rowGap
        val clusterProjectWide = selectedId == null && isAllProjectFlows(model.rootLabel)

        // In the project-wide overview every visible endpoint participates in clustering.
        // Older versions pulled READ nodes and synthetic boundary nodes into separate islands,
        // then drew long cross-island edges across empty canvas. That was the main source of the
        // huge apparent gaps. A cluster now means exactly what the user expects: if two visible
        // nodes have any graph edge between them, they belong to the same packed component.
        val mainNodes = if (clusterProjectWide) model.nodes else model.nodes.filter { it.kind != NodeKind.READ }
        val mainIds = mainNodes.mapTo(linkedSetOf()) { it.id }
        val mainGraph = model.copy(
            nodes = mainNodes,
            edges = model.edges.filter { it.from in mainIds && it.to in mainIds },
        )
        val result = linkedMapOf<String, Rectangle>()
        val componentClusters = mutableListOf<ComponentCluster>()
        val clusterByNodeId = linkedMapOf<String, Int>()
        if (clusterProjectWide) {
            // The overview uses substantially smaller nodes and measured dense packing. A fixed
            // 2400px shelf left huge holes between components and forced panning on 200+ node graphs.
            val components = semanticOverviewClusters(mainGraph)
            val placements = components.mapIndexed { index, ids ->
                layoutOverviewComponent(
                    model = mainGraph,
                    ids = ids,
                    index = index + 1,
                )
            }
            val packed = packProjectComponents(placements)
            packed.forEachIndexed { clusterIndex, packedComponent ->
                val placement = packedComponent.placement
                placement.bounds.forEach { (id, r) ->
                    clusterByNodeId[id] = clusterIndex
                    result[id] = Rectangle(
                        r.x + packedComponent.x,
                        r.y + packedComponent.y,
                        r.width,
                        r.height,
                    )
                }
                componentClusters += ComponentCluster(
                    bounds = Rectangle(
                        packedComponent.x,
                        packedComponent.y,
                        placement.width,
                        placement.height,
                    ),
                    label = placement.label,
                )
            }
        } else {
            val columns = selectedId?.takeIf { mainGraph.node(it) != null }
                ?.let { computeFocusedColumns(mainGraph, it) }
                ?: computeColumns(mainGraph)

            val grouped = mainNodes.groupBy { columns[it.id] ?: 0 }.toSortedMap()
            val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
            val rowStride = nodeH + verticalGap
            val contentHeight = maxRows * rowStride

            grouped.forEach { (column, nodes) ->
                val sorted = nodes.sortedWith(compareBy<FlowNode>({ nodeSortKey(it.kind) }, { it.label }))
                val groupHeight = sorted.size * rowStride
                val startY = 38 + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
                sorted.forEachIndexed { row, node ->
                    result[node.id] = Rectangle(
                        30 + column * (nodeW + horizontalGap),
                        startY + row * rowStride,
                        nodeW,
                        nodeH,
                    )
                }
            }
        }

        if (clusterProjectWide) {
            normalizeOverviewOrigin(result, componentClusters)
        }

        val mainRight = result.values.maxOfOrNull { it.x + it.width } ?: (30 + nodeW)
        val mainBottom = result.values.maxOfOrNull { it.y + it.height } ?: (30 + nodeH)
        val reads = if (clusterProjectWide) emptyList() else model.nodes.filter { it.kind == NodeKind.READ }.sortedBy { it.label }
        var readCluster: Rectangle? = null

        if (reads.isNotEmpty()) {
            val readNodeW = if (clusterProjectWide) PROJECT_OVERVIEW_NODE_W else nodeW
            val readNodeH = if (clusterProjectWide) PROJECT_OVERVIEW_NODE_H else nodeH
            val gap = if (clusterProjectWide) PROJECT_OVERVIEW_VERTICAL_GAP else 28
            val clusterTop = mainBottom + if (clusterProjectWide) 14 else 85
            val usableWidth = max(mainRight, 30 + readNodeW)
            val horizontalStride = readNodeW + gap
            val perRow = if (clusterProjectWide) {
                ((usableWidth - 26) / horizontalStride).coerceAtLeast(1)
            } else {
                ((usableWidth - 50) / horizontalStride).coerceIn(1, 6)
            }
            val left = if (clusterProjectWide) 18 else 45
            val header = if (clusterProjectWide) 24 else 32
            val readVerticalGap = if (clusterProjectWide) PROJECT_OVERVIEW_VERTICAL_GAP else verticalGap
            reads.forEachIndexed { index, node ->
                val col = index % perRow
                val row = index / perRow
                result[node.id] = Rectangle(
                    left + col * horizontalStride,
                    clusterTop + header + row * (readNodeH + readVerticalGap),
                    readNodeW,
                    readNodeH,
                )
            }
            val readRight = result.filterKeys { id -> model.node(id)?.kind == NodeKind.READ }
                .values.maxOf { it.x + it.width }
            val readBottom = result.filterKeys { id -> model.node(id)?.kind == NodeKind.READ }
                .values.maxOf { it.y + it.height }
            readCluster = Rectangle(
                if (clusterProjectWide) 8 else 20,
                clusterTop,
                max(usableWidth + 12, readRight + 12),
                readBottom - clusterTop + if (clusterProjectWide) 10 else 28,
            )
        }

        val right = max(mainRight + 50, readCluster?.let { it.x + it.width + 25 } ?: 0)
        val bottom = max(mainBottom + 50, readCluster?.let { it.y + it.height + 25 } ?: 0)
        return NodeLayout(result, readCluster, componentClusters, Dimension(right, bottom), clusterByNodeId)
    }


    /**
     * Removes only unused outer coordinate space from All flows. Do not scale node geometry here:
     * scaling the coordinates themselves destroys the spacing that makes edges legible. Fit is
     * handled by the canvas zoom, while semantic clustering keeps 200+ node overviews compact.
     *
     * Fit is not allowed below 20%, therefore the layout itself must fit inside the logical
     * viewport available at 20%. This pass measures the *actual occupied bounds* after component
     * packing and, only when necessary, uniformly compacts the geometry so Fit is guaranteed to
     * reach the complete graph without panning. This is intentionally an overview-only transform;
     * selecting a node returns to full-size focused nodes.
     */
    private fun normalizeOverviewOrigin(
        nodes: MutableMap<String, Rectangle>,
        clusters: MutableList<ComponentCluster>,
    ) {
        if (nodes.isEmpty()) return
        val allRects = nodes.values + clusters.map { it.bounds }
        val minX = allRects.minOf { it.x }
        val minY = allRects.minOf { it.y }
        val dx = PROJECT_OVERVIEW_OUTER_PADDING - minX
        val dy = PROJECT_OVERVIEW_OUTER_PADDING - minY
        nodes.values.forEach { it.translate(dx, dy) }
        clusters.forEach { it.bounds.translate(dx, dy) }
    }

    private data class ComponentPlacement(
        val bounds: Map<String, Rectangle>,
        val width: Int,
        val height: Int,
        val label: String,
    )

    private fun layoutComponent(
        model: FlowGraph,
        ids: Set<String>,
        horizontalGap: Int,
        verticalGap: Int,
        index: Int,
        layoutNodeW: Int = nodeW,
        layoutNodeH: Int = nodeH,
        padding: Int = PROJECT_CLUSTER_PADDING,
        headerHeight: Int = PROJECT_CLUSTER_HEADER,
    ): ComponentPlacement {
        val nodes = model.nodes.filter { it.id in ids }
        val subGraph = model.copy(
            nodes = nodes,
            edges = model.edges.filter { it.from in ids && it.to in ids },
        )
        val rawColumns = computeColumns(subGraph)
        val minColumn = rawColumns.values.minOrNull() ?: 0
        val columns = rawColumns.mapValues { it.value - minColumn }
        val grouped = nodes.groupBy { columns[it.id] ?: 0 }.toSortedMap()
        val rowStride = layoutNodeH + verticalGap
        val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
        val contentHeight = maxRows * rowStride
        val bounds = linkedMapOf<String, Rectangle>()

        grouped.forEach { (column, columnNodes) ->
            val sorted = columnNodes.sortedWith(compareBy<FlowNode>({ nodeSortKey(it.kind) }, { it.label }))
            val groupHeight = sorted.size * rowStride
            val startY = headerHeight + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
            sorted.forEachIndexed { row, node ->
                bounds[node.id] = Rectangle(
                    padding + column * (layoutNodeW + horizontalGap),
                    startY + row * rowStride,
                    layoutNodeW,
                    layoutNodeH,
                )
            }
        }

        val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: layoutNodeW) + padding
        val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: layoutNodeH) + padding
        val states = nodes.filter { it.kind == NodeKind.STATE }
        val names = states.map { it.label }.distinct().sorted()
        val preview = names.take(3).joinToString(", ") + if (names.size > 3) ", …" else ""
        val label = buildString {
            if (states.isEmpty() && nodes.all { it.kind == NodeKind.CLUSTER }) {
                append("Boundary ").append(index)
                val boundaryPreview = nodes.map { it.label }.distinct().sorted().take(2).joinToString(", ")
                if (boundaryPreview.isNotBlank()) append(" • ").append(boundaryPreview)
            } else {
                append("Cluster ").append(index).append(" • ").append(states.size).append(" flows")
                if (preview.isNotBlank()) append(" • ").append(preview)
            }
        }
        return ComponentPlacement(bounds, width, height, label)
    }

    /**
     * Dense component layout used only by the project-wide overview.
     *
     * The focused graph deliberately preserves causal columns, but that makes a large connected
     * component thousands of pixels wide when its longest dependency chain is deep. In the overview
     * we instead preserve only the *ordering* of those causal columns, then fold the ordered nodes
     * into a bounded near-square grid. This keeps upstream-ish nodes toward the left and downstream-ish
     * nodes toward the right without letting graph depth dictate physical canvas width.
     */
    private fun layoutOverviewComponent(
        model: FlowGraph,
        ids: Set<String>,
        index: Int,
    ): ComponentPlacement {
        val nodes = model.nodes.filter { it.id in ids }
        val subGraph = model.copy(
            nodes = nodes,
            edges = model.edges.filter { it.from in ids && it.to in ids },
        )
        val causalColumns = computeColumns(subGraph)
        val distinctColumns = causalColumns.values.distinct().sorted()
        val rankByColumn = distinctColumns.withIndex().associate { (rank, value) -> value to rank }
        val physicalColumnCount = minOf(PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS, distinctColumns.size.coerceAtLeast(1))

        fun physicalColumn(node: FlowNode): Int {
            val rank = rankByColumn[causalColumns[node.id] ?: 0] ?: 0
            if (distinctColumns.size <= physicalColumnCount || physicalColumnCount <= 1) return rank
            return kotlin.math.round(
                rank.toDouble() * (physicalColumnCount - 1).toDouble() /
                    (distinctColumns.size - 1).coerceAtLeast(1).toDouble(),
            ).toInt().coerceIn(0, physicalColumnCount - 1)
        }

        val grouped = nodes.groupBy(::physicalColumn).toSortedMap()
        val bounds = linkedMapOf<String, Rectangle>()
        val rowStride = PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_VERTICAL_GAP
        val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
        val contentHeight = maxRows * rowStride
        grouped.forEach { (column, columnNodes) ->
            val sorted = columnNodes.sortedWith(
                compareBy<FlowNode>({ causalColumns[it.id] ?: 0 }, { nodeSortKey(it.kind) }, { it.label }),
            )
            val groupHeight = sorted.size * rowStride
            val startY = PROJECT_OVERVIEW_CLUSTER_HEADER + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
            sorted.forEachIndexed { row, node ->
                bounds[node.id] = Rectangle(
                    PROJECT_OVERVIEW_CLUSTER_PADDING + column * (PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_HORIZONTAL_GAP),
                    startY + row * rowStride,
                    PROJECT_OVERVIEW_NODE_W,
                    PROJECT_OVERVIEW_NODE_H,
                )
            }
        }

        val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W) +
            PROJECT_OVERVIEW_CLUSTER_PADDING
        val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H) +
            PROJECT_OVERVIEW_CLUSTER_PADDING
        val states = nodes.filter { it.kind == NodeKind.STATE }
        val names = states.map { it.label }.distinct().sorted()
        val preview = names.take(2).joinToString(", ") + if (names.size > 2) ", …" else ""
        val label = buildString {
            append("Cluster ").append(index).append(" • ").append(states.size).append(" flows")
            if (preview.isNotBlank()) append(" • ").append(preview)
        }
        return ComponentPlacement(bounds, width, height, label)
    }

    private data class PackedComponent(
        val placement: ComponentPlacement,
        val x: Int,
        val y: Int,
    )

    private data class Shelf(
        val y: Int,
        val height: Int,
        var nextX: Int,
    )

    private data class PackingResult(
        val components: List<PackedComponent>,
        val width: Int,
        val height: Int,
        val fittedScale: Double,
    )

    /**
     * Packs disconnected components against the *actual viewport aspect ratio* instead of a fixed
     * row width. Several candidate widths are tried with best-fit-decreasing shelves and we keep
     * the arrangement that produces the largest whole-graph scale-to-fit factor. Sorting by height
     * and choosing the closest-height shelf keeps wasted vertical space low while staying fast for
     * hundreds of clusters.
     */
    private fun packProjectComponents(placements: List<ComponentPlacement>): List<PackedComponent> {
        if (placements.isEmpty()) return emptyList()
        val gap = PROJECT_OVERVIEW_CLUSTER_GAP
        val sorted = placements.sortedWith(
            compareByDescending<ComponentPlacement> { it.height }
                .thenByDescending { it.width }
                .thenByDescending { it.width.toLong() * it.height.toLong() },
        )

        val extent = viewport()?.extentSize ?: Dimension(1400, 850)
        val viewportW = extent.width.coerceAtLeast(320)
        val viewportH = extent.height.coerceAtLeast(240)
        val viewportAspect = viewportW.toDouble() / viewportH.toDouble()
        val maxWidth = sorted.maxOf { it.width } + PROJECT_OVERVIEW_OUTER_PADDING * 2
        val totalArea = sorted.sumOf {
            (it.width + gap).toLong() * (it.height + gap).toLong()
        }.coerceAtLeast(1L)
        val idealWidth = sqrt(totalArea.toDouble() * viewportAspect).toInt().coerceAtLeast(maxWidth)

        // A 20% minimum zoom means the *layout itself* has to fit inside this logical rectangle.
        // Explicitly try shelf widths up to that boundary instead of merely choosing a visually
        // pleasing aspect ratio and hoping Fit can zoom far enough afterwards.
        val logicalFitWidth = ((viewportW - FIT_PADDING * 2).coerceAtLeast(320) / PROJECT_OVERVIEW_MIN_ZOOM)
            .toInt().coerceAtLeast(maxWidth)

        val candidates = linkedSetOf<Int>()
        listOf(0.72, 0.84, 0.94, 1.0, 1.08, 1.20, 1.36, 1.52).forEach { factor ->
            candidates += (idealWidth * factor).toInt().coerceIn(maxWidth, logicalFitWidth)
        }
        candidates += maxWidth
        candidates += logicalFitWidth
        candidates += (logicalFitWidth * 0.85).toInt().coerceAtLeast(maxWidth)
        candidates += (logicalFitWidth * 0.70).toInt().coerceAtLeast(maxWidth)

        return candidates
            .map { width -> packIntoShelves(sorted, width, gap, viewportW, viewportH) }
            .maxWithOrNull(
                compareBy<PackingResult> { it.fittedScale }
                    .thenBy { -(it.width.toLong() * it.height.toLong()) },
            )
            ?.components
            ?: emptyList()
    }

    private fun packIntoShelves(
        placements: List<ComponentPlacement>,
        targetWidth: Int,
        gap: Int,
        viewportW: Int,
        viewportH: Int,
    ): PackingResult {
        // Tight deterministic row packing. `placements` arrive height-sorted, so items sharing a
        // row have similar heights and very little shelf waste. More importantly, the row width is
        // the actual logical width available at the 20% fit boundary, rather than an arbitrary
        // aesthetic width. Hundreds of isolated/small components therefore tile edge-to-edge.
        val packed = mutableListOf<PackedComponent>()
        val left = PROJECT_OVERVIEW_OUTER_PADDING
        val rightLimit = targetWidth - PROJECT_OVERVIEW_OUTER_PADDING
        var x = left
        var y = PROJECT_OVERVIEW_OUTER_PADDING
        var rowHeight = 0

        placements.forEach { placement ->
            val needsNewRow = x > left && x + placement.width > rightLimit
            if (needsNewRow) {
                x = left
                y += rowHeight + gap
                rowHeight = 0
            }
            packed += PackedComponent(placement, x, y)
            x += placement.width + gap
            rowHeight = max(rowHeight, placement.height)
        }

        val right = packed.maxOfOrNull { it.x + it.placement.width } ?: 1
        val bottom = packed.maxOfOrNull { it.y + it.placement.height } ?: 1
        val width = right + PROJECT_OVERVIEW_OUTER_PADDING
        val height = bottom + PROJECT_OVERVIEW_OUTER_PADDING
        val fitted = minOf(
            viewportW.toDouble() / width.toDouble(),
            viewportH.toDouble() / height.toDouble(),
            1.0,
        )
        return PackingResult(packed, width, height, fitted)
    }

    /**
     * Builds overview communities from strong causal proximity rather than plain connectivity.
     * A single weak/read bridge must not turn half of the app into one giant grid. Large strong
     * components are greedily partitioned into bounded neighborhoods; cross-neighborhood edges
     * remain visible on the map.
     */
    private fun semanticOverviewClusters(model: FlowGraph): List<Set<String>> {
        val byId = model.nodes.associateBy { it.id }
        val primaryIds = model.nodes.asSequence()
            .filter { it.kind != NodeKind.READ }
            .map { it.id }
            .toMutableSet()
        if (primaryIds.isEmpty()) return emptyList()

        fun strong(edge: FlowEdge): Boolean =
            edge.kind != EdgeKind.READS &&
                edge.kind != EdgeKind.POSSIBLY_TRIGGERS_WRITE &&
                edge.confidence != CausalConfidence.POSSIBLE

        val adjacency = primaryIds.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
        model.edges.asSequence().filter(::strong).forEach { edge ->
            if (edge.from in primaryIds && edge.to in primaryIds) {
                adjacency.getValue(edge.from) += edge.to
                adjacency.getValue(edge.to) += edge.from
            }
        }

        fun owner(id: String): String {
            val node = byId[id] ?: return ""
            node.runtimeKey?.takeIf { it.isNotBlank() }?.let { key ->
                return key.substringBeforeLast('.', key)
            }
            return node.source?.file?.name?.substringBeforeLast('.').orEmpty()
        }

        val unassigned = primaryIds.toMutableSet()
        val clusters = mutableListOf<LinkedHashSet<String>>()
        while (unassigned.isNotEmpty()) {
            val seed = unassigned.maxWithOrNull(
                compareBy<String> { adjacency[it].orEmpty().count { n -> n in unassigned } }
                    .thenBy { if (byId[it]?.kind == NodeKind.STATE) 1 else 0 },
            ) ?: unassigned.first()
            val cluster = linkedSetOf(seed)
            unassigned.remove(seed)
            val seedOwner = owner(seed)

            while (cluster.size < PROJECT_OVERVIEW_MAX_CLUSTER_NODES) {
                val candidates = linkedSetOf<String>()
                cluster.forEach { id ->
                    adjacency[id].orEmpty().filterTo(candidates) { it in unassigned }
                }
                if (candidates.isEmpty()) break
                val chosen = candidates.maxWithOrNull(
                    compareBy<String> { candidate ->
                        adjacency[candidate].orEmpty().count { it in cluster } * 100 +
                            (if (owner(candidate).isNotBlank() && owner(candidate) == seedOwner) 20 else 0) +
                            adjacency[candidate].orEmpty().count { it in unassigned } * 2 +
                            (if (byId[candidate]?.kind == NodeKind.STATE) 4 else 0)
                    }.thenBy { it },
                ) ?: break
                cluster += chosen
                unassigned.remove(chosen)
            }
            clusters += cluster
        }

        // Reads are context, not community glue. Attach them to a neighboring causal community
        // where possible; overflow reads are chunked separately so observer-heavy screens don't
        // blow up an otherwise useful Flow neighborhood.
        val clusterIndexById = mutableMapOf<String, Int>()
        clusters.forEachIndexed { index, ids -> ids.forEach { clusterIndexById[it] = index } }
        val readOverflow = mutableListOf<String>()
        model.nodes.filter { it.kind == NodeKind.READ }.forEach { read ->
            val neighborCluster = model.edges.asSequence()
                .filter { it.from == read.id || it.to == read.id }
                .mapNotNull { edge -> clusterIndexById[if (edge.from == read.id) edge.to else edge.from] }
                .firstOrNull()
            if (neighborCluster != null && clusters[neighborCluster].size < PROJECT_OVERVIEW_MAX_CLUSTER_NODES + 6) {
                clusters[neighborCluster] += read.id
            } else {
                readOverflow += read.id
            }
        }
        readOverflow.chunked(PROJECT_OVERVIEW_MAX_CLUSTER_NODES).forEach { chunk ->
            clusters += LinkedHashSet(chunk)
        }

        return clusters.sortedWith(
            compareByDescending<Set<String>> { ids -> ids.count { byId[it]?.kind == NodeKind.STATE } }
                .thenByDescending { it.size },
        )
    }

    private fun connectedComponents(model: FlowGraph): List<Set<String>> {
        val ids = model.nodes.mapTo(linkedSetOf()) { it.id }
        if (ids.isEmpty()) return emptyList()
        val adjacency = ids.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
        model.edges.forEach { edge ->
            if (edge.from !in ids || edge.to !in ids) return@forEach
            // All visible edges participate here, including READS and synthetic boundary nodes.
            // If an edge is drawn between two nodes they must be packed into the same component;
            // otherwise the edge itself creates a giant empty span between separately packed boxes.
            adjacency.getValue(edge.from) += edge.to
            adjacency.getValue(edge.to) += edge.from
        }

        val remaining = ids.toMutableSet()
        val components = mutableListOf<Set<String>>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val component = linkedSetOf<String>()
            val queue = ArrayDeque<String>()
            queue += start
            remaining -= start
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                adjacency[current].orEmpty().forEach { next ->
                    if (remaining.remove(next)) queue += next
                }
            }
            components += component
        }
        return components.sortedWith(
            compareByDescending<Set<String>> { component ->
                component.count { id -> model.node(id)?.kind == NodeKind.STATE }
            }.thenByDescending { it.size }
        )
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val model = graph ?: return
        val g = graphics.create() as Graphics2D
        g.scale(zoom, zoom)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        boundsById.clear()
        boundsById.putAll(layout.bounds)

        layout.componentClusters.forEach { cluster ->
            val oldStroke = g.stroke
            val border = UIManager.getColor("Separator.foreground") ?: Color.GRAY
            g.color = withAlpha(border, 145)
            g.stroke = BasicStroke(1.25f)
            g.drawRoundRect(cluster.bounds.x, cluster.bounds.y, cluster.bounds.width, cluster.bounds.height, 18, 18)
            g.stroke = oldStroke
            g.font = font.deriveFont(Font.BOLD, 11f)
            g.color = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
            g.drawString(cluster.label.take(110), cluster.bounds.x + 14, cluster.bounds.y + 20)
        }

        layout.readCluster?.let { cluster ->
            val oldStroke = g.stroke
            g.color = UIManager.getColor("Separator.foreground") ?: Color.GRAY
            g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
            g.drawRoundRect(cluster.x, cluster.y, cluster.width, cluster.height, 18, 18)
            g.stroke = oldStroke
            g.font = font.deriveFont(Font.BOLD, 11f)
            g.drawString("READS / OBSERVERS — no tracked state write discovered", cluster.x + 14, cluster.y + 20)
        }

        val selected = selectedId
        val forwardDepths = selected?.let {
            reachableEdgeDepths(model, it, forward = true, includePossible = includePossibleTraversal)
        }.orEmpty()
        val backwardDepths = selected?.let {
            reachableEdgeDepths(model, it, forward = false, includePossible = includePossibleTraversal)
        }.orEmpty()
        val directForwardEdges = selected?.let { id ->
            model.edges.filterTo(linkedSetOf()) {
                it.from == id && it.kind != EdgeKind.READS && !isPossibleEdge(it)
            }
        }.orEmpty()
        val directBackwardEdges = selected?.let { id ->
            model.edges.filterTo(linkedSetOf()) {
                it.to == id && it.kind != EdgeKind.READS && !isPossibleEdge(it)
            }
        }.orEmpty()

        if (selected != null) {
            drawDirectionLegend(g)
        }

        model.edges.forEach { edge ->
            val a = boundsById[edge.from] ?: return@forEach
            val b = boundsById[edge.to] ?: return@forEach
            val possibleContext = selected != null && isPossibleEdge(edge) &&
                (edge.from == selected || edge.to == selected ||
                    forwardDepths.keys.any { it.from == edge.from || it.to == edge.from || it.from == edge.to || it.to == edge.to } ||
                    backwardDepths.keys.any { it.from == edge.from || it.to == edge.from || it.from == edge.to || it.to == edge.to })
            val direction = when {
                possibleContext && !includePossibleTraversal -> EdgeDirection.POSSIBLE
                edge in forwardDepths && edge in backwardDepths -> EdgeDirection.BOTH
                edge in forwardDepths -> EdgeDirection.FORWARD
                edge in backwardDepths -> EdgeDirection.BACKWARD
                else -> EdgeDirection.NONE
            }
            val direct = edge in directForwardEdges || edge in directBackwardEdges
            val directionDistance = minOf(
                forwardDepths[edge] ?: Int.MAX_VALUE,
                backwardDepths[edge] ?: Int.MAX_VALUE,
            ).takeUnless { it == Int.MAX_VALUE }
            val simHop = simulationResult?.hopsThrough(simulationStep)?.lastOrNull {
                it.sourceStateId == edge.from && it.targetStateId == edge.to
            }
            val runtimeCount = runtimeOverlay.edgeCounts[edge.from to edge.to] ?: 0
            val fromCluster = layout.clusterByNodeId[edge.from]
            val toCluster = layout.clusterByNodeId[edge.to]
            val interCluster = fromCluster != null && toCluster != null && fromCluster != toCluster
            drawArrow(g, a, b, edge, direction, direct, directionDistance, selected != null, simHop, runtimeCount, interCluster)
        }
        model.nodes.forEach { node -> boundsById[node.id]?.let { drawNode(g, node, it) } }
        g.dispose()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val model = graph ?: return null
        val point = toModelPoint(event.point)
        val nodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
        if (nodeId != null) return model.node(nodeId)?.detail

        val edge = findEdgeAt(model, point) ?: return null
        val source = navigationSource(model, edge)
        val where = source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
        return "Click to open ${edgeDisplayLabel(edge)}$where"
    }

    private fun findEdgeAt(model: FlowGraph, point: Point): FlowEdge? =
        model.edges.asSequence()
            .mapNotNull { edge ->
                val line = edgeLine(edge) ?: return@mapNotNull null
                edge to pointToSegmentDistance(point.x.toDouble(), point.y.toDouble(), line)
            }
            .filter { (_, distance) -> distance <= EDGE_HIT_DISTANCE / zoom }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    private fun edgeLine(edge: FlowEdge): EdgeLine? {
        val a = boundsById[edge.from] ?: return null
        val b = boundsById[edge.to] ?: return null
        val right = b.centerX >= a.centerX
        return EdgeLine(
            edge = edge,
            x1 = if (right) a.x + a.width else a.x,
            y1 = a.y + a.height / 2,
            x2 = if (right) b.x else b.x + b.width,
            y2 = b.y + b.height / 2,
        )
    }

    private fun pointToSegmentDistance(px: Double, py: Double, line: EdgeLine): Double {
        val x1 = line.x1.toDouble()
        val y1 = line.y1.toDouble()
        val x2 = line.x2.toDouble()
        val y2 = line.y2.toDouble()
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) return kotlin.math.hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val nearestX = x1 + t * dx
        val nearestY = y1 + t * dy
        return kotlin.math.hypot(px - nearestX, py - nearestY)
    }

    private fun navigationSource(model: FlowGraph, edge: FlowEdge): SourceLocation? {
        edge.source?.let { return it }
        val from = model.node(edge.from)
        val to = model.node(edge.to)

        // Prefer the endpoint that represents an actual call/write/read site rather than a state
        // declaration. This makes detailed edges naturally navigate to combine/collect/update/etc.
        sequenceOf(to, from)
            .filterNotNull()
            .firstOrNull { it.kind !in setOf(NodeKind.STATE, NodeKind.FIELD) && it.source != null }
            ?.source
            ?.let { return it }

        return from?.source ?: to?.source
    }

    private fun reachableEdgeDepths(
        model: FlowGraph,
        startId: String,
        forward: Boolean,
        includePossible: Boolean,
    ): Map<FlowEdge, Int> {
        val result = linkedMapOf<FlowEdge, Int>()
        val nodeDepth = mutableMapOf(startId to 0)
        val queue = ArrayDeque<String>()
        queue.addLast(startId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentDepth = nodeDepth[current] ?: 0
            val currentNode = model.node(current)
            if (current != startId && currentNode?.kind in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)) {
                continue
            }

            model.edges.forEach { edge ->
                val matches = if (forward) edge.from == current else edge.to == current
                if (!matches) return@forEach
                if (edge.kind == EdgeKind.READS) return@forEach
                if (!includePossible && isPossibleEdge(edge)) return@forEach

                val next = if (forward) edge.to else edge.from
                val depth = currentDepth + 1
                val previousEdgeDepth = result[edge]
                if (previousEdgeDepth == null || depth < previousEdgeDepth) result[edge] = depth

                val previousNodeDepth = nodeDepth[next]
                if (previousNodeDepth == null || depth < previousNodeDepth) {
                    nodeDepth[next] = depth
                    queue.addLast(next)
                }
            }
        }
        return result
    }

    private fun isPossibleEdge(edge: FlowEdge): Boolean =
        edge.kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE || edge.confidence == CausalConfidence.POSSIBLE

    private fun computeFocusedColumns(model: FlowGraph, selected: String): Map<String, Int> {
        val forward = nodeDistances(model, selected, forward = true)
        val backward = nodeDistances(model, selected, forward = false)
        val raw = mutableMapOf<String, Int>()
        raw[selected] = 0

        model.nodes.forEach { node ->
            if (node.id == selected || node.kind == NodeKind.READ) return@forEach
            val f = forward[node.id]
            val b = backward[node.id]
            when {
                f != null && b != null -> raw[node.id] = 0 // cycle / bidirectional relation
                f != null -> raw[node.id] = f
                b != null -> raw[node.id] = -b
            }
        }

        // One-hop possible/context nodes should sit just outside the definite cone without changing it.
        model.edges.filter(::isPossibleEdge).forEach { edge ->
            if (edge.from in raw && edge.to !in raw) raw[edge.to] = (raw[edge.from] ?: 0) + 1
            if (edge.to in raw && edge.from !in raw) raw[edge.from] = (raw[edge.to] ?: 0) - 1
        }

        val minColumn = raw.values.minOrNull() ?: 0
        return raw.mapValues { (_, value) -> value - minColumn }
    }

    private fun nodeDistances(model: FlowGraph, startId: String, forward: Boolean): Map<String, Int> {
        val distances = mutableMapOf(startId to 0)
        val queue = ArrayDeque<String>()
        queue.addLast(startId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val depth = distances.getValue(current)
            val currentNode = model.node(current)
            if (current != startId && currentNode?.kind in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)) {
                continue
            }
            model.edges.forEach { edge ->
                val matches = if (forward) edge.from == current else edge.to == current
                if (!matches || edge.kind == EdgeKind.READS || isPossibleEdge(edge)) return@forEach
                val next = if (forward) edge.to else edge.from
                if (next !in distances) {
                    distances[next] = depth + 1
                    queue.addLast(next)
                }
            }
        }
        distances.remove(startId)
        return distances
    }

    private fun computeColumns(model: FlowGraph): Map<String, Int> {
        val ids = model.nodes.mapTo(linkedSetOf()) { it.id }
        val incoming = ids.associateWithTo(mutableMapOf()) { 0 }
        val outgoing = ids.associateWithTo(mutableMapOf()) { mutableListOf<String>() }
        model.edges.forEach { edge ->
            if (edge.from !in ids || edge.to !in ids) return@forEach
            outgoing.getValue(edge.from) += edge.to
            incoming[edge.to] = incoming.getValue(edge.to) + 1
        }

        val level = ids.associateWithTo(mutableMapOf()) { 0 }
        val queue = ArrayDeque<String>()
        incoming.filterValues { it == 0 }.keys.forEach(queue::addLast)
        val remainingIncoming = incoming.toMutableMap()

        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            outgoing.getValue(from).forEach { to ->
                level[to] = max(level.getValue(to), level.getValue(from) + 1)
                val left = remainingIncoming.getValue(to) - 1
                remainingIncoming[to] = left
                if (left == 0) queue.addLast(to)
            }
        }

        repeat(ids.size.coerceAtMost(20)) {
            var changed = false
            model.edges.forEach { edge ->
                val proposed = (level[edge.from] ?: 0) + 1
                if (proposed > (level[edge.to] ?: 0) && proposed <= ids.size) {
                    level[edge.to] = proposed
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        return level
    }

    private fun drawNode(g: Graphics2D, node: FlowNode, r: Rectangle) {
        val overviewCompact = r.height <= PROJECT_OVERVIEW_NODE_H
        val color = when (node.kind) {
            NodeKind.STATE -> Color(75, 115, 185)
            NodeKind.FIELD -> Color(68, 135, 160)
            NodeKind.WRITER -> Color(185, 95, 85)
            NodeKind.EXPOSURE -> Color(80, 145, 110)
            NodeKind.OPERATOR -> Color(120, 105, 175)
            NodeKind.COLLECTOR -> Color(185, 140, 65)
            NodeKind.BEHAVIOR -> Color(170, 105, 70)
            NodeKind.READ -> Color(105, 110, 120)
            NodeKind.CYCLE -> Color(135, 85, 155)
            NodeKind.CLUSTER -> Color(85, 95, 110)
        }
        g.color = color
        g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16)

        if (node.id == selectedId) {
            val oldStroke = g.stroke
            g.color = UIManager.getColor("Focus.color") ?: Color.WHITE
            g.stroke = BasicStroke(3f)
            g.drawRoundRect(r.x - 2, r.y - 2, r.width + 4, r.height + 4, 18, 18)
            g.stroke = oldStroke
        }

        val simHop = simulationResult?.hopsThrough(simulationStep)?.lastOrNull { it.targetStateId == node.id }
        val simIsSource = simulationResult?.spec?.sourceStateId == node.id
        if (simHop != null || simIsSource) {
            val oldStroke = g.stroke
            g.color = when (simHop?.status) {
                SimulationStatus.POSSIBLE, SimulationStatus.CONDITIONAL, SimulationStatus.TIMING_DEPENDENT -> Color(230, 170, 55)
                SimulationStatus.SUPPRESSED -> Color(190, 85, 85)
                else -> Color(65, 185, 110)
            }
            g.stroke = BasicStroke(4f)
            g.drawRoundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 20, 20)
            val badge = simHop?.sequence?.toString() ?: "0"
            g.fillOval(r.x + r.width - 20, r.y - 9, 27, 27)
            g.color = Color.WHITE
            g.font = font.deriveFont(Font.BOLD, 11f)
            g.drawString(badge, r.x + r.width - 12, r.y + 9)
            g.stroke = oldStroke
        }

        val runtimeCount = runtimeOverlay.nodeCounts[node.id] ?: 0
        val runtimeDeliveries = runtimeOverlay.deliveryCounts[node.id] ?: 0
        val runtimeCollections = runtimeOverlay.collectCounts[node.id] ?: 0
        val runtimeRequests = runtimeOverlay.emitRequestCounts[node.id] ?: 0
        val runtimeReads = runtimeOverlay.readCounts[node.id] ?: 0
        if (runtimeCount > 0 || runtimeDeliveries > 0 || runtimeCollections > 0 || runtimeRequests > 0 || runtimeReads > 0) {
            val oldStroke = g.stroke

            // "Active" is a transient runtime state, not a permanent selection. Keep historical
            // counters/badges, but fade the cyan activity outline after the cooldown. Use the IDE
            // receipt time rather than RuntimeTraceEvent.timestampNanos because device and host
            // monotonic clocks are not comparable.
            val now = runtimeReferenceMillis ?: System.currentTimeMillis()
            val lastActivity = runtimeOverlay.lastActivityAtMillis[node.id]
            val ageMs = lastActivity?.let { (now - it).coerceAtLeast(0L) }
            val activityStrength = when {
                ageMs == null -> 0f
                ageMs <= RUNTIME_ACTIVITY_HOT_MS -> 1f
                ageMs >= RUNTIME_ACTIVITY_COOLDOWN_MS -> 0f
                else -> 1f - ((ageMs - RUNTIME_ACTIVITY_HOT_MS).toFloat() /
                    (RUNTIME_ACTIVITY_COOLDOWN_MS - RUNTIME_ACTIVITY_HOT_MS).toFloat())
            }
            if (activityStrength > 0f) {
                val alpha = (70 + 185 * activityStrength).toInt().coerceIn(0, 255)
                g.color = Color(70, 205, 205, alpha)
                g.stroke = BasicStroke(1.5f + 2.5f * activityStrength)
                g.drawRoundRect(r.x - 7, r.y - 7, r.width + 14, r.height + 14, 22, 22)
            }

            // Historical activity remains available as a compact badge without making the node
            // look currently active forever.
            g.color = Color(70, 145, 170)
            if (overviewCompact) {
                val activity = runtimeCount + runtimeDeliveries + runtimeCollections + runtimeRequests + runtimeReads
                val badgeText = "×$activity"
                val badgeWidth = (badgeText.length * 6 + 10).coerceAtLeast(28)
                g.fillRoundRect(r.x + r.width - badgeWidth - 5, r.y + 4, badgeWidth, 14, 7, 7)
                g.color = Color.WHITE
                g.font = font.deriveFont(Font.BOLD, 8f)
                g.drawString(badgeText, r.x + r.width - badgeWidth, r.y + 14)
            } else {
                val badgeText = buildString {
                    if (runtimeCount > 0) append("emit ×$runtimeCount")
                    if (runtimeDeliveries > 0) { if (isNotEmpty()) append(" • "); append("deliver ×$runtimeDeliveries") }
                    if (runtimeCollections > 0) { if (isNotEmpty()) append(" • "); append("collect ×$runtimeCollections") }
                    if (runtimeRequests > 0) { if (isNotEmpty()) append(" • "); append("request ×$runtimeRequests") }
                    if (runtimeReads > 0) { if (isNotEmpty()) append(" • "); append("read ×$runtimeReads") }
                }
                val badgeWidth = (badgeText.length * 6 + 12).coerceAtLeast(58)
                g.fillRoundRect(r.x + 8, r.y + r.height - 18, badgeWidth, 16, 8, 8)
                g.color = Color.WHITE
                g.font = font.deriveFont(Font.BOLD, 9f)
                g.drawString(badgeText, r.x + 13, r.y + r.height - 7)
            }
            g.stroke = oldStroke
        }

        g.color = Color.WHITE
        if (overviewCompact) {
            // Overview geometry can be internally compacted to guarantee a complete fit at the
            // hard 20% zoom. Scale text with the actual node box and never paint a second line
            // outside the rectangle (older builds did exactly that for the 34px overview nodes).
            val labelSize = (r.height * 0.34f).coerceIn(4.5f, 10f)
            val inset = (r.height / 6).coerceAtLeast(2)
            g.font = font.deriveFont(Font.BOLD, labelSize)
            val maxChars = ((r.width - inset * 2) / (labelSize * 0.58f)).toInt().coerceAtLeast(3)
            g.drawString(node.label.take(maxChars), r.x + inset, r.y + (r.height * 0.62).toInt())
            if (r.height >= 42 && r.width >= 110) {
                g.font = font.deriveFont(Font.PLAIN, (labelSize * 0.72f).coerceAtLeast(4f))
                g.drawString(node.detail.take(maxChars), r.x + inset, r.y + r.height - 5)
            }
        } else {
            g.font = font.deriveFont(Font.BOLD, 13f)
            g.drawString(node.label.take(33), r.x + 12, r.y + 26)
            g.font = font.deriveFont(Font.PLAIN, 10f)
            g.drawString(node.detail.take(54), r.x + 12, r.y + 51)
        }
    }

    private fun drawArrow(
        g: Graphics2D,
        a: Rectangle,
        b: Rectangle,
        edge: FlowEdge,
        direction: EdgeDirection,
        direct: Boolean,
        directionDistance: Int?,
        hasSelection: Boolean,
        simulationHop: SimulationHop?,
        runtimeCount: Int,
        interCluster: Boolean,
    ) {
        val line = edgeLineFromBounds(edge, a, b)
        val oldStroke = g.stroke
        val normal = UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY

        val simulationColor = when (simulationHop?.status) {
            SimulationStatus.CONDITIONAL, SimulationStatus.TIMING_DEPENDENT, SimulationStatus.POSSIBLE -> Color(230, 170, 55)
            SimulationStatus.SUPPRESSED -> Color(190, 85, 85)
            SimulationStatus.CANCELLATION_AWARE -> Color(115, 185, 235)
            SimulationStatus.DEFINITE -> Color(65, 185, 110)
            null -> null
        }
        val runtimeColor = if (runtimeCount > 0) Color(70, 205, 205) else null
        val directionColor = when (direction) {
            EdgeDirection.FORWARD -> directionalColor(forward = true)
            EdgeDirection.BACKWARD -> directionalColor(forward = false)
            EdgeDirection.BOTH -> cycleColor()
            EdgeDirection.POSSIBLE -> possibleColor()
            EdgeDirection.NONE -> null
        }

        // When a node is selected, causal direction is the primary visual encoding:
        // green flows out to affected state, red flows in from causes. Runtime/simulation
        // information remains in the edge label, but does not obscure the noodle direction.
        val distance = directionDistance ?: if (direct) 1 else 4
        val distanceAlpha = when (distance) {
            1 -> 255
            2 -> 220
            3 -> 180
            else -> 135
        }
        g.color = if (hasSelection) {
            when {
                direction == EdgeDirection.POSSIBLE -> withAlpha(possibleColor(), 205)
                directionColor != null && direct -> directionColor
                directionColor != null -> withAlpha(directionColor, distanceAlpha)
                edge.kind == EdgeKind.READS -> withAlpha(normal, 80)
                else -> withAlpha(normal, 42)
            }
        } else {
            when {
                runtimeColor != null -> runtimeColor
                simulationColor != null -> simulationColor
                interCluster -> withAlpha(normal, 92)
                else -> normal
            }
        }

        val width = when {
            hasSelection && direct && direction in setOf(EdgeDirection.FORWARD, EdgeDirection.BACKWARD, EdgeDirection.BOTH) -> 4.8f
            hasSelection && direction == EdgeDirection.POSSIBLE -> 1.8f
            hasSelection && direction != EdgeDirection.NONE -> when (distance) {
                1 -> 4.0f
                2 -> 3.1f
                3 -> 2.4f
                else -> 1.8f
            }
            runtimeCount > 0 -> 4.6f
            simulationHop != null -> 4.0f
            interCluster -> 0.95f
            edge.kind == EdgeKind.PROPAGATES -> 2.2f
            edge.kind == EdgeKind.READS -> 1.1f
            else -> 1.35f
        }
        val dash = when {
            edge.confidence == CausalConfidence.POSSIBLE -> floatArrayOf(7f, 5f)
            edge.kind == EdgeKind.READS -> floatArrayOf(4f, 4f)
            else -> null
        }
        g.stroke = if (dash != null) {
            BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
        } else {
            BasicStroke(width)
        }

        g.drawLine(line.x1, line.y1, line.x2, line.y2)
        val dir = if (line.x2 >= line.x1) 1 else -1
        val arrow = if (direct && direction != EdgeDirection.NONE) 11 else 9
        g.drawLine(line.x2, line.y2, line.x2 - dir * arrow, line.y2 - 5)
        g.drawLine(line.x2, line.y2, line.x2 - dir * arrow, line.y2 + 5)
        g.font = font.deriveFont(
            if (edge.kind == EdgeKind.PROPAGATES || (direct && direction != EdgeDirection.NONE)) Font.BOLD else Font.PLAIN,
            if (direct && direction != EdgeDirection.NONE) 10f else 9f,
        )
        val overlayLabel = buildString {
            append(edgeDisplayLabel(edge))
            simulationHop?.let { append(" • #${it.sequence} ${it.status.name.lowercase().replace('_', ' ')}") }
            if (runtimeCount > 0) append(" • live ×$runtimeCount")
        }
        val showEdgeLabel = hasSelection || direct || runtimeCount > 0 || simulationHop != null ||
            (!interCluster && zoom >= 0.42)
        if (showEdgeLabel) {
            g.drawString(
                overlayLabel.take(90),
                (line.x1 + line.x2) / 2 - 20,
                (line.y1 + line.y2) / 2 - 5,
            )
        }
        g.stroke = oldStroke
    }

    private fun drawDirectionLegend(g: Graphics2D) {
        val oldStroke = g.stroke
        val oldFont = g.font
        g.font = font.deriveFont(Font.BOLD, 10f)
        g.stroke = BasicStroke(3f)

        var x = 30
        val y = 17
        g.color = directionalColor(forward = false)
        g.drawLine(x, y - 3, x + 24, y - 3)
        g.drawString("upstream / causes", x + 30, y)

        x += 145
        g.color = directionalColor(forward = true)
        g.drawLine(x, y - 3, x + 24, y - 3)
        g.drawString("downstream / affected", x + 30, y)

        x += 170
        g.color = cycleColor()
        g.drawLine(x, y - 3, x + 24, y - 3)
        g.drawString("cycle", x + 30, y)

        x += 92
        g.color = possibleColor()
        g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
        g.drawLine(x, y - 3, x + 24, y - 3)
        g.drawString("possible (does not expand)", x + 30, y)

        g.stroke = oldStroke
        g.font = oldFont
    }

    private fun directionalColor(forward: Boolean): Color {
        val bg = background ?: Color(45, 45, 45)
        val dark = (bg.red + bg.green + bg.blue) / 3 < 140
        return if (forward) {
            if (dark) Color(70, 215, 120) else Color(25, 145, 70)
        } else {
            if (dark) Color(240, 95, 95) else Color(195, 50, 50)
        }
    }

    private fun cycleColor(): Color {
        val bg = background ?: Color(45, 45, 45)
        val dark = (bg.red + bg.green + bg.blue) / 3 < 140
        return if (dark) Color(200, 125, 235) else Color(145, 75, 175)
    }

    private fun possibleColor(): Color {
        val bg = background ?: Color(45, 45, 45)
        val dark = (bg.red + bg.green + bg.blue) / 3 < 140
        return if (dark) Color(230, 170, 70) else Color(180, 115, 25)
    }

    private fun edgeLineFromBounds(edge: FlowEdge, a: Rectangle, b: Rectangle): EdgeLine {
        val right = b.centerX >= a.centerX
        return EdgeLine(
            edge = edge,
            x1 = if (right) a.x + a.width else a.x,
            y1 = a.y + a.height / 2,
            x2 = if (right) b.x else b.x + b.width,
            y2 = b.y + b.height / 2,
        )
    }

    private fun edgeDisplayLabel(edge: FlowEdge): String = edge.label ?: run {
        val base = edge.kind.name.lowercase().replace('_', ' ')
        val fields = if (edge.affectedFields.isEmpty()) "" else {
            val shown = edge.affectedFields
                .map { if (it == "\$value") "value" else it }
                .sorted()
                .take(2)
                .joinToString(",")
            " • $shown" + if (edge.affectedFields.size > 2) ",…" else ""
        }
        base + fields
    }

    private fun blend(a: Color, b: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
            (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
            (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
        )
    }

    private fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun nodeSortKey(kind: NodeKind): Int = when (kind) {
        NodeKind.WRITER -> 0
        NodeKind.FIELD -> 1
        NodeKind.STATE -> 2
        NodeKind.EXPOSURE -> 3
        NodeKind.OPERATOR -> 4
        NodeKind.COLLECTOR -> 5
        NodeKind.BEHAVIOR -> 6
        NodeKind.CYCLE -> 7
        NodeKind.CLUSTER -> 8
        NodeKind.READ -> 9
    }

    private companion object {
        const val EDGE_HIT_DISTANCE = 12.0
        const val MIN_ZOOM = 0.20
        const val MAX_ZOOM = 3.50
        const val ZOOM_STEP = 1.18
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
        const val PROJECT_OVERVIEW_MIN_ZOOM = 0.20
        const val PROJECT_OVERVIEW_MAX_GRID_COLUMNS = 18
        const val PROJECT_OVERVIEW_MAX_CLUSTER_NODES = 24
        const val PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS = 6
    }
}
