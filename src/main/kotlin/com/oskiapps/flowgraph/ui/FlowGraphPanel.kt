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

internal const val RUNTIME_ACTIVITY_REPAINT_MS = 50
internal const val RUNTIME_ACTIVITY_HOT_MS = 700L
internal const val RUNTIME_ACTIVITY_COOLDOWN_MS = 2_500L
internal const val CIRCUIT_GRID_SPACING = 28
internal const val CIRCUIT_LANE_SPACING = 10
internal const val EVENT_LENS_MIN_NODE_WIDTH_PX = 150
internal const val EVENT_LENS_MIN_NODE_HEIGHT_PX = 48
internal const val EVENT_LENS_W = 286
internal const val EVENT_LENS_H = 92
internal const val MAGNIFIER_W = 360
internal const val MAGNIFIER_H = 250
internal const val MAGNIFIER_MARGIN = 16
internal const val CLUSTER_NAV_FIT_PADDING = 54

internal fun isAllProjectFlows(label: String?): Boolean =
    label?.startsWith("All project flows") == true

/**
 * Source composables use runtime identities emitted by FlowGraph instrumentation.
 * Keep this at file scope because both FlowGraphPanel and the separate GraphCanvas
 * renderer/layout class need to classify the same runtime keys.
 */
internal fun isSourceComposeRuntimeKey(key: String?): Boolean =
    key?.startsWith("@compose2|") == true || key?.startsWith("@compose|") == true


internal data class CurrentUiRuntimeCandidate(
    val runtimeKey: String,
    val visibleArea: Long,
    val responseOrder: Int,
)

/** One fixed navigation target for a currently rendered overview cluster/lane. */
internal data class ClusterNavigationEntry(
    val index: Int,
    val label: String,
)

class FlowGraphPanel(internal val project: Project) : JPanel(BorderLayout()) {
    internal val title = JLabel("Put the caret on a Flow/StateFlow/Compose State and choose Show Flow Graph")
    internal val hint = JLabel("Click a node to isolate its causal cone • wheel to zoom • drag to pan • click an edge to open its call site • Circuit view adds routed orthogonal traces")
    internal val showOperators = JCheckBox("Show operators").apply {
        toolTipText = "Expand Flow-to-Flow propagation arrows into operators, collectors, behaviors and write sites"
    }
    internal val showReads = JCheckBox("Reads").apply {
        isSelected = true
        toolTipText = "Show read-only observers in a separate graph cluster"
    }
    internal val includePossible = JCheckBox("Expand possible").apply {
        isSelected = false
        toolTipText = "Allow inferred/possible coupling to expand the focused causal cone. This only applies while Possible connections are visible."
    }
    internal val circuitView = JCheckBox("Circuit").apply {
        isSelected = true
        toolTipText = "Alternative routing with orthogonal circuit-style traces, subtle grid and clearer crossings"
    }
    internal val expandLibraries = JCheckBox("Expand libs").apply {
        isSelected = false
        toolTipText = "Expand framework/dependency Flow nodes instead of collapsing them into boundary clusters"
    }
    internal val depth = JComboBox(arrayOf("1", "2", "3", "All")).apply {
        selectedItem = "3"
        toolTipText = "Maximum causal distance shown upstream/downstream from the selected node"
        preferredSize = Dimension(58, preferredSize.height)
    }
    internal val fieldFocus = JComboBox(arrayOf("All fields")).apply {
        isEnabled = false
        toolTipText = "Restrict the selected StateFlow to one discovered state field"
        preferredSize = Dimension(130, preferredSize.height)
    }
    internal val allFlows = JButton("Load all flows").apply {
        toolTipText = "Analyze project Flow/StateFlow/SharedFlow plus Compose State/MutableState and show disconnected groups as separate clusters"
    }
    internal val allFlowsStatusIndicator = JLabel("● Idle").apply {
        toolTipText = "Project-wide Flow analysis status"
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }
    internal val showComposeState = JCheckBox("Compose state").apply {
        isSelected = true
        toolTipText = "Show Jetpack Compose State / MutableState nodes in the project overview"
        isOpaque = false
    }
    internal val showCoroutineFlows = JCheckBox("Coroutine Flow/StateFlow").apply {
        isSelected = true
        toolTipText = "Show kotlinx.coroutines Flow, StateFlow and SharedFlow state plus their local causal plumbing"
        isOpaque = false
    }
    internal val flowInfluencedComposablesOnly = JCheckBox("Flow-influenced UI only").apply {
        isSelected = false
        toolTipText = "Show only composables that are causally reached by a coroutine Flow/StateFlow/SharedFlow. Rebuild, repack and fit the graph; unrelated Compose branches are removed."
        isOpaque = false
    }
    internal val showComposeViews = JCheckBox("Compose views").apply {
        isSelected = true
        toolTipText = "Show composables and visual Compose call-site trees in the project overview"
        isOpaque = false
    }
    internal val projectComposablesOnly = JCheckBox("Project composables only").apply {
        isSelected = true
        toolTipText = "Hide AndroidX/framework Compose call-site nodes such as Column, Row, Text, Surface and Offset; reconnect project composables directly and rebuild the graph compactly."
        isOpaque = false
    }
    internal val showPossibleConnections = JCheckBox("Possible connections").apply {
        isSelected = true
        toolTipText = "Show inferred/possible causal connections. Uncheck to remove them, rebuild the visible graph, repack it, and fit the compact result."
        isOpaque = false
    }
    internal val conciseOverview = JCheckBox("Concise overview").apply {
        isSelected = true
        toolTipText = "Keep All Flows architectural: show a compact Flow/state dependency graph feeding Compose, fold duplicate Compose calls, group observers, and shorten edge labels. Runtime branches retain observed coroutine stages. Detail view stays fully expanded."
        isOpaque = false
    }
    internal val overviewDirectionLabel = JLabel("Layout:").apply {
        isVisible = false // Circuit is selected by default.
    }
    internal val overviewDirection = JComboBox(arrayOf("Horizontal", "Vertical")).apply {
        isVisible = false // Circuit uses its own causal left-to-right layout.

        selectedItem = "Horizontal"
        toolTipText = "Choose the reading direction inside each All Flows cluster. Clusters always stay stacked vertically; Horizontal reads left-to-right inside a cluster and Vertical reads top-to-bottom. Changing it rebuilds and fits the overview."
        preferredSize = Dimension(102, preferredSize.height)
    }
    internal val extras = JToggleButton("View settings ▾").apply {
        toolTipText = "Show or hide graph display filters and analysis controls"
        margin = Insets(2, 8, 2, 8)
    }
    internal val showAll = JButton("Show all").apply {
        isEnabled = false
        toolTipText = "Restore the complete graph"
        margin = Insets(3, 8, 3, 8)
    }
    internal val affected = JButton("Affected ↓").apply { isEnabled = false }
    internal val causes = JButton("Causes ↑").apply { isEnabled = false }
    internal val liveTrace = JToggleButton("Live").apply {
        toolTipText = "Start/stop the localhost live-trace server on port 50737"
    }
    internal val adbReverse = JButton("ADB reverse").apply {
        toolTipText = "Map device tcp:50737 to the local Flow Graph live server"
        margin = Insets(2, 8, 2, 8)
    }
    internal val liveStatusIndicator = JLabel("● Off").apply {
        toolTipText = "Live trace transport status"
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }
    internal val currentUi = JButton("Current UI").apply {
        isEnabled = false
        toolTipText = "Jump to a Flow Graph composable that is currently visible on the connected phone"
        margin = Insets(2, 8, 2, 8)
    }
    internal val liveInfo = JToggleButton("ⓘ").apply {
        toolTipText = "Show/hide live trace diagnostics"
        margin = Insets(2, 5, 2, 5)
    }
    /** Most recent Current UI inspector status/error, surfaced in the Live ⓘ diagnostics panel. */
    internal var currentUiDiagnostic: String? = null
    internal val recentOnly = JToggleButton("Recently active").apply {
        toolTipText = "Show only nodes active in the last 2.5s, plus short connector paths between them"
    }
    internal val runtimeBranchesOnly = JCheckBox("Runtime branches only").apply {
        isSelected = false
        toolTipText = "Rebuild All Flows from runtime-observed branches since Clear runtime. Keep the causal coroutine Flow/operator/state graph and its Compose targets; inactive sibling branches stay hidden. Detail view remains complete."
        isOpaque = false
    }
    internal val eventLens = JToggleButton("Event lens").apply {
        isSelected = false
        toolTipText = "When zoomed out, temporarily magnify the most recently active node beside the live event"
    }
    internal val magnifier = JToggleButton("🔍").apply {
        isSelected = false
        toolTipText = "Magnifier: show a zoomed view of the graph under the mouse in the bottom-right corner"
        margin = Insets(2, 5, 2, 5)
    }
    internal val graphSearch = JButton("🔎 Search").apply {
        toolTipText = "Find and highlight nodes in the visible graph"
        margin = Insets(2, 6, 2, 6)
    }
    internal val graphSearchField = JTextField(18).apply {
        toolTipText = "Search node names, details, ids and source files"
        isVisible = false
        preferredSize = Dimension(180, preferredSize.height)
    }
    internal val graphSearchClose = JButton("×").apply {
        toolTipText = "Close search"
        margin = Insets(2, 5, 2, 5)
        isVisible = false
    }
    internal val clusterNavigatorButtons = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
    }
    internal val clusterNavigatorScroll = JScrollPane(clusterNavigatorButtons).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Clusters"),
            BorderFactory.createEmptyBorder(2, 2, 2, 2),
        )
        preferredSize = Dimension(205, 320)
        minimumSize = Dimension(155, 120)
        isVisible = false
        toolTipText = "Jump to a graph cluster/lane; clicking a name centers and fits that cluster"
    }
    internal var selectedClusterNavigationIndex: Int? = null
    internal val clusterNavigatorButtonByIndex = linkedMapOf<Int, JToggleButton>()
    internal val liveDiagnostics = JTextArea(5, 88).apply {
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
    internal val clearRuntime = JButton("Clear live").apply {
        isEnabled = false
        toolTipText = "Clear runtime emission counts and observed paths"
    }
    internal val backToGraph = JButton("← Back").apply {
        isEnabled = false
        toolTipText = "Return to the previous graph view"
        margin = Insets(2, 7, 2, 7)
    }

    internal val zoomOut = compactZoomButton("−", "Zoom out")
    internal val zoomReset = JButton("100%").apply {
        toolTipText = "Reset zoom to 100%"
        margin = Insets(2, 5, 2, 5)
    }
    internal val zoomFit = JButton("Fit").apply {
        toolTipText = "Fit the complete graph into the visible viewport"
        margin = Insets(2, 5, 2, 5)
    }
    internal val zoomIn = compactZoomButton("+", "Zoom in")
    internal val expandLeft = JButton("<").apply {
        isVisible = false
        isEnabled = false
        toolTipText = "Expand one more upstream causal level"
        margin = Insets(8, 8, 8, 8)
        preferredSize = Dimension(34, 58)
        font = font.deriveFont(Font.BOLD, 18f)
    }
    internal val expandRight = JButton(">").apply {
        isVisible = false
        isEnabled = false
        toolTipText = "Expand one more downstream causal level"
        margin = Insets(8, 8, 8, 8)
        preferredSize = Dimension(34, 58)
        font = font.deriveFont(Font.BOLD, 18f)
    }

    internal val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "Select a Flow/StateFlow to see which other flows it can affect, runtime deliveries, side-effect writes, collectors and upstream causes."
    }

    internal val composePreviewCount = JLabel("Active compositions: —").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Number of live Compose compositions seen by the runtime inspector"
    }
    internal val composePreviewInfo = JToggleButton("ⓘ").apply {
        toolTipText = "Show/hide Compose render diagnostics"
        margin = Insets(2, 5, 2, 5)
    }
    internal val composePreviewDetails = JTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 5
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = UIManager.getColor("Panel.background")
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Compose render info"),
            BorderFactory.createEmptyBorder(3, 6, 4, 6),
        )
        isVisible = false
    }
    internal val composePreviewCanvas = ComposePreviewCanvas()
    internal val composePreviewRefresh = JButton("Refresh").apply {
        isEnabled = false
        toolTipText = "Inspect and render the selected Compose node again from the running app"
    }
    internal val composePreviewOpen = JButton("Open large").apply {
        isEnabled = false
        toolTipText = "Open the current Compose render at a larger size"
    }
    /** UI render belongs to focused node inspection, never to the All Flows overview. */
    internal var composePreviewContainer: JPanel? = null
    internal var detailsContentPane: JPanel? = null
    internal var graphDetailsSplitPane: JSplitPane? = null

    internal var fullGraph: FlowGraph? = null
    /** Full-detail graph currently in scope. The visible graph may be its collapsed state projection. */
    internal var activeGraph: FlowGraph? = null
    internal var selectedNodeId: String? = null
    internal var detailFocusNodeId: String? = null
    internal var selectionFocusActive: Boolean = false
    internal val navigationHistory = GraphNavigationHistory()

    internal var detailUpstreamDepth: Int? = 3
    internal var detailDownstreamDepth: Int? = 3
    internal var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
    internal var historicalRuntimeOverlay: RuntimeOverlay? = null
    internal var historyCursorMillis: Long? = null
    internal val recentRuntimeEmissionNanos = mutableMapOf<String, Long>()
    internal val liveTraceService: LiveTraceService = project.getService(LiveTraceService::class.java)
    internal val composeRenderService: ComposeRenderService = project.getService(ComposeRenderService::class.java)
    internal val runtimeTimeline = RuntimeTimelinePanel(
        onScrubStarted = ::pauseRuntimeRecordingForHistory,
        onScrubbed = ::onTimelineScrubbed,
    )
    internal var liveTransportStatus: LiveTraceStatus = liveTraceService.status()
    internal var runtimeEventsSeen: Int = 0
    internal var matchedRuntimeEvents: Int = 0
    internal var unmatchedRuntimeEvents: Int = 0
    internal var uiInteractionEventsSeen: Int = 0
    internal val unmatchedRuntimeKeys = linkedMapOf<String, Int>()
    internal var lastRecentFilterIds: Set<String> = emptySet()
    internal var lastRuntimeBranchFilterIds: Set<String> = emptySet()
    internal var lastRecentFilterRefreshMillis: Long = 0L
    internal var lastLiveDetailsRefreshMillis: Long = 0L
    internal var lastLiveDiagnosticsRefreshMillis: Long = 0L
    internal var pendingHistoricalDetailsCursorMillis: Long? = null
    internal val historicalDetailsTimer = Timer(HISTORICAL_DETAILS_REFRESH_MS) {
        val cursor = pendingHistoricalDetailsCursorMillis
        pendingHistoricalDetailsCursorMillis = null
        if (cursor != null && historyCursorMillis == cursor) updateHistoricalDetails(cursor)
    }.apply { isRepeats = false }
    internal var pendingCurrentComposeCandidates: List<CurrentUiRuntimeCandidate>? = null
    internal var currentComposeRequestStartedAt: Long = 0L

    // Deep Flow instrumentation can produce thousands of deliveries per second. Never enqueue
    // one Swing Runnable per event: that starves the EDT and can make Android Studio appear hung.
    internal val runtimeEventBuffer = RuntimeEventBuffer(MAX_PENDING_RUNTIME_EVENTS)
    internal val runtimeFlushTimer = Timer(LIVE_UI_FLUSH_MS) { flushRuntimeEvents() }.apply {
        isRepeats = true
        start()
    }
    // Recomposition is only a refresh trigger; geometry still comes from the live slot table.
    // Debounce so hot recomposition never turns UI capture into a frame-by-frame profiler.
    internal val composeAutoRefreshTimer = Timer(COMPOSE_AUTO_REFRESH_MS) {
        val node = selectedNodeId?.let { fullGraph?.node(it) }
        if (node != null && isRenderableCompose(node) && composeRenderService.state(node.id) !is ComposeRenderState.Loading) {
            requestComposeRenderingFor(node, force = true)
        }
    }.apply { isRepeats = false }
    internal val liveTraceListener: (RuntimeTraceEvent) -> Unit = listener@{ event ->
        if (!liveTrace.isSelected) return@listener
        // Compose images can be megabytes and are consumed directly by ComposeRenderService from
        // LiveTraceService history. Never feed them into the profiler/timeline UI queue.
        if (event.kind in setOf("compose-image", "compose-image-error", "compose-current", "compose-current-error")) return@listener
        runtimeEventBuffer.offer(event)
    }
    internal val liveStatusListener: (LiveTraceStatus) -> Unit = { status ->
        SwingUtilities.invokeLater {
            // Status callbacks may reach the EDT out of order during rapid reconnects.
            liveTransportStatus = liveTraceService.status()
            updateLiveStatusIndicator(liveTransportStatus)
            updateLiveTraceDiagnostics()
        }
    }
    internal var fitRequestGeneration: Long = 0L
    internal var autoFitGeneration: Long = 0L
    internal var autoFitApplying: Boolean = false

    internal val canvas = GraphCanvas(
        project = project,
        onNodeSelected = ::selectNode,
        onZoomChanged = { zoom ->
            zoomReset.text = "${(zoom * 100).toInt()}%"
        },
        onUserViewportInteraction = {
            if (!autoFitApplying) {
                fitRequestGeneration++
                cancelPendingAutoFit()
            }
        },
        onClustersChanged = ::updateClusterNavigator,
        onActiveClusterChanged = ::updateActiveClusterNavigation,
    )
    /**
     * Runtime activity is intentionally transient. Counts/history remain, but the cyan "hot"
     * outline fades away after a short cooldown so old emissions do not look permanently active.
     * This timer only runs while at least one node is cooling down.
     */
    internal val runtimeActivityCooldownTimer = Timer(RUNTIME_ACTIVITY_REPAINT_MS) { event ->
        canvas.repaint()
        val now = System.currentTimeMillis()
        if (liveTrace.isSelected && recentOnly.isSelected) refreshRecentActivityFilterIfNeeded(now = now)
        val stillCooling = runtimeOverlay.lastActivityAtMillis.values.any {
            now - it <= RUNTIME_ACTIVITY_COOLDOWN_MS
        } || (liveTrace.isSelected && recentOnly.isSelected && lastRecentFilterIds.isNotEmpty())
        if (!stillCooling) (event.source as? Timer)?.stop()
    }.apply { isRepeats = true }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        // Keep the main row intentionally small: project-wide loading and live runtime controls
        // are always visible. Less-frequent graph filters live in a persistent inline Extras pane.
        // A JPopupMenu used here previously dismissed itself whenever any contained toggle/combo
        // was clicked, which made the controls feel broken. This panel stays open until explicitly
        // collapsed, so several settings can be changed in one pass.
        val extrasClose = JButton("×").apply {
            toolTipText = "Close view settings"
            margin = Insets(1, 6, 1, 6)
            isFocusable = false
        }
        val extrasControls = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            add(showComposeState)
            add(showCoroutineFlows)
            add(flowInfluencedComposablesOnly)
            add(runtimeBranchesOnly)
            add(showComposeViews)
            add(projectComposablesOnly)
            add(showPossibleConnections)
            add(conciseOverview)
            add(showReads)
            add(includePossible)
            add(circuitView)
            add(JLabel("Depth"))
            add(depth)
            add(JLabel("Field"))
            add(fieldFocus)
            add(expandLibraries)
            add(showOperators)
            add(causes)
            add(affected)
        }
        val extrasPanel = JPanel(BorderLayout(6, 0)).apply {
            isVisible = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(4, 6, 4, 4),
            )
            add(extrasControls, BorderLayout.CENTER)
            add(extrasClose, BorderLayout.EAST)
        }
        fun setExtrasVisible(visible: Boolean) {
            extrasPanel.isVisible = visible
            extras.isSelected = visible
            extras.text = if (visible) "View settings ▴" else "View settings ▾"
            extrasPanel.revalidate()
            extrasPanel.parent?.revalidate()
            revalidate()
            repaint()
        }
        extras.addActionListener { setExtrasVisible(extras.isSelected) }
        extrasClose.addActionListener { setExtrasVisible(false) }

        val actions = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(allFlows)
            add(allFlowsStatusIndicator)
            add(liveTrace)
            add(liveStatusIndicator)
            add(adbReverse)
            add(currentUi)
            add(clearRuntime)
            add(liveInfo)
            add(recentOnly)
            add(eventLens)
            add(extras)
        }
        val header = JPanel(BorderLayout(8, 4)).apply {
            border = BorderFactory.createEmptyBorder(0, 2, 8, 2)
            val text = JPanel(GridLayout(0, 1, 0, 2)).apply {
                isOpaque = false
                add(title)
                add(hint)
            }
            val controls = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                actions.alignmentX = Component.LEFT_ALIGNMENT
                extrasPanel.alignmentX = Component.LEFT_ALIGNMENT
                add(actions)
                add(extrasPanel)
            }
            add(text, BorderLayout.NORTH)
            add(controls, BorderLayout.SOUTH)
        }

        // WrapLayout calculates its height from the current available width. Revalidate the
        // header whenever the tool window changes width so newly wrapped/unwrapped rows get space.
        header.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                actions.revalidate()
                extrasControls.revalidate()
                extrasPanel.revalidate()
                header.revalidate()
            }
        })

        backToGraph.addActionListener { navigateBack() }
        zoomOut.addActionListener { cancelPendingAutoFit(); canvas.zoomOut() }
        zoomReset.addActionListener { cancelPendingAutoFit(); canvas.resetZoom() }
        zoomIn.addActionListener { cancelPendingAutoFit(); canvas.zoomIn() }

        showComposeState.addActionListener { reloadVisibilityFilters() }
        showCoroutineFlows.addActionListener { reloadVisibilityFilters() }
        flowInfluencedComposablesOnly.addActionListener { reloadVisibilityFilters() }
        showComposeViews.addActionListener { reloadVisibilityFilters() }
        projectComposablesOnly.addActionListener { reloadVisibilityFilters() }
        showPossibleConnections.addActionListener {
            includePossible.isEnabled = showPossibleConnections.isSelected
            reloadVisibilityFilters()
        }
        conciseOverview.addActionListener {
            renderActive(autoFit = !selectionFocusActive)
        }
        overviewDirection.addActionListener {
            val vertical = overviewDirection.selectedItem == "Vertical"
            if (canvas.projectOverviewVertical != vertical) {
                canvas.projectOverviewVertical = vertical
                canvas.clearFrozenLayout()
                renderActive(autoFit = true)
            }
        }
        showReads.addActionListener { renderActive(autoFit = selectionFocusActive) }
        includePossible.addActionListener { renderActive(autoFit = selectionFocusActive) }
        circuitView.addActionListener {
            canvas.circuitViewEnabled = circuitView.isSelected
            overviewDirection.isVisible = !circuitView.isSelected
            overviewDirectionLabel.isVisible = !circuitView.isSelected
            overviewDirection.parent?.revalidate()
            overviewDirection.parent?.repaint()
            canvas.clearFrozenLayout()
            renderActive(autoFit = true)
        }
        depth.addActionListener {
            if (selectionFocusActive) resetDetailExpansionDepths()
            renderActive(autoFit = selectionFocusActive)
        }
        fieldFocus.addActionListener {
            if (fieldFocus.isEnabled) renderActive(autoFit = selectionFocusActive)
        }
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
        liveTrace.addActionListener { toggleLiveTrace() }
        adbReverse.addActionListener { configureAdbReverse() }
        liveInfo.addActionListener {
            liveDiagnostics.isVisible = liveTrace.isSelected && liveInfo.isSelected
            updateLiveTraceDiagnostics()
            revalidate()
            repaint()
        }
        recentOnly.addActionListener {
            lastRecentFilterIds = if (recentOnly.isSelected) recentActiveNodeIds() else emptySet()
            lastRecentFilterRefreshMillis = System.currentTimeMillis()
            canvas.recentFocusEnabled = recentOnly.isSelected
            canvas.recentFocusIds = if (recentOnly.isSelected) lastRecentFilterIds else emptySet()
            if (recentOnly.isSelected && lastRecentFilterIds.isNotEmpty() &&
                !runtimeActivityCooldownTimer.isRunning
            ) runtimeActivityCooldownTimer.start()
            renderActive(autoFit = false)
        }
        runtimeBranchesOnly.addActionListener {
            lastRuntimeBranchFilterIds = runtimeObservedNodeIds()
            reloadVisibilityFilters()
        }
        eventLens.addActionListener {
            canvas.eventLensEnabled = eventLens.isSelected
        }
        magnifier.addActionListener {
            canvas.magnifierEnabled = magnifier.isSelected
        }
        graphSearch.addActionListener {
            graphSearch.isVisible = false
            graphSearchField.isVisible = true
            graphSearchClose.isVisible = true
            graphSearchField.requestFocusInWindow()
            graphSearchField.selectAll()
            graphSearchField.parent?.revalidate()
            graphSearchField.parent?.repaint()
        }
        graphSearchClose.addActionListener {
            graphSearchField.text = ""
            canvas.searchQuery = ""
            graphSearchField.isVisible = false
            graphSearchClose.isVisible = false
            graphSearch.isVisible = true
            graphSearch.parent?.revalidate()
            graphSearch.parent?.repaint()
        }
        graphSearchField.document.addDocumentListener(object : DocumentListener {
            private fun update() { canvas.searchQuery = graphSearchField.text }
            override fun insertUpdate(e: DocumentEvent?) = update()
            override fun removeUpdate(e: DocumentEvent?) = update()
            override fun changedUpdate(e: DocumentEvent?) = update()
        })
        zoomFit.addActionListener {
            cancelPendingAutoFit()
            // Fit against the settled viewport, not the previous Swing layout pass.
            // An outstanding manual zoom or a newer Fit invalidates this request.
            val request = ++fitRequestGeneration
            SwingUtilities.invokeLater {
                if (request != fitRequestGeneration) return@invokeLater
                graphFitAction()
            }
        }
        composePreviewInfo.addActionListener {
            composePreviewDetails.isVisible = composePreviewInfo.isSelected
            composePreviewDetails.parent?.revalidate()
            composePreviewDetails.parent?.repaint()
        }
        composePreviewRefresh.addActionListener {
            val node = selectedNodeId?.let { fullGraph?.node(it) }?.takeIf { it.kind == NodeKind.COMPOSABLE }
            if (node != null) requestComposeRenderingFor(node, force = true)
        }
        composePreviewOpen.addActionListener {
            val node = selectedNodeId?.let { fullGraph?.node(it) }?.takeIf { it.kind == NodeKind.COMPOSABLE }
            val snapshot = node?.let { composeRenderService.snapshot(it.id) }
            if (node != null && snapshot != null) showComposeRenderDialog(node, snapshot)
        }
        currentUi.addActionListener { jumpToCurrentPhoneComposable() }
        clearRuntime.addActionListener { clearRuntimeOverlay() }
        liveTraceService.addListener(liveTraceListener)
        liveTraceService.addStatusListener(liveStatusListener)
        liveTrace.isSelected = liveTransportStatus.running
        updateLiveStatusIndicator(liveTransportStatus)

        val graphScroll = JScrollPane(canvas)
        graphScroll.viewport.addChangeListener {
            canvas.updateActiveClusterFromViewport()
        }
        // One width-aware toolbar instead of fixed WEST/EAST groups. Every control participates
        // in the same WrapLayout, so a narrow tool window simply gains another menu row instead
        // of clipping the filters or pushing graph actions off-screen.
        val graphViewportHeader = JPanel(WrapLayout(FlowLayout.LEFT, 7, 3)).apply {
            isOpaque = true
            background = UIManager.getColor("Panel.background") ?: Color(45, 45, 45)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(2, 4, 2, 4),
            )
            toolTipText = "Graph filters and view controls"

            add(Box.createHorizontalStrut(8))
            add(backToGraph)
            add(showAll)
            add(zoomOut)
            add(zoomReset)
            add(zoomIn)
            add(zoomFit)
            add(overviewDirectionLabel)
            add(overviewDirection)
            add(graphSearch)
            add(graphSearchField)
            add(graphSearchClose)
        }
        graphScroll.setColumnHeaderView(graphViewportHeader)
        graphScroll.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // WrapLayout recalculates its preferred height from the newly available width.
                graphViewportHeader.revalidate()
                graphScroll.columnHeader?.revalidate()
                graphScroll.revalidate()
            }
        })
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
        val graphRightRail = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(rightExpander, BorderLayout.WEST)
            add(clusterNavigatorScroll, BorderLayout.CENTER)
        }
        // Keep the magnifier in the graph pane's lower-right corner. A normal
        // BorderLayout guarantees Swing lays out the button; the former layered
        // pane could lose the overlay when its scroll viewport was resized.
        val graphOverlay = JPanel(BorderLayout()).apply {
            add(graphScroll, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 2, 8)
                add(magnifier)
            }, BorderLayout.SOUTH)
        }
        val graphWithExpanders = JPanel(BorderLayout()).apply {
            add(leftExpander, BorderLayout.WEST)
            add(graphOverlay, BorderLayout.CENTER)
            add(graphRightRail, BorderLayout.EAST)
        }
        val detailsScroll = JScrollPane(details)
        val composePreviewPanel = JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("UI render"),
                BorderFactory.createEmptyBorder(6, 8, 8, 8),
            )
            val heading = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(composePreviewCount, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                    isOpaque = false
                    add(composePreviewRefresh)
                    add(composePreviewOpen)
                    add(composePreviewInfo)
                }, BorderLayout.EAST)
            }
            add(heading, BorderLayout.NORTH)
            add(composePreviewCanvas, BorderLayout.CENTER)
            add(composePreviewDetails, BorderLayout.SOUTH)
            minimumSize = Dimension(320, 190)
            isVisible = false
        }
        composePreviewContainer = composePreviewPanel
        val lowerSide = JPanel(BorderLayout(0, 6)).apply {
            add(detailsScroll, BorderLayout.CENTER)
            add(liveDiagnostics, BorderLayout.SOUTH)
            minimumSize = Dimension(320, 150)
        }
        // The UI render occupies the entire sidebar. The former debug/details pane
        // below it is available only when there is no live UI render.
        val rightSide = JPanel(java.awt.CardLayout()).apply {
            isVisible = false
            preferredSize = Dimension(430, 520)
            minimumSize = Dimension(320, 300)
            add(lowerSide, "details")
            add(composePreviewPanel, "render")
        }
        detailsContentPane = rightSide
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphWithExpanders, rightSide).apply {
            resizeWeight = 0.72
            isContinuousLayout = true
            dividerSize = 7
        }

        graphDetailsSplitPane = split

        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
        }

        val center = JPanel(BorderLayout()).apply {
            add(split, BorderLayout.CENTER)
            add(runtimeTimeline, BorderLayout.SOUTH)
        }

        runtimeTimeline.setEvents(liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS))
        updateLiveControlVisibility()

        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    /**
     * The live Compose render is a node-inspection aid. Keeping it mounted in All Flows steals a
     * large part of the viewport and suggests that the overview itself has one selected render.
     * Show it only while a node-focused detail graph is active.
     */



















































    internal companion object {
        const val MAX_DETAIL_ITEMS = 20
        const val MAX_UNMATCHED_KEYS = 5
        const val MAX_REPLAY_EVENTS = 2_000
        const val MAX_HISTORY_DETAIL_EVENTS = 80
        const val MAX_COMPOSE_INSTANCE_DETAIL_ITEMS = 12
        const val MAX_COMPOSE_INSTANCES_PER_NODE = 256
        const val LIVE_EDGE_WINDOW_NANOS = 750_000_000L
        const val HISTORY_EDGE_WINDOW_MS = 750L
        // Frequent enough for visually continuous activity, while expensive details panes are
        // throttled separately below.
        const val LIVE_UI_FLUSH_MS = 50
        const val COMPOSE_AUTO_REFRESH_MS = 500
        const val MAX_RUNTIME_EVENTS_PER_FLUSH = 180
        // Hard EDT budget for one live batch. Bursts stay queued/coalesced instead of producing a
        // long frame that makes zoom/pan/timeline input hitch.
        const val RUNTIME_FLUSH_BUDGET_NANOS = 6_000_000L
        const val LIVE_DETAILS_REFRESH_MS = 180L
        const val LIVE_DIAGNOSTICS_REFRESH_MS = 250L
        const val HISTORICAL_DETAILS_REFRESH_MS = 90
        const val MAX_PENDING_RUNTIME_EVENTS = 1_200
        const val RECENT_FILTER_REFRESH_MS = 250L
        val HISTORY_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS")
    }









}

