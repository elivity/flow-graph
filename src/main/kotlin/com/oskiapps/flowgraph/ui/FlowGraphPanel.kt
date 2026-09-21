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
import kotlin.math.sqrt

private const val RUNTIME_ACTIVITY_REPAINT_MS = 50
private const val RUNTIME_ACTIVITY_HOT_MS = 700L
private const val RUNTIME_ACTIVITY_COOLDOWN_MS = 2_500L
private const val CIRCUIT_GRID_SPACING = 28
private const val CIRCUIT_LANE_SPACING = 10
private const val EVENT_LENS_MIN_NODE_WIDTH_PX = 150
private const val EVENT_LENS_MIN_NODE_HEIGHT_PX = 48
private const val EVENT_LENS_W = 286
private const val EVENT_LENS_H = 92
private const val MAGNIFIER_W = 360
private const val MAGNIFIER_H = 250
private const val MAGNIFIER_MARGIN = 16

private fun isAllProjectFlows(label: String?): Boolean =
    label?.startsWith("All project flows") == true

class FlowGraphPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val title = JLabel("Put the caret on a Flow/StateFlow/Compose State and choose Show Flow Graph")
    private val hint = JLabel("Click a node to isolate its causal cone • wheel to zoom • drag to pan • click an edge to open its call site • Circuit view adds routed orthogonal traces")
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
    private val circuitView = JToggleButton("Circuit").apply {
        toolTipText = "Alternative routing with orthogonal circuit-style traces, subtle grid and clearer crossings"
    }
    private val expandLibraries = JToggleButton("Expand libs").apply {
        isSelected = false
        toolTipText = "Expand framework/dependency Flow nodes instead of collapsing them into boundary clusters"
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
    private val allFlows = JButton("Load all flows").apply {
        toolTipText = "Analyze project Flow/StateFlow/SharedFlow plus Compose State/MutableState and show disconnected groups as separate clusters"
    }
    private val allFlowsStatusIndicator = JLabel("● Idle").apply {
        toolTipText = "Project-wide Flow analysis status"
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }
    private val extras = JToggleButton("Extras ▾").apply {
        toolTipText = "Show additional graph analysis and display controls"
        margin = Insets(2, 8, 2, 8)
    }
    private val showAll = JButton("Show all").apply {
        isEnabled = false
        toolTipText = "Restore the complete graph"
        margin = Insets(3, 8, 3, 8)
    }
    private val affected = JButton("Affected ↓").apply { isEnabled = false }
    private val causes = JButton("Causes ↑").apply { isEnabled = false }
    private val liveTrace = JToggleButton("Live").apply {
        toolTipText = "Start/stop the localhost live-trace server on port 50737"
    }
    private val liveStatusIndicator = JLabel("● Off").apply {
        toolTipText = "Live trace transport status"
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }
    private val currentUi = JButton("Current UI").apply {
        isEnabled = false
        toolTipText = "Jump to a Flow Graph composable that is currently visible on the connected phone"
        margin = Insets(2, 8, 2, 8)
    }
    private val liveInfo = JToggleButton("ⓘ").apply {
        toolTipText = "Show/hide live trace diagnostics"
        margin = Insets(2, 5, 2, 5)
    }
    private val recentOnly = JToggleButton("Recently active").apply {
        toolTipText = "Show only nodes active in the last 2.5s, plus short connector paths between them"
    }
    private val eventLens = JToggleButton("Event lens").apply {
        isSelected = true
        toolTipText = "When zoomed out, temporarily magnify the most recently active node beside the live event"
    }
    private val magnifier = JToggleButton("🔍").apply {
        isSelected = false
        toolTipText = "Magnifier: show a zoomed view of the graph under the mouse in the bottom-right corner"
        margin = Insets(2, 5, 2, 5)
    }
    private val graphSearch = JButton("🔎 Search").apply {
        toolTipText = "Find and highlight nodes in the visible graph"
        margin = Insets(2, 6, 2, 6)
    }
    private val graphSearchField = JTextField(18).apply {
        toolTipText = "Search node names, details, ids and source files"
        isVisible = false
        preferredSize = Dimension(180, preferredSize.height)
    }
    private val graphSearchClose = JButton("×").apply {
        toolTipText = "Close search"
        margin = Insets(2, 5, 2, 5)
        isVisible = false
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
        margin = Insets(2, 5, 2, 5)
    }
    private val zoomReset = JButton("100%").apply {
        toolTipText = "Reset zoom to 100%"
        margin = Insets(2, 5, 2, 5)
    }
    private val zoomFit = JButton("Fit").apply {
        toolTipText = "Fit the complete graph into the visible viewport"
        margin = Insets(2, 5, 2, 5)
    }
    private val zoomIn = JButton("+").apply {
        toolTipText = "Zoom in"
        margin = Insets(2, 5, 2, 5)
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

    private val composePreviewCount = JLabel("Active compositions: —").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Number of live Compose compositions seen by the runtime inspector"
    }
    private val composePreviewInfo = JToggleButton("ⓘ").apply {
        toolTipText = "Show/hide Compose render diagnostics"
        margin = Insets(2, 5, 2, 5)
    }
    private val composePreviewDetails = JTextArea().apply {
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
    private val composePreviewCanvas = ComposePreviewCanvas()
    private val composePreviewRefresh = JButton("Refresh").apply {
        isEnabled = false
        toolTipText = "Inspect and render the selected Compose node again from the running app"
    }
    private val composePreviewOpen = JButton("Open large").apply {
        isEnabled = false
        toolTipText = "Open the current Compose render at a larger size"
    }

    private var fullGraph: FlowGraph? = null
    /** Full-detail graph currently in scope. The visible graph may be its collapsed state projection. */
    private var activeGraph: FlowGraph? = null
    private var selectedNodeId: String? = null
    private var selectionFocusActive: Boolean = false
    /** Independent causal radius for the focused detail map. Null means all levels. */
    private var detailUpstreamDepth: Int? = 3
    private var detailDownstreamDepth: Int? = 3
    private var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
    private var historicalRuntimeOverlay: RuntimeOverlay? = null
    private var historyCursorMillis: Long? = null
    private val recentRuntimeEmissionNanos = mutableMapOf<String, Long>()
    private val liveTraceService: LiveTraceService = project.getService(LiveTraceService::class.java)
    private val composeRenderService: ComposeRenderService = project.getService(ComposeRenderService::class.java)
    private val runtimeTimeline = RuntimeTimelinePanel(
        onScrubStarted = ::pauseRuntimeRecordingForHistory,
        onScrubbed = ::onTimelineScrubbed,
    )
    private var liveTransportStatus: LiveTraceStatus = liveTraceService.status()
    private var runtimeEventsSeen: Int = 0
    private var matchedRuntimeEvents: Int = 0
    private var unmatchedRuntimeEvents: Int = 0
    private var uiInteractionEventsSeen: Int = 0
    private val unmatchedRuntimeKeys = linkedMapOf<String, Int>()
    private var lastRecentFilterIds: Set<String> = emptySet()
    private var lastRecentFilterRefreshMillis: Long = 0L
    private var lastLiveDetailsRefreshMillis: Long = 0L
    private var lastLiveDiagnosticsRefreshMillis: Long = 0L
    private var pendingHistoricalDetailsCursorMillis: Long? = null
    private val historicalDetailsTimer = Timer(HISTORICAL_DETAILS_REFRESH_MS) {
        val cursor = pendingHistoricalDetailsCursorMillis
        pendingHistoricalDetailsCursorMillis = null
        if (cursor != null && historyCursorMillis == cursor) updateHistoricalDetails(cursor)
    }.apply { isRepeats = false }
    private var pendingCurrentComposeKeys: List<String>? = null
    private var currentComposeRequestStartedAt: Long = 0L

    // Deep Flow instrumentation can produce thousands of deliveries per second. Never enqueue
    // one Swing Runnable per event: that starves the EDT and can make Android Studio appear hung.
    private val pendingRuntimeEvents = ConcurrentLinkedQueue<RuntimeTraceEvent>()
    private val pendingRuntimeEventCount = AtomicInteger(0)
    private val droppedRuntimeUiEvents = AtomicLong(0)
    private val runtimeFlushTimer = Timer(LIVE_UI_FLUSH_MS) { flushRuntimeEvents() }.apply {
        isRepeats = true
        start()
    }
    // Recomposition is only a refresh trigger; geometry still comes from the live slot table.
    // Debounce so hot recomposition never turns UI capture into a frame-by-frame profiler.
    private val composeAutoRefreshTimer = Timer(COMPOSE_AUTO_REFRESH_MS) {
        val node = selectedNodeId?.let { fullGraph?.node(it) }
        if (node != null && isRenderableCompose(node) && composeRenderService.state(node.id) !is ComposeRenderState.Loading) {
            requestComposeRenderingFor(node, force = true)
        }
    }.apply { isRepeats = false }
    private val liveTraceListener: (RuntimeTraceEvent) -> Unit = listener@{ event ->
        // Compose images can be megabytes and are consumed directly by ComposeRenderService from
        // LiveTraceService history. Never feed them into the profiler/timeline UI queue.
        if (event.kind in setOf("compose-image", "compose-image-error", "compose-current", "compose-current-error")) return@listener
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
            updateLiveStatusIndicator(status)
            updateLiveTraceDiagnostics()
        }
    }
    private var autoFitGeneration: Long = 0L
    private var autoFitApplying: Boolean = false

    private val canvas = GraphCanvas(
        project = project,
        onNodeSelected = ::selectNode,
        onZoomChanged = { zoom ->
            zoomReset.text = "${(zoom * 100).toInt()}%"
        },
        onUserViewportInteraction = {
            if (!autoFitApplying) cancelPendingAutoFit()
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

        // Keep the main row intentionally small: project-wide loading and live runtime controls
        // are always visible. Less-frequent graph filters live in a persistent inline Extras pane.
        // A JPopupMenu used here previously dismissed itself whenever any contained toggle/combo
        // was clicked, which made the controls feel broken. This panel stays open until explicitly
        // collapsed, so several settings can be changed in one pass.
        val extrasClose = JButton("×").apply {
            toolTipText = "Close Extras"
            margin = Insets(1, 6, 1, 6)
            isFocusable = false
        }
        val extrasControls = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
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
            extras.text = if (visible) "Extras ▴" else "Extras ▾"
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

        zoomOut.addActionListener { cancelPendingAutoFit(); canvas.zoomOut() }
        zoomReset.addActionListener { cancelPendingAutoFit(); canvas.resetZoom() }
        zoomIn.addActionListener { cancelPendingAutoFit(); canvas.zoomIn() }

        showReads.addActionListener { renderActive(autoFit = selectionFocusActive) }
        includePossible.addActionListener { renderActive(autoFit = selectionFocusActive) }
        circuitView.addActionListener {
            canvas.circuitViewEnabled = circuitView.isSelected
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
        liveInfo.addActionListener {
            liveDiagnostics.isVisible = liveInfo.isSelected
            updateLiveTraceDiagnostics()
            revalidate()
            repaint()
        }
        recentOnly.addActionListener {
            lastRecentFilterIds = if (recentOnly.isSelected) recentActiveNodeIds() else emptySet()
            lastRecentFilterRefreshMillis = System.currentTimeMillis()
            canvas.recentFocusEnabled = recentOnly.isSelected
            canvas.recentFocusIds = if (recentOnly.isSelected) lastRecentFilterIds else emptySet()
            renderActive(autoFit = false)
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
            canvas.fitToViewport()
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
        val graphLegend = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3)).apply {
            isOpaque = true
            background = UIManager.getColor("Panel.background") ?: Color(45, 45, 45)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(2, 3, 2, 3),
            )
            toolTipText = "Graph view controls"
            add(showAll)
            add(zoomOut)
            add(zoomReset)
            add(zoomFit)
            add(zoomIn)
            add(magnifier)
            add(graphSearch)
            add(graphSearchField)
            add(graphSearchClose)
        }
        val graphViewportHeader = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIManager.getColor("Panel.background") ?: Color(45, 45, 45)
            border = BorderFactory.createEmptyBorder(3, 4, 3, 4)
            add(graphLegend, BorderLayout.EAST)
        }
        graphScroll.setColumnHeaderView(graphViewportHeader)
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
        }
        val lowerSide = JPanel(BorderLayout(0, 6)).apply {
            add(detailsScroll, BorderLayout.CENTER)
            add(liveDiagnostics, BorderLayout.SOUTH)
            minimumSize = Dimension(320, 150)
        }
        val rightSide = JSplitPane(JSplitPane.VERTICAL_SPLIT, composePreviewPanel, lowerSide).apply {
            resizeWeight = 0.48
            isContinuousLayout = true
            dividerSize = 7
            preferredSize = Dimension(430, 520)
            minimumSize = Dimension(320, 300)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphWithExpanders, rightSide).apply {
            resizeWeight = 0.72
            isContinuousLayout = true
            dividerSize = 7
        }

        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
        }

        val center = JPanel(BorderLayout()).apply {
            add(split, BorderLayout.CENTER)
            add(runtimeTimeline, BorderLayout.SOUTH)
        }

        runtimeTimeline.setEvents(liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS))

        add(top, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
    }

    private fun updateAllFlowsStatus(label: String, color: Color) {
        allFlowsStatusIndicator.text = "● $label"
        allFlowsStatusIndicator.foreground = color
        allFlowsStatusIndicator.toolTipText = "Project-wide Flow analysis: $label"
    }

    private fun analyzeAllFlows() {
        if (DumbService.isDumb(project)) {
            hint.text = "Wait until indexing finishes, then choose Load all flows again."
            updateAllFlowsStatus("Waiting", Color(230, 170, 55))
            return
        }

        allFlows.isEnabled = false
        allFlows.text = "Loading…"
        updateAllFlowsStatus("Loading", Color(230, 170, 55))
        title.text = "Scanning project Flow / StateFlow / SharedFlow / Compose State properties…"
        hint.text = "Project-wide analysis runs in the background and can be cancelled by normal IDE write actions."

        ReadAction.nonBlocking<FlowGraph> {
            AnalysisApiFlowAnalyzer(project).analyzeAllProjectFlows()
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { graph ->
                allFlows.text = "Load all flows"
                allFlows.isEnabled = true
                updateAllFlowsStatus("Loaded", Color(65, 185, 110))
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

        // Global APK instrumentation can report framework flows and other screens that this graph
        // cannot possibly render. Admit only events that resolve to this immutable graph plus UI
        // interaction markers. The per-key cache means even hot event streams pay the semantic
        // matcher only once per distinct runtime key. LiveTraceService also prunes old irrelevant
        // history immediately, keeping timeline scrubbing bounded.
        val admissionCache = ConcurrentHashMap<String, Boolean>()
        liveTraceService.setProfilerEventFilter { event ->
            if (event.kind == "ui-interaction" || event.stateKey == "@ui-interaction") {
                true
            } else {
                admissionCache.computeIfAbsent(event.stateKey) { key -> graph.nodeByRuntimeKey(key) != null }
            }
        }
        if (isAllProjectFlows(graph.rootLabel)) {
            updateAllFlowsStatus("Loaded", Color(65, 185, 110))
        }
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
        val relevantRuntimeKeys = linkedSetOf<String>()
        graph.nodes.forEach { node ->
            node.runtimeKey?.let(relevantRuntimeKeys::add)
            relevantRuntimeKeys.addAll(node.runtimeAliases)
        }
        runtimeTimeline.setRelevantRuntimeKeys(relevantRuntimeKeys)
        runtimeTimeline.setProfilerEventsFilteredToCurrentGraph(true)
        val projectOverviewLoaded = isAllProjectFlows(graph.rootLabel)
        renderActive(autoFit = false)
        replayRuntimeHistory()
        updateLiveTraceDiagnostics()
        details.text = if (isAllProjectFlows(graph.rootLabel)) {
            "Project-wide Flow view. Disconnected Flow groups are laid out as separate clusters. Select any node to isolate its causal cone; Show all restores the complete project graph."
        } else {
            "Select a node to isolate its causal cone. Only upstream/downstream connections will remain and the graph will re-layout compactly. Live history is remapped automatically when you switch flows."
        }
        updateComposePreview()
        pendingCurrentComposeKeys?.let { keys ->
            if (isAllProjectFlows(graph.rootLabel)) {
                pendingCurrentComposeKeys = null
                focusFirstCurrentComposeMatch(keys)
            }
        }
        historyCursorMillis?.let { onTimelineScrubbed(it) }
        if (projectOverviewLoaded) scheduleSettledAutoFit()
    }

    private fun cancelPendingAutoFit() {
        autoFitGeneration++
    }

    /**
     * Fit once immediately and again after the surrounding Swing layout has had time to settle.
     * Project-wide loads and focused detail views can change split-pane/viewport geometry over a
     * few EDT turns; a single invokeLater fit can therefore use the old extent. Manual zoom/pan
     * cancels the delayed retries so the camera never snaps back after the user takes control.
     */
    private fun scheduleSettledAutoFit() {
        val generation = ++autoFitGeneration

        fun applyIfCurrent() {
            if (generation != autoFitGeneration) return
            autoFitApplying = true
            try {
                canvas.fitToViewport()
            } finally {
                autoFitApplying = false
            }
        }

        SwingUtilities.invokeLater { applyIfCurrent() }
        listOf(90, 220).forEach { delay ->
            Timer(delay) { event ->
                (event.source as? Timer)?.stop()
                applyIfCurrent()
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun isRenderableCompose(node: FlowNode): Boolean =
        node.kind == NodeKind.COMPOSABLE && node.runtimeKey?.startsWith("@compose|") == true

    private fun selectNode(nodeId: String) {
        selectedNodeId = nodeId
        selectionFocusActive = true
        resetDetailExpansionDepths()
        showAll.isEnabled = true
        updateFieldFocusChoices(nodeId)
        hint.text = "Focused detail • < expands one cause level • > expands one affected level • existing nodes stay anchored"
        renderActive(autoFit = false)
        // Showing a focused detail view changes both graph geometry and the amount of usable
        // viewport space. Fit after Swing has settled those changes instead of fitting against the
        // previous frame's extent.
        scheduleSettledAutoFit()
        val selectedNode = fullGraph?.node(nodeId)
        updateComposePreview()
        selectedNode?.takeIf { isRenderableCompose(it) }?.let { node ->
            requestComposeRenderingFor(node, force = false)
        }
    }

    private fun requestComposeRenderingFor(node: FlowNode, force: Boolean) {
        composeRenderService.requestRender(node, force = force) { state ->
            canvas.repaint()
            if (selectedNodeId == node.id) {
                updateDetails()
                updateComposePreview()
            }
            when (state) {
                is ComposeRenderState.Loading -> hint.text = "Rendering ${node.label}: ${state.message}"
                is ComposeRenderState.Ready -> hint.text = "Rendered ${node.label} • shown in the UI render pane"
                is ComposeRenderState.Failed -> hint.text = "Could not render ${node.label}: ${state.message}"
                ComposeRenderState.Idle -> Unit
            }
        }
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

    private fun restoreFullGraph() {
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

        canvas.selectedId = selectedNodeId
        canvas.compactLayout = selectionFocusActive
        canvas.includePossibleTraversal = includePossible.isSelected
        canvas.runtimeOverlay = displayedRuntimeOverlay()
        canvas.runtimeReferenceMillis = historyCursorMillis
        canvas.recentFocusEnabled = recentOnly.isSelected
        canvas.recentFocusIds = if (recentOnly.isSelected) recentActiveNodeIds() else emptySet()
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
        val isComposeState = node.kind == NodeKind.STATE && node.detail.contains("Compose State", ignoreCase = true)

        affected.isEnabled = impact.outgoing.isNotEmpty() ||
            impact.downstreamStates.isNotEmpty() || impact.downstreamCollectors.isNotEmpty() ||
            impact.downstreamBehaviors.isNotEmpty() || impact.downstreamReads.isNotEmpty() ||
            fieldEffects.isNotEmpty() || stateChangesOut.isNotEmpty()
        causes.isEnabled = impact.incoming.isNotEmpty() ||
            impact.upstreamWriters.isNotEmpty() || impact.upstreamStates.isNotEmpty() ||
            fieldCauses.isNotEmpty() || stateChangesIn.isNotEmpty()

        details.text = buildString {
            appendLine(node.label)
            appendLine(node.kind.name.lowercase().replace('_', ' '))
            appendLine(node.detail)
            node.source?.let { appendLine("${it.file.path}:${it.line + 1}") }
            node.runtimeKey?.let { appendLine("runtime key: $it") }
            shownRuntime.nodeCounts[node.id]?.let { count ->
                appendLine(if (node.kind == NodeKind.COMPOSABLE) "runtime compose body executions: $count" else "runtime emissions: $count")
            }
            shownRuntime.changeCounts[node.id]?.let { count -> appendLine("runtime state changes: $count") }
            shownRuntime.deliveryCounts[node.id]?.let { count -> appendLine("runtime deliveries: $count") }
            shownRuntime.collectCounts[node.id]?.let { count -> appendLine("runtime collections: $count") }
            shownRuntime.emitRequestCounts[node.id]?.let { count -> appendLine("suspending emit requests: $count") }
            shownRuntime.readCounts[node.id]?.let { count -> appendLine("runtime reads: $count") }

            val composeInstances = shownRuntime.composeInstanceEventsByNode[node.id].orEmpty()
            if (isComposeState && composeInstances.isNotEmpty()) {
                appendLine("runtime instances observed: ${composeInstances.size}")
                if (composeInstances.size == 1) {
                    val lastInstanceEvent = composeInstances.values.single()
                    lastInstanceEvent.valueSummary?.let { appendLine("last value: $it") }
                    appendLine("last runtime event key: ${lastInstanceEvent.stateKey}")
                    lastInstanceEvent.site?.takeIf { it.isNotBlank() }?.let {
                        appendLine("last runtime event site: $it")
                    }
                } else {
                    appendLine("instance values:")
                    composeInstances.entries
                        .sortedBy { it.key }
                        .take(MAX_COMPOSE_INSTANCE_DETAIL_ITEMS)
                        .forEach { (instanceId, event) ->
                            appendLine("  #$instanceId = ${event.valueSummary ?: "<unknown>"}")
                        }
                    if (composeInstances.size > MAX_COMPOSE_INSTANCE_DETAIL_ITEMS) {
                        appendLine("  … +${composeInstances.size - MAX_COMPOSE_INSTANCE_DETAIL_ITEMS} more instances")
                    }
                }
            } else {
                shownRuntime.lastEventByNode[node.id]?.let { lastEvent ->
                    lastEvent.valueSummary?.let { appendLine("last value: $it") }
                    if (isComposeState) {
                        appendLine("last runtime event key: ${lastEvent.stateKey}")
                        appendLine("last runtime event kind: ${lastEvent.kind}")
                        lastEvent.site?.takeIf { it.isNotBlank() }?.let {
                            appendLine("last runtime event site: $it")
                        }
                    }
                }
            }
            if (node.kind == NodeKind.COMPOSABLE) {
                appendLine()
                appendLine("COMPOSE RENDER")
                when (val render = composeRenderService.state(node.id)) {
                    ComposeRenderState.Idle -> {
                        if (!isRenderableCompose(node)) {
                            appendLine("  This is a Compose host node without a source @Composable runtime key.")
                            appendLine("  Select a named @Composable child to inspect its live pixels.")
                        } else {
                            appendLine("  UI render inspects the running Compose slot table and LayoutInfo bounds automatically when selected.")
                            appendLine("  Pixels are drawn from the live AndroidComposeView; no generated @Preview or adb screenshot is used.")
                        }
                    }
                    is ComposeRenderState.Loading -> appendLine("  ${render.message}")
                    is ComposeRenderState.Ready -> {
                        appendLine("  ${render.snapshot.description}")
                        appendLine("  ${render.snapshot.image.width}×${render.snapshot.image.height} • shown in the UI Preview tab")
                    }
                    is ComposeRenderState.Failed -> appendLine("  ${render.message}")
                }
            }

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


    private fun updateComposePreview() {
        val node = selectedNodeId?.let { fullGraph?.node(it) }
        if (node?.kind != NodeKind.COMPOSABLE) {
            composePreviewCount.text = "Active compositions: —"
            composePreviewDetails.text = "Select a Compose node to inspect its live UI."
            composePreviewCanvas.snapshot = null
            composePreviewRefresh.isEnabled = false
            composePreviewOpen.isEnabled = false
            return
        }

        if (!isRenderableCompose(node)) {
            composePreviewCount.text = "Active compositions: —"
            composePreviewDetails.text = buildString {
                appendLine("Node: ${node.label}")
                append("This is a Compose host node without an inspectable source @Composable. Select a named @Composable child.")
            }
            composePreviewCanvas.snapshot = null
            composePreviewRefresh.isEnabled = false
            composePreviewOpen.isEnabled = false
            return
        }
        composePreviewRefresh.isEnabled = true
        when (val state = composeRenderService.state(node.id)) {
            ComposeRenderState.Idle -> {
                composePreviewCount.text = "Active compositions: —"
                composePreviewDetails.text = buildString {
                    appendLine("Node: ${node.label}")
                    append("No live render yet. Selecting this Compose node requests its current live UI automatically; use Refresh to request it again.")
                }
                composePreviewCanvas.snapshot = null
                composePreviewOpen.isEnabled = false
            }
            is ComposeRenderState.Loading -> {
                composePreviewCount.text = "Active compositions: …"
                composePreviewDetails.text = buildString {
                    appendLine("Node: ${node.label}")
                    append(state.message)
                }
                composePreviewCanvas.snapshot = null
                composePreviewOpen.isEnabled = false
            }
            is ComposeRenderState.Ready -> {
                val snapshot = state.snapshot
                composePreviewCount.text = "Active compositions: ${snapshot.activeCompositions}"
                composePreviewDetails.text = buildString {
                    appendLine("Node: ${node.label}")
                    appendLine(snapshot.description)
                    append("Image: ${snapshot.image.width}×${snapshot.image.height} • active visible instances: ${snapshot.visibleInstances} • groups scanned: ${snapshot.groupsScanned} • exact source matches: ${snapshot.exactMatches}")
                }
                composePreviewCanvas.snapshot = snapshot
                composePreviewOpen.isEnabled = true
            }
            is ComposeRenderState.Failed -> {
                composePreviewCount.text = "Active compositions: —"
                composePreviewDetails.text = buildString {
                    appendLine("Node: ${node.label}")
                    append(state.message)
                }
                composePreviewCanvas.snapshot = null
                composePreviewOpen.isEnabled = false
            }
        }
    }

    private fun showComposeRenderDialog(node: FlowNode, snapshot: ComposeRenderSnapshot) {
        val image = snapshot.image
        val maxW = 1100
        val maxH = 850
        val scale = minOf(1.0, maxW.toDouble() / image.width, maxH.toDouble() / image.height)
        val displayImage = if (scale < 1.0) {
            image.getScaledInstance((image.width * scale).toInt(), (image.height * scale).toInt(), Image.SCALE_SMOOTH)
        } else image
        val label = JLabel(ImageIcon(displayImage)).apply {
            toolTipText = snapshot.description
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        val scroll = JScrollPane(label).apply {
            preferredSize = Dimension(
                (displayImage.getWidth(null) + 24).coerceIn(360, maxW + 32),
                (displayImage.getHeight(null) + 52).coerceIn(300, maxH + 52),
            )
        }
        JOptionPane.showMessageDialog(
            this,
            scroll,
            "${node.label} • ${snapshot.description}",
            JOptionPane.PLAIN_MESSAGE,
        )
    }

    private fun jumpToCurrentPhoneComposable() {
        val status = liveTraceService.status()
        if (!status.running || status.activeClients <= 0) {
            hint.text = "Current UI requires a connected live debug app."
            return
        }

        val requestedAt = System.currentTimeMillis()
        currentComposeRequestStartedAt = requestedAt
        currentUi.isEnabled = false
        currentUi.text = "Locating…"
        hint.text = "Inspecting the Compose UI currently visible on the phone…"

        if (liveTraceService.requestCurrentCompose() <= 0) {
            currentUi.text = "Current UI"
            currentUi.isEnabled = true
            hint.text = "The connected app did not accept the Current UI request."
            return
        }

        val timer = Timer(100, null)
        timer.addActionListener {
            if (currentComposeRequestStartedAt != requestedAt) {
                timer.stop()
                return@addActionListener
            }
            val response = liveTraceService.latestCurrentComposeResponse()
                ?.takeIf { it.receivedAtMillis >= requestedAt }
            if (response != null) {
                timer.stop()
                currentUi.text = "Current UI"
                currentUi.isEnabled = liveTraceService.status().activeClients > 0
                if (response.kind == "compose-current-error") {
                    hint.text = response.valueSummary ?: "Could not identify a currently visible composable."
                    return@addActionListener
                }
                val keys = response.valueSummary.orEmpty()
                    .lineSequence()
                    .map { it.substringBefore('	').trim() }
                    .filter { it.startsWith("@compose|") }
                    .distinct()
                    .toList()
                if (keys.isEmpty()) {
                    hint.text = "No inspectable source composable is currently visible on the phone."
                    return@addActionListener
                }
                if (!focusFirstCurrentComposeMatch(keys)) {
                    val loaded = fullGraph
                    if (loaded == null || !isAllProjectFlows(loaded.rootLabel)) {
                        pendingCurrentComposeKeys = keys
                        hint.text = "Current UI found. Loading all flows so Flow Graph can locate its composable node…"
                        analyzeAllFlows()
                    } else {
                        hint.text = "The current phone UI is visible to Compose, but none of its composables are represented in the loaded Flow graph."
                    }
                }
                return@addActionListener
            }
            if (System.currentTimeMillis() - requestedAt >= 5_000L) {
                timer.stop()
                currentUi.text = "Current UI"
                currentUi.isEnabled = liveTraceService.status().activeClients > 0
                hint.text = "Timed out while locating the current phone composable."
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    private fun focusFirstCurrentComposeMatch(runtimeKeys: List<String>): Boolean {
        val graph = fullGraph ?: return false
        val node = runtimeKeys.asSequence()
            .mapNotNull { key -> graph.nodeByRuntimeKey(key) }
            .firstOrNull { it.kind == NodeKind.COMPOSABLE }
            ?: return false

        activeGraph = graph
        selectionFocusActive = false
        selectNode(node.id)
        SwingUtilities.invokeLater {
            canvas.centerNode(node.id)
            hint.text = "Current phone UI → ${node.label}"
        }
        return true
    }

    private fun toggleLiveTrace() {
        if (liveTrace.isSelected) {
            liveTraceService.start().onSuccess { port ->
                hint.text = if (liveTraceService.isRecordingPaused()) {
                    "Live trace transport listening, but profiler recording is PAUSED • press LIVE on the timeline to resume"
                } else {
                    "Live trace listening on :$port • waiting for an instrumented debug client"
                }
                liveTransportStatus = liveTraceService.status()
                updateLiveStatusIndicator(liveTransportStatus)
                updateLiveTraceDiagnostics()
            }.onFailure { error ->
                liveTrace.isSelected = false
                liveTransportStatus = liveTraceService.status()
                updateLiveStatusIndicator(liveTransportStatus)
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
            liveTransportStatus = liveTraceService.status()
            updateLiveStatusIndicator(liveTransportStatus)
            updateLiveTraceDiagnostics()
            hint.text = "Live trace off"
        }
    }

    private fun updateLiveStatusIndicator(status: LiveTraceStatus = liveTransportStatus) {
        currentUi.isEnabled = status.running && status.activeClients > 0
        when {
            !status.running -> {
                liveStatusIndicator.text = "● Off"
                liveStatusIndicator.foreground = UIManager.getColor("Label.disabledForeground") ?: Color(130, 135, 140)
                liveStatusIndicator.toolTipText = "Live trace server is off"
            }
            status.activeClients <= 0 -> {
                liveStatusIndicator.text = "● Waiting for client"
                liveStatusIndicator.foreground = Color(222, 166, 55)
                liveStatusIndicator.toolTipText = "Server is listening on :${status.port ?: LiveTraceService.DEFAULT_PORT}; no instrumented client is connected"
            }
            else -> {
                liveStatusIndicator.text = "● On"
                liveStatusIndicator.foreground = Color(76, 190, 112)
                liveStatusIndicator.toolTipText = "${status.activeClients} live client${if (status.activeClients == 1) "" else "s"} connected"
            }
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
        uiInteractionEventsSeen = 0
        unmatchedRuntimeKeys.clear()
    }

    private fun updateLiveTraceDiagnostics() {
        val status = liveTransportStatus
        liveDiagnostics.isVisible = liveInfo.isSelected

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
        val eventLine = "Source events: $runtimeEventsSeen • transport samples: ${status.validEvents} • matched: $matchedRuntimeEvents • unmatched: $unmatchedRuntimeEvents • UI: $uiInteractionEventsSeen" +
            " • skipped outside graph=${status.filteredProfilerEvents} • UI queue=${pendingRuntimeEventCount.get()} • dropped=${droppedRuntimeUiEvents.get()}"
        val pauseLine = when {
            !status.running -> "Profiler recording: OFF"
            status.recordingPaused -> "Profiler PAUSED • retained history frozen • incoming ignored while paused=${status.ignoredWhilePaused} • press LIVE to resume"
            else -> "Profiler recording: LIVE"
        }
        val deepRuntimeLine = "Current graph: emit=${runtimeOverlay.nodeCounts.values.sum()} • " +
            "change=${runtimeOverlay.changeCounts.values.sum()} • deliver=${runtimeOverlay.deliveryCounts.values.sum()} • " +
            "collect=${runtimeOverlay.collectCounts.values.sum()} • emit requests=${runtimeOverlay.emitRequestCounts.values.sum()} • " +
            "read=${runtimeOverlay.readCounts.values.sum()}"
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
            append("Transport: valid=${status.validEvents} • filtered=${status.filteredProfilerEvents} • malformed=${status.malformedLines}")
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
        val deadlineNanos = System.nanoTime() + RUNTIME_FLUSH_BUDGET_NANOS
        while (processed < MAX_RUNTIME_EVENTS_PER_FLUSH && (processed == 0 || System.nanoTime() < deadlineNanos)) {
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

        // Keep the visual overlay responsive without rebuilding large Swing text panes at the same
        // cadence. Details/diagnostics are informational; repainting them 15-20 times per second is
        // pure EDT churn and is very noticeable while panning, zooming or scrubbing.
        val now = System.currentTimeMillis()
        if (now - lastLiveDiagnosticsRefreshMillis >= LIVE_DIAGNOSTICS_REFRESH_MS) {
            lastLiveDiagnosticsRefreshMillis = now
            updateLiveTraceDiagnostics()
        }
        if (selectedNodeId != null && historyCursorMillis == null && now - lastLiveDetailsRefreshMillis >= LIVE_DETAILS_REFRESH_MS) {
            lastLiveDetailsRefreshMillis = now
            updateDetails()
            updateComposePreview()
        }
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
        canvas.recentFocusEnabled = true
        canvas.recentFocusIds = current
        // Recently active is a focus layer: the graph stays intact and only visual emphasis changes.
        canvas.repaint()
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
        runtimeEventsSeen += event.occurrences
        if (event.kind == "ui-interaction" || event.stateKey == "@ui-interaction") {
            uiInteractionEventsSeen += event.occurrences
            // UI input is a global profiler marker, not a Flow/State node. Keep it in the timeline
            // but never count it as an unmatched runtime key or mutate the graph overlay.
            clearRuntime.isEnabled = true
            if (refreshUi) updateLiveTraceDiagnostics()
            return
        }
        val graph = fullGraph
        if (graph == null) {
            unmatchedRuntimeEvents += event.occurrences
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
                unmatchedRuntimeEvents += event.occurrences
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

        matchedRuntimeEvents += event.occurrences
        unmatchedRuntimeKeys.remove(event.stateKey)
        if (refreshUi) updateLiveTraceDiagnostics()

        val nodeCounts = runtimeOverlay.nodeCounts.toMutableMap()
        val changeCounts = runtimeOverlay.changeCounts.toMutableMap()
        val readCounts = runtimeOverlay.readCounts.toMutableMap()
        val deliveryCounts = runtimeOverlay.deliveryCounts.toMutableMap()
        val collectCounts = runtimeOverlay.collectCounts.toMutableMap()
        val emitRequestCounts = runtimeOverlay.emitRequestCounts.toMutableMap()
        val lastEvents = runtimeOverlay.lastEventByNode.toMutableMap()
        val composeInstanceEvents = runtimeOverlay.composeInstanceEventsByNode.toMutableMap()
        val lastActivityAtMillis = runtimeOverlay.lastActivityAtMillis.toMutableMap()
        val edgeCounts = runtimeOverlay.edgeCounts.toMutableMap()
        val isRead = event.kind == "read"
        val isDelivery = event.kind == "deliver"
        val isCollect = event.kind == "collect-start"
        val isEmitRequest = event.kind == "emit-request"
        val isCompose = event.kind == "compose"
        val isInitial = event.kind == "initial"
        val isComposeState = node.kind == NodeKind.STATE && node.detail.contains("Compose State", ignoreCase = true)
        val isStateChange = event.kind == "state-change" || (isComposeState && event.kind == "emit")
        val isValueEvent = event.kind == "emit" || event.kind == "state-change" || event.kind == "deliver" || isInitial

        // Keep the last value for each concrete Compose State object. A source declaration can back
        // many live Lazy/keyed instances, so replacing one node-level `last value` on every event is
        // semantically wrong. Initial snapshots populate this map but never count as mutations.
        if (isComposeState && event.instanceId != null && isValueEvent && event.valueSummary != null) {
            val perInstance = composeInstanceEvents[node.id].orEmpty().toMutableMap()
            perInstance[event.instanceId] = event
            if (perInstance.size > MAX_COMPOSE_INSTANCES_PER_NODE) {
                perInstance.entries
                    .sortedBy { it.value.receivedAtMillis }
                    .take(perInstance.size - MAX_COMPOSE_INSTANCES_PER_NODE)
                    .forEach { perInstance.remove(it.key) }
            }
            composeInstanceEvents[node.id] = perInstance
        }

        // Reads and initial snapshots are diagnostics, not mutations. They must not make a state
        // node look active/changed or keep it in Recently active.
        val visuallyActiveEvent = markActivity && !isCollect && !isRead && !isInitial
        if (visuallyActiveEvent) {
            lastActivityAtMillis[node.id] = System.currentTimeMillis()
            if (!runtimeActivityCooldownTimer.isRunning) runtimeActivityCooldownTimer.start()
        }

        when {
            isRead -> {
                readCounts[node.id] = (readCounts[node.id] ?: 0) + event.occurrences
            }
            isInitial -> {
                // Initial registration is a value snapshot, never a mutation/emission count.
                lastEvents[node.id] = event
            }
            isStateChange -> {
                changeCounts[node.id] = (changeCounts[node.id] ?: 0) + event.occurrences
                lastEvents[node.id] = event
            }
            isDelivery -> {
                deliveryCounts[node.id] = (deliveryCounts[node.id] ?: 0) + event.occurrences
                lastEvents[node.id] = event
            }
            isCollect -> collectCounts[node.id] = (collectCounts[node.id] ?: 0) + event.occurrences
            isEmitRequest -> {
                emitRequestCounts[node.id] = (emitRequestCounts[node.id] ?: 0) + event.occurrences
                lastEvents[node.id] = event
            }
            else -> {
                nodeCounts[node.id] = (nodeCounts[node.id] ?: 0) + event.occurrences
                lastEvents[node.id] = event
            }
        }

        if (isValueEvent && event.kind != "initial" && node.kind == NodeKind.STATE) {
            // Runtime causality remains conservative: an edge is marked observed only when static
            // analysis already knows source -> target and the source emitted shortly before target.
            graph.stateTransitionsTo(node.id).forEach { transition ->
                    val sourceTime = recentRuntimeEmissionNanos[transition.sourceStateId] ?: return@forEach
                    val delta = event.timestampNanos - sourceTime
                    if (delta in 0..LIVE_EDGE_WINDOW_NANOS) {
                        val key = transition.sourceStateId to node.id
                        edgeCounts[key] = (edgeCounts[key] ?: 0) + event.occurrences
                    }
                }

            recentRuntimeEmissionNanos[node.id] = event.timestampNanos
        }

        if (isCompose && node.kind == NodeKind.COMPOSABLE) {
            // Compose runtime events happen after the Flow-backed State has invalidated the UI.
            // Light the static path into the composable only when one of its upstream nodes was
            // active inside the same short runtime window. This keeps runtime causality grounded
            // in the static graph instead of inventing arbitrary recomposition causes.
            graph.incomingEdges(node.id)
                .filter { it.kind in setOf(EdgeKind.UPDATES_COMPOSE, EdgeKind.COMPOSES) }
                .forEach { incoming ->
                    val directTime = lastActivityAtMillis[incoming.from]
                    val bridgeIncoming = graph.incomingEdges(incoming.from)
                    val upstream = bridgeIncoming.firstOrNull { edge ->
                        val t = lastActivityAtMillis[edge.from] ?: return@firstOrNull false
                        System.currentTimeMillis() - t <= HISTORY_EDGE_WINDOW_MS
                    }
                    val observed = directTime?.let { System.currentTimeMillis() - it <= HISTORY_EDGE_WINDOW_MS } == true || upstream != null
                    if (observed) {
                        edgeCounts[incoming.from to incoming.to] = (edgeCounts[incoming.from to incoming.to] ?: 0) + event.occurrences
                        if (upstream != null) {
                            edgeCounts[upstream.from to upstream.to] = (edgeCounts[upstream.from to upstream.to] ?: 0) + event.occurrences
                        }
                    }
                }
        }

        if (isCompose && node.id == selectedNodeId && isRenderableCompose(node)) {
            composeAutoRefreshTimer.restart()
        }

        runtimeOverlay = RuntimeOverlay(
            nodeCounts = nodeCounts,
            changeCounts = changeCounts,
            readCounts = readCounts,
            deliveryCounts = deliveryCounts,
            collectCounts = collectCounts,
            emitRequestCounts = emitRequestCounts,
            edgeCounts = edgeCounts,
            lastEventByNode = lastEvents,
            composeInstanceEventsByNode = composeInstanceEvents,
            lastActivityAtMillis = lastActivityAtMillis,
            lastNodeId = if (isRead || isCollect || isInitial) runtimeOverlay.lastNodeId else node.id,
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
                    isInitial -> appendLine("${node.label} • instance snapshot")
                    isDelivery -> appendLine("${node.label} • collector delivery #${deliveryCounts[node.id]}")
                    isCollect -> appendLine("${node.label} • collection #${collectCounts[node.id]}")
                    isEmitRequest -> appendLine("${node.label} • suspended emit request #${emitRequestCounts[node.id]}")
                    isCompose -> appendLine("${node.label} • body execution #${nodeCounts[node.id]}")
                    isStateChange -> appendLine("${node.label} • state change #${changeCounts[node.id]}")
                    else -> appendLine("${node.label} • emission #${nodeCounts[node.id]}")
                }
                appendLine("runtime key: ${event.stateKey}")
                event.instanceId?.let { appendLine("runtime instance: #$it") }
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
            pendingHistoricalDetailsCursorMillis = null
            historicalDetailsTimer.stop()
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
            // The graph overlay follows the scrubber at ~60 Hz. The text pane is intentionally
            // sampled more slowly because rebuilding/laying out a long JTextArea on every pointer
            // tick made the whole IDE feel sticky.
            pendingHistoricalDetailsCursorMillis = cursorMillis
            if (!historicalDetailsTimer.isRunning) historicalDetailsTimer.start()
        }
    }

    private fun buildHistoricalOverlay(
        graph: FlowGraph,
        events: List<RuntimeTraceEvent>,
    ): RuntimeOverlay {
        if (events.isEmpty()) return RuntimeOverlay.EMPTY

        val nodeCounts = linkedMapOf<String, Int>()
        val changeCounts = linkedMapOf<String, Int>()
        val readCounts = linkedMapOf<String, Int>()
        val deliveryCounts = linkedMapOf<String, Int>()
        val collectCounts = linkedMapOf<String, Int>()
        val emitRequestCounts = linkedMapOf<String, Int>()
        val edgeCounts = linkedMapOf<Pair<String, String>, Int>()
        val lastEvents = linkedMapOf<String, RuntimeTraceEvent>()
        val composeInstanceEvents = linkedMapOf<String, MutableMap<Int, RuntimeTraceEvent>>()
        val lastActivity = linkedMapOf<String, Long>()
        val recentStateActivity = linkedMapOf<String, Long>()
        var lastNodeId: String? = null

        // RuntimeTimelinePanel already keeps receipt order. Avoid sorting every ±450 ms slice
        // on every scrub tick.
        events.forEach { event ->
            val node = graph.nodeByRuntimeKey(event.stateKey) ?: return@forEach
            val isRead = event.kind == "read"
            val isDelivery = event.kind == "deliver"
            val isCollect = event.kind == "collect-start"
            val isEmitRequest = event.kind == "emit-request"
            val isCompose = event.kind == "compose"
            val isInitial = event.kind == "initial"
            val isComposeState = node.kind == NodeKind.STATE && node.detail.contains("Compose State", ignoreCase = true)
            val isStateChange = event.kind == "state-change" || (isComposeState && event.kind == "emit")
            val isValueEvent = event.kind == "emit" || event.kind == "state-change" || event.kind == "deliver" || isInitial

            if (isComposeState && event.instanceId != null && isValueEvent && event.valueSummary != null) {
                val perInstance = composeInstanceEvents.getOrPut(node.id) { linkedMapOf() }
                perInstance[event.instanceId] = event
                if (perInstance.size > MAX_COMPOSE_INSTANCES_PER_NODE) {
                    perInstance.entries
                        .sortedBy { it.value.receivedAtMillis }
                        .take(perInstance.size - MAX_COMPOSE_INSTANCES_PER_NODE)
                        .forEach { perInstance.remove(it.key) }
                }
            }

            when {
                isRead -> {
                    readCounts[node.id] = (readCounts[node.id] ?: 0) + event.occurrences
                }
                isInitial -> {
                    lastEvents[node.id] = event
                }
                isStateChange -> {
                    changeCounts[node.id] = (changeCounts[node.id] ?: 0) + event.occurrences
                    lastEvents[node.id] = event
                }
                isDelivery -> {
                    deliveryCounts[node.id] = (deliveryCounts[node.id] ?: 0) + event.occurrences
                    lastEvents[node.id] = event
                }
                isCollect -> collectCounts[node.id] = (collectCounts[node.id] ?: 0) + event.occurrences
                isEmitRequest -> {
                    emitRequestCounts[node.id] = (emitRequestCounts[node.id] ?: 0) + event.occurrences
                    lastEvents[node.id] = event
                }
                else -> {
                    nodeCounts[node.id] = (nodeCounts[node.id] ?: 0) + event.occurrences
                    lastEvents[node.id] = event
                }
            }

            if (!isCollect && !isRead && !isInitial) {
                lastActivity[node.id] = event.receivedAtMillis
                lastNodeId = node.id
            }

            if (isValueEvent && event.kind != "initial" && node.kind == NodeKind.STATE) {
                graph.stateTransitionsTo(node.id)
                    .forEach transitionLoop@ { transition ->
                        val sourceTime = recentStateActivity[transition.sourceStateId] ?: return@transitionLoop
                        val delta = event.receivedAtMillis - sourceTime
                        if (delta in 0..HISTORY_EDGE_WINDOW_MS) {
                            val key = transition.sourceStateId to node.id
                            edgeCounts[key] = (edgeCounts[key] ?: 0) + event.occurrences
                        }
                    }
                recentStateActivity[node.id] = event.receivedAtMillis
            }

            if (isCompose && node.kind == NodeKind.COMPOSABLE) {
                graph.incomingEdges(node.id)
                    .filter { it.kind in setOf(EdgeKind.UPDATES_COMPOSE, EdgeKind.COMPOSES) }
                    .forEach { incoming ->
                        val directTime = lastActivity[incoming.from]
                        val bridgeIncoming = graph.incomingEdges(incoming.from)
                        val upstream = bridgeIncoming.firstOrNull { edge ->
                            val t = lastActivity[edge.from] ?: return@firstOrNull false
                            event.receivedAtMillis - t in 0..HISTORY_EDGE_WINDOW_MS
                        }
                        val observed = directTime?.let { event.receivedAtMillis - it in 0..HISTORY_EDGE_WINDOW_MS } == true || upstream != null
                        if (observed) {
                            edgeCounts[incoming.from to incoming.to] = (edgeCounts[incoming.from to incoming.to] ?: 0) + event.occurrences
                            if (upstream != null) {
                                edgeCounts[upstream.from to upstream.to] = (edgeCounts[upstream.from to upstream.to] ?: 0) + event.occurrences
                            }
                        }
                    }
            }
        }

        return RuntimeOverlay(
            nodeCounts = nodeCounts,
            changeCounts = changeCounts,
            readCounts = readCounts,
            deliveryCounts = deliveryCounts,
            collectCounts = collectCounts,
            emitRequestCounts = emitRequestCounts,
            edgeCounts = edgeCounts,
            lastEventByNode = lastEvents,
            composeInstanceEventsByNode = composeInstanceEvents,
            lastActivityAtMillis = lastActivity,
            lastNodeId = lastNodeId,
        )
    }

    private fun updateHistoricalDetails(cursorMillis: Long) {
        val graph = fullGraph
        val events = runtimeTimeline.eventsNear(cursorMillis)
        val uiInteractions = events.filter { it.kind == "ui-interaction" || it.stateKey == "@ui-interaction" }
        val graphEvents = events.filterNot { it.kind == "ui-interaction" || it.stateKey == "@ui-interaction" }
        val matched = if (graph == null) emptyList() else graphEvents.mapNotNull { event ->
            graph.nodeByRuntimeKey(event.stateKey)?.let { it to event }
        }
        val outsideCount = graphEvents.size - matched.size

        details.text = buildString {
            appendLine("RUNTIME HISTORY")
            appendLine("${HISTORY_TIME_FORMAT.format(Date(cursorMillis))}  ±${RuntimeTimelinePanel.HISTORY_RADIUS_MS}ms")
            appendLine("events: ${events.size} • UI interactions: ${uiInteractions.size} • in current graph: ${matched.size} • outside graph: $outsideCount")
            appendLine("Recording is PAUSED. Retained history and map coordinates are frozen; incoming device events are ignored until LIVE is pressed.")

            if (uiInteractions.isNotEmpty()) {
                appendLine()
                appendLine("UI INTERACTIONS")
                uiInteractions.take(MAX_HISTORY_DETAIL_EVENTS).forEach { interaction ->
                    val delta = interaction.receivedAtMillis - cursorMillis
                    val sign = if (delta >= 0) "+" else ""
                    append("  $sign${delta}ms  ")
                    append(interaction.valueSummary ?: "interaction")
                    interaction.site?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                    appendLine()
                }
            }

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
                    event.instanceId?.let { append(" #$it") }
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
                    (overlay.changeCounts[selected] ?: 0) +
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

private class ComposePreviewCanvas : JPanel() {
    var snapshot: ComposeRenderSnapshot? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(400, 520)
        minimumSize = Dimension(280, 220)
        background = Color(28, 28, 32)
        border = BorderFactory.createLineBorder(Color(75, 75, 82), 1, true)
        toolTipText = "Selected Compose render"
    }

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val snap = snapshot
            if (snap == null) {
                g.color = Color(170, 170, 178)
                g.font = font.deriveFont(Font.PLAIN, 13f)
                val text = "No UI render available"
                val fm = g.fontMetrics
                g.drawString(text, ((width - fm.stringWidth(text)) / 2).coerceAtLeast(12), height / 2)
                return
            }

            val image = snap.image
            val pad = 18
            val availableW = (width - pad * 2).coerceAtLeast(1)
            val availableH = (height - pad * 2).coerceAtLeast(1)
            val scale = minOf(
                availableW.toDouble() / image.width.toDouble(),
                availableH.toDouble() / image.height.toDouble(),
            )
            val drawW = (image.width * scale).toInt().coerceAtLeast(1)
            val drawH = (image.height * scale).toInt().coerceAtLeast(1)
            val x = (width - drawW) / 2
            val y = (height - drawH) / 2

            g.color = Color(12, 12, 15)
            g.fillRoundRect(x - 5, y - 5, drawW + 10, drawH + 10, 12, 12)
            g.drawImage(image, x, y, drawW, drawH, null)
            g.color = Color(105, 105, 115)
            g.drawRoundRect(x - 5, y - 5, drawW + 10, drawH + 10, 12, 12)
        } finally {
            g.dispose()
        }
    }
}

private class GraphCanvas(
    private val project: Project,
    private val onNodeSelected: (String) -> Unit,
    private val onZoomChanged: (Double) -> Unit,
    private val onUserViewportInteraction: () -> Unit,
) : JPanel() {
    private val composeRenderService: ComposeRenderService = project.getService(ComposeRenderService::class.java)
    var graph: FlowGraph? = null
        set(value) {
            field = value
            cachedLayout = null
            circuitRoutingCache = null
        }
    var selectedId: String? = null
        set(value) {
            field = value
            cachedLayout = null
            repaint()
        }
    private var focusedEdge: FlowEdge? = null

    var compactLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                cachedLayout = null
                circuitRoutingCache = null
            }
        }
    var includePossibleTraversal: Boolean = false
        set(value) { field = value; repaint() }
    var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.EMPTY
        set(value) { field = value; repaint() }
    /** Null = live wall clock. Non-null = historical scrub reference time. */
    var runtimeReferenceMillis: Long? = null
        set(value) { field = value; repaint() }
    var eventLensEnabled: Boolean = true
        set(value) { field = value; repaint() }
    var magnifierEnabled: Boolean = false
        set(value) { field = value; repaint() }
    var circuitViewEnabled: Boolean = false
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

    private val boundsById = mutableMapOf<String, Rectangle>()
    private val nodeW = 250
    private val nodeH = 76
    private val colGap = 170
    private val rowGap = 34
    private var cachedLayout: NodeLayout? = null
    private var circuitRoutingCache: CircuitRoutingCache? = null
    /**
     * Snapshot of the map before a temporary visibility-only filter is applied.
     * Visible nodes reuse these exact coordinates so the user's spatial memory remains valid.
     */
    private var frozenLayout: NodeLayout? = null

    private var zoom = 1.0
    private var unscaledPreferredSize = Dimension(1, 1)
    // Symmetric device-pixel breathing room around the graph. Without this, when the scaled graph
    // is smaller than the viewport JViewport clamps viewPosition to (0, 0), making it impossible
    // to keep the logical point under the mouse fixed during low-level zoom. Keeping one viewport
    // worth of virtual canvas on every side gives cursor-centered zoom the same camera freedom at
    // 20% that it has at 150%+.
    private var canvasPaddingX = 0
    private var canvasPaddingY = 0
    private var panStartOnScreen: Point? = null
    private var panStartViewPosition: Point? = null
    private var panDragged = false
    private var suppressNextClick = false
    private var magnifierMousePoint: Point? = null

    private data class RuntimeLensTarget(
        val node: FlowNode,
        val bounds: Rectangle,
        val ageMs: Long,
    )

    private data class EdgePathFocus(
        val seed: FlowEdge,
        val edges: Set<FlowEdge>,
        val nodes: Set<String>,
    )

    private data class CircuitRoutingCache(
        val routes: Map<FlowEdge, List<Point>>,
        val junctions: Set<Point>,
    )

    private enum class EdgePathStyle {
        NONE,
        DIMMED,
        PATH,
        SEED,
    }

    init {
        background = UIManager.getColor("Panel.background")
        toolTipText = "Wheel to zoom; drag empty canvas (or middle-drag) to pan; click a node to isolate its causal connections; click an edge to highlight its path; double-click an edge to navigate"

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val shouldPan = SwingUtilities.isMiddleMouseButton(e) ||
                    (SwingUtilities.isLeftMouseButton(e) && !isInteractiveAt(e.point))
                if (!shouldPan) return

                onUserViewportInteraction()
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

                val nodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
                if (nodeId != null) {
                    focusedEdge = null
                    val node = model.node(nodeId) ?: return
                    val nodeBounds = boundsById[nodeId]
                    if (node.kind == NodeKind.COMPOSABLE && nodeBounds != null && composeSurfaceRect(nodeBounds).contains(point)) {
                        onNodeSelected(nodeId)
                        repaint()
                        return
                    }
                    if (e.clickCount >= 2) {
                        node.source?.let { src -> OpenFileDescriptor(project, src.file, src.offset).navigate(true) }
                        return
                    }
                    if (node.kind !in setOf(NodeKind.CYCLE, NodeKind.CLUSTER)) {
                        onNodeSelected(nodeId)
                    }
                    repaint()
                    return
                }

                val edge = findEdgeAt(model, point)
                if (edge == null) {
                    if (focusedEdge != null) {
                        focusedEdge = null
                        repaint()
                    }
                    return
                }
                focusedEdge = edge
                repaint()
                if (e.clickCount >= 2) {
                    navigationSource(model, edge)?.let { src ->
                        OpenFileDescriptor(project, src.file, src.offset).navigate(true)
                    }
                }
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
                // Mouse wheels commonly report integral rotations, so the toolbar's deliberately
                // coarse 18% step feels jumpy here. Use a smaller exponential wheel response while
                // preserving fractional trackpad deltas. Every event anchors to *this event's* mouse
                // position, so moving the pointer during a wheel gesture immediately changes the
                // focal point instead of continuing to zoom around a stale position.
                onUserViewportInteraction()
                val factor = Math.exp(-e.preciseWheelRotation * WHEEL_ZOOM_SENSITIVITY)
                setZoom(zoom * factor, Point(e.point))
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
        val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
        val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
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
        val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
        val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
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

    fun centerNode(nodeId: String) {
        val model = graph ?: return
        val viewport = viewport() ?: return
        val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        val rect = layout.bounds[nodeId] ?: return
        val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
        val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
        viewport.viewPosition = clampViewPosition(
            viewport,
            centerX - viewport.extentSize.width / 2,
            centerY - viewport.extentSize.height / 2,
        )
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
        ).coerceIn(currentMinZoom(), currentMaxZoom())

        setZoom(fitted, null)
        // Fit the actual graph, not the virtual camera padding. Keep the graph centered while the
        // padding remains available for the next cursor-centered wheel gesture.
        val contentW = unscaledPreferredSize.width * zoom
        val contentH = unscaledPreferredSize.height * zoom
        viewport.viewPosition = clampViewPosition(
            viewport,
            kotlin.math.round(canvasPaddingX + contentW / 2.0 - viewport.extentSize.width / 2.0).toInt(),
            kotlin.math.round(canvasPaddingY + contentH / 2.0 - viewport.extentSize.height / 2.0).toInt(),
        )
    }

    private fun setZoom(requestedZoom: Double, anchorInCanvas: Point?) {
        val newZoom = requestedZoom.coerceIn(currentMinZoom(), currentMaxZoom())
        if (kotlin.math.abs(newZoom - zoom) < 0.0001) return

        val viewport = viewport()
        val oldZoom = zoom
        val anchor = anchorInCanvas ?: viewportCenterInCanvas() ?: Point(0, 0)

        // Capture the exact logical graph point under the cursor *before* changing scale, plus the
        // cursor's pixel position inside the JViewport. Reconstructing the viewport position from
        // those two values after every zoom step prevents the familiar "zoom drifts away from the
        // mouse" effect, especially after several wheel ticks and non-integer zoom factors.
        val modelX = (anchor.x.toDouble() - canvasPaddingX) / oldZoom
        val modelY = (anchor.y.toDouble() - canvasPaddingY) / oldZoom
        val anchorInViewport = viewport?.let {
            SwingUtilities.convertPoint(this, anchor, it)
        }

        zoom = newZoom
        updateScaledPreferredSize()
        revalidate()
        onZoomChanged(zoom)

        fun restoreMouseAnchor() {
            if (viewport == null || anchorInViewport == null) return
            val targetX = kotlin.math.round(canvasPaddingX + modelX * zoom - anchorInViewport.x).toInt()
            val targetY = kotlin.math.round(canvasPaddingY + modelY * zoom - anchorInViewport.y).toInt()
            viewport.viewPosition = clampViewPosition(viewport, targetX, targetY)
        }

        // Apply once immediately for responsive wheel zoom, then once after Swing has laid out the
        // newly-sized view. JViewport can otherwise clamp against the previous view size and cause
        // a small but cumulative cursor-anchor error.
        restoreMouseAnchor()
        SwingUtilities.invokeLater {
            viewport?.revalidate()
            restoreMouseAnchor()
            repaint()
        }
        repaint()
    }

    private fun currentMinZoom(): Double =
        if (isAllProjectFlows(graph?.rootLabel) && selectedId == null) PROJECT_OVERVIEW_MIN_ZOOM else MIN_ZOOM

    /**
     * Keep enough spatial context at maximum zoom to see roughly two neighboring nodes at once.
     * This prevents the + button / mouse wheel from turning the graph into a single-node close-up.
     */
    private fun currentMaxZoom(): Double {
        val viewportWidth = viewport()?.extentSize?.width?.takeIf { it > 0 } ?: return MAX_ZOOM
        val overview = isAllProjectFlows(graph?.rootLabel) && selectedId == null
        val contextWidth = if (overview) {
            PROJECT_OVERVIEW_NODE_W * 2 + PROJECT_OVERVIEW_HORIZONTAL_GAP + 28
        } else {
            nodeW * 2 + (if (compactLayout) 86 else colGap) + 28
        }
        return (viewportWidth.toDouble() / contextWidth.toDouble())
            .coerceIn(currentMinZoom(), MAX_ZOOM)
    }

    private fun updateScaledPreferredSize() {
        val viewport = viewport()
        val desiredPaddingX = (viewport?.extentSize?.width ?: 0).coerceAtLeast(MIN_VIRTUAL_CANVAS_PADDING)
        val desiredPaddingY = (viewport?.extentSize?.height ?: 0).coerceAtLeast(MIN_VIRTUAL_CANVAS_PADDING)
        val deltaPaddingX = desiredPaddingX - canvasPaddingX
        val deltaPaddingY = desiredPaddingY - canvasPaddingY
        val oldViewPosition = viewport?.viewPosition?.let(::Point)

        canvasPaddingX = desiredPaddingX
        canvasPaddingY = desiredPaddingY
        preferredSize = Dimension(
            (unscaledPreferredSize.width * zoom).toInt().coerceAtLeast(1) + canvasPaddingX * 2,
            (unscaledPreferredSize.height * zoom).toInt().coerceAtLeast(1) + canvasPaddingY * 2,
        )

        // Padding is camera space, not graph space. If a resize changes its size, move the viewport
        // by exactly the same delta so the graph does not visually jump. The first layout uses the
        // same rule, placing the old (0,0) graph view at the new padded content origin.
        if (viewport != null && oldViewPosition != null && (deltaPaddingX != 0 || deltaPaddingY != 0)) {
            val target = clampViewPosition(
                viewport,
                oldViewPosition.x + deltaPaddingX,
                oldViewPosition.y + deltaPaddingY,
            )
            viewport.viewPosition = target
            SwingUtilities.invokeLater {
                viewport.viewPosition = clampViewPosition(viewport, target.x, target.y)
            }
        }
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
        ((point.x - canvasPaddingX) / zoom).toInt(),
        ((point.y - canvasPaddingY) / zoom).toInt(),
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
        val viewModels = nodes.asSequence()
            .filter { it.groupKind == NodeGroupKind.VIEW_MODEL }
            .mapNotNull { it.groupLabel }
            .distinct()
            .sorted()
            .toList()
        val composeSurfaces = nodes.asSequence()
            .filter { it.kind == NodeKind.COMPOSABLE }
            .map { it.label }
            .distinct()
            .sorted()
            .toList()
        val label = buildString {
            when {
                viewModels.size == 1 -> append("ViewModel • ").append(viewModels.first())
                viewModels.isNotEmpty() -> append("ViewModels • ").append(viewModels.take(2).joinToString(" + "))
                composeSurfaces.isNotEmpty() -> append("Compose • ").append(composeSurfaces.first())
                else -> append("Cluster ").append(index)
            }
            append(" • ").append(states.size).append(" flows")
            if (composeSurfaces.isNotEmpty()) {
                append(" • UI: ").append(composeSurfaces.take(2).joinToString(", "))
                if (composeSurfaces.size > 2) append(", …")
            } else if (preview.isNotBlank()) {
                append(" • ").append(preview)
            }
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
            node.groupKey?.takeIf { it.isNotBlank() }?.let { return it }
            node.runtimeKey?.takeIf { it.isNotBlank() && !it.startsWith("@compose|") }?.let { key ->
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
                            (if (owner(candidate).isNotBlank() && owner(candidate) == seedOwner) 160 else 0) +
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

    private fun drawGraphScene(g: Graphics2D, model: FlowGraph, updateBounds: Boolean) {
        if (circuitViewEnabled) drawCircuitBackdrop(g)

        val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
        val circuitRouting = if (circuitViewEnabled) buildCircuitRouting(model, layout.bounds) else null
        circuitRoutingCache = circuitRouting
        if (updateBounds) {
            boundsById.clear()
            boundsById.putAll(layout.bounds)
        }

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

        val edgeFocus = focusedEdge?.takeIf { it in model.edges }?.let { computeEdgePathFocus(model, it) }
        val selected = if (edgeFocus == null) selectedId else null
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
            val a = layout.bounds[edge.from] ?: return@forEach
            val b = layout.bounds[edge.to] ?: return@forEach
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
            val runtimeCount = runtimeOverlay.edgeCounts[edge.from to edge.to] ?: 0
            val fromCluster = layout.clusterByNodeId[edge.from]
            val toCluster = layout.clusterByNodeId[edge.to]
            val interCluster = fromCluster != null && toCluster != null && fromCluster != toCluster
            val pathStyle = when {
                edgeFocus == null -> EdgePathStyle.NONE
                edge == edgeFocus.seed -> EdgePathStyle.SEED
                edge in edgeFocus.edges -> EdgePathStyle.PATH
                else -> EdgePathStyle.DIMMED
            }
            drawArrow(
                g, a, b, edge, direction, direct, directionDistance, selected != null,
                runtimeCount, interCluster, pathStyle,
            )
        }
        val searchActive = searchQuery.isNotBlank()
        model.nodes.forEach { node ->
            val bounds = layout.bounds[node.id] ?: return@forEach
            val hasLiveActivity = hasRuntimeActivity(node.id)
            val searchMatch = !searchActive || nodeMatchesSearch(node)
            val edgeDimmed = edgeFocus != null && node.id !in edgeFocus.nodes
            val searchDimmed = searchActive && !searchMatch
            val recentDimmed = recentFocusEnabled && node.id !in recentFocusIds
            val shouldDim = recentDimmed || ((edgeDimmed || searchDimmed) && !hasLiveActivity)
            if (shouldDim) {
                val nodeGraphics = g.create() as Graphics2D
                try {
                    // Match the selected-node causal-focus treatment: non-matching search results
                    // remain spatially visible, but look inactive rather than competing with hits.
                    nodeGraphics.composite = AlphaComposite.SrcOver.derive(0.22f)
                    drawNode(nodeGraphics, node, bounds)
                } finally {
                    nodeGraphics.dispose()
                }
            } else {
                drawNode(g, node, bounds)
                if (edgeFocus != null && node.id in edgeFocus.nodes) {
                    val oldStroke = g.stroke
                    val accent = if (node.id == edgeFocus.seed.from || node.id == edgeFocus.seed.to) {
                        Color(245, 194, 78)
                    } else {
                        Color(72, 220, 190)
                    }
                    g.color = accent
                    g.stroke = BasicStroke(if (node.id == edgeFocus.seed.from || node.id == edgeFocus.seed.to) 2.6f else 1.6f)
                    g.drawRoundRect(bounds.x - 4, bounds.y - 4, bounds.width + 8, bounds.height + 8, 20, 20)
                    g.stroke = oldStroke
                }
            }
        }
    }

    private fun hasRuntimeActivity(nodeId: String): Boolean =
        (runtimeOverlay.nodeCounts[nodeId] ?: 0) > 0 ||
            (runtimeOverlay.changeCounts[nodeId] ?: 0) > 0 ||
            (runtimeOverlay.deliveryCounts[nodeId] ?: 0) > 0 ||
            (runtimeOverlay.collectCounts[nodeId] ?: 0) > 0 ||
            (runtimeOverlay.emitRequestCounts[nodeId] ?: 0) > 0

    private fun magnifierBounds(visible: Rectangle, topLeft: Boolean): Rectangle {
        val lensW = MAGNIFIER_W.coerceAtMost((visible.width - MAGNIFIER_MARGIN * 2).coerceAtLeast(120))
        val lensH = MAGNIFIER_H.coerceAtMost((visible.height - MAGNIFIER_MARGIN * 2).coerceAtLeast(100))
        val x = if (topLeft) {
            visible.x + MAGNIFIER_MARGIN
        } else {
            visible.x + visible.width - lensW - MAGNIFIER_MARGIN
        }
        val y = if (topLeft) {
            visible.y + MAGNIFIER_MARGIN
        } else {
            visible.y + visible.height - lensH - MAGNIFIER_MARGIN
        }
        return Rectangle(x, y, lensW, lensH)
    }

    private fun drawMouseMagnifier(g: Graphics2D, model: FlowGraph) {
        val visible = visibleRect.takeIf { it.width > 0 && it.height > 0 } ?: Rectangle(0, 0, width, height)

        // Bottom-right is the magnifier's stable/home region. If the pointer enters that region,
        // move the painted lens to the opposite corner so the lens never obscures the graph under
        // inspection. Deliberately test against the *home* bounds rather than the currently painted
        // bounds: otherwise the lens would oscillate bottom-right -> top-left -> bottom-right while
        // the pointer stays still. As soon as the pointer leaves the original bottom-right region,
        // the magnifier returns home.
        val homeLens = magnifierBounds(visible, topLeft = false)
        val mouse = magnifierMousePoint
        val lens = if (mouse != null && homeLens.contains(mouse)) {
            magnifierBounds(visible, topLeft = true)
        } else {
            homeLens
        }

        val dark = ((background ?: Color(45, 45, 45)).red +
            (background ?: Color(45, 45, 45)).green +
            (background ?: Color(45, 45, 45)).blue) / 3 < 140
        val frame = if (dark) Color(88, 218, 194) else Color(30, 125, 112)
        val panel = if (dark) Color(18, 24, 29, 244) else Color(248, 250, 250, 246)

        g.color = Color(0, 0, 0, if (dark) 90 else 35)
        g.fillRoundRect(lens.x + 6, lens.y + 7, lens.width, lens.height, 18, 18)
        g.color = panel
        g.fillRoundRect(lens.x, lens.y, lens.width, lens.height, 18, 18)

        val inner = Rectangle(lens.x + 8, lens.y + 28, lens.width - 16, lens.height - 36)
        if (mouse != null && inner.width > 0 && inner.height > 0) {
            val modelX = (mouse.x - canvasPaddingX) / zoom
            val modelY = (mouse.y - canvasPaddingY) / zoom
            val scene = g.create() as Graphics2D
            try {
                scene.clip = inner
                scene.color = background ?: UIManager.getColor("Panel.background") ?: Color(45, 45, 45)
                scene.fillRect(inner.x, inner.y, inner.width, inner.height)
                val twoNodeLogicalWidth = (nodeW * 2 + (if (compactLayout) 86 else colGap)).toDouble()
                val twoNodeScale = inner.width.toDouble() / twoNodeLogicalWidth
                val lensScale = maxOf(zoom, twoNodeScale) * 2.0
                scene.translate(inner.centerX, inner.centerY)
                scene.scale(lensScale, lensScale)
                scene.translate(-modelX, -modelY)
                scene.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                drawGraphScene(scene, model, updateBounds = false)
            } finally {
                scene.dispose()
            }

            g.color = withAlpha(frame, 190)
            g.stroke = BasicStroke(1.2f)
            val cx = inner.x + inner.width / 2
            val cy = inner.y + inner.height / 2
            g.drawLine(cx - 10, cy, cx + 10, cy)
            g.drawLine(cx, cy - 10, cx, cy + 10)
            g.drawOval(cx - 3, cy - 3, 6, 6)
        } else {
            g.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            g.font = font.deriveFont(Font.PLAIN, 11f)
            g.drawString("Move the mouse over the graph", inner.x + 16, inner.y + 26)
        }

        g.color = frame
        g.stroke = BasicStroke(1.8f)
        g.drawRoundRect(lens.x, lens.y, lens.width, lens.height, 18, 18)
        g.font = font.deriveFont(Font.BOLD, 11f)
        g.drawString("MAGNIFIER  •  2× stronger", lens.x + 12, lens.y + 18)
    }

    private fun drawRuntimeEventLens(g: Graphics2D, model: FlowGraph) {
        val now = runtimeReferenceMillis ?: System.currentTimeMillis()
        val candidate = runtimeOverlay.lastActivityAtMillis.asSequence()
            .mapNotNull { (nodeId, atMillis) ->
                val age = (now - atMillis).coerceAtLeast(0L)
                if (age > RUNTIME_ACTIVITY_COOLDOWN_MS) return@mapNotNull null
                val node = model.node(nodeId) ?: return@mapNotNull null
                val bounds = boundsById[nodeId] ?: return@mapNotNull null
                RuntimeLensTarget(node = node, bounds = bounds, ageMs = age)
            }
            .minByOrNull { it.ageMs }
            ?: return

        val node = candidate.node
        val modelBounds = candidate.bounds
        val ageMs = candidate.ageMs
        val screenBounds = Rectangle(
            canvasPaddingX + (modelBounds.x * zoom).toInt(),
            canvasPaddingY + (modelBounds.y * zoom).toInt(),
            (modelBounds.width * zoom).toInt().coerceAtLeast(1),
            (modelBounds.height * zoom).toInt().coerceAtLeast(1),
        )
        if (screenBounds.width >= EVENT_LENS_MIN_NODE_WIDTH_PX && screenBounds.height >= EVENT_LENS_MIN_NODE_HEIGHT_PX) return
        val visible = visibleRect.takeIf { it.width > 0 && it.height > 0 } ?: Rectangle(0, 0, width, height)
        if (!visible.intersects(screenBounds)) return

        val gap = 18
        var lensX = screenBounds.x + screenBounds.width + gap
        if (lensX + EVENT_LENS_W > visible.x + visible.width - 8) {
            lensX = screenBounds.x - EVENT_LENS_W - gap
        }
        lensX = lensX.coerceIn(visible.x + 8, (visible.x + visible.width - EVENT_LENS_W - 8).coerceAtLeast(visible.x + 8))
        var lensY = screenBounds.y + screenBounds.height / 2 - EVENT_LENS_H / 2
        lensY = lensY.coerceIn(visible.y + 8, (visible.y + visible.height - EVENT_LENS_H - 8).coerceAtLeast(visible.y + 8))
        val lens = Rectangle(lensX, lensY, EVENT_LENS_W, EVENT_LENS_H)

        val activityStrength = (1f - ageMs.toFloat() / RUNTIME_ACTIVITY_COOLDOWN_MS.toFloat()).coerceIn(0f, 1f)
        val accent = Color(72, 220, 205, (150 + 105 * activityStrength).toInt().coerceIn(0, 255))
        val nodeColor = when {
            isComposeStateNode(node) -> Color(55, 138, 145)
            else -> when (node.kind) {
                NodeKind.STATE -> Color(75, 115, 185)
                NodeKind.FIELD -> Color(68, 135, 160)
                NodeKind.WRITER -> Color(185, 95, 85)
                NodeKind.EXPOSURE -> Color(80, 145, 110)
                NodeKind.OPERATOR -> Color(120, 105, 175)
                NodeKind.COLLECTOR -> Color(185, 140, 65)
                NodeKind.BEHAVIOR -> Color(170, 105, 70)
                NodeKind.READ -> Color(105, 110, 120)
                NodeKind.COMPOSABLE -> Color(145, 82, 170)
                NodeKind.CYCLE -> Color(135, 85, 155)
                NodeKind.CLUSTER -> Color(85, 95, 110)
            }
        }
        val dark = ((background ?: Color(45, 45, 45)).let { it.red + it.green + it.blue } / 3) < 140
        val cardBackground = if (dark) Color(24, 28, 34, 244) else Color(247, 249, 250, 247)
        val textColor = if (dark) Color(240, 245, 248) else Color(32, 38, 42)
        val secondary = if (dark) Color(176, 188, 194) else Color(83, 94, 101)

        val oldStroke = g.stroke
        // Callout trace from the tiny live node to the magnified card.
        val nodeCx = screenBounds.x + screenBounds.width / 2
        val nodeCy = screenBounds.y + screenBounds.height / 2
        val targetX = if (lens.centerX >= nodeCx) lens.x else lens.x + lens.width
        val targetY = lens.y + lens.height / 2
        val elbowX = (nodeCx + targetX) / 2
        g.color = Color(accent.red, accent.green, accent.blue, (80 + 120 * activityStrength).toInt().coerceIn(0, 255))
        g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(nodeCx, nodeCy, elbowX, nodeCy)
        g.drawLine(elbowX, nodeCy, elbowX, targetY)
        g.drawLine(elbowX, targetY, targetX, targetY)
        g.color = accent
        g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(nodeCx, nodeCy, elbowX, nodeCy)
        g.drawLine(elbowX, nodeCy, elbowX, targetY)
        g.drawLine(elbowX, targetY, targetX, targetY)

        // Professional retro HUD card: restrained glow, node-color rail and live status lamp.
        g.color = Color(0, 0, 0, if (dark) 80 else 38)
        g.fillRoundRect(lens.x + 5, lens.y + 6, lens.width, lens.height, 16, 16)
        g.color = cardBackground
        g.fillRoundRect(lens.x, lens.y, lens.width, lens.height, 16, 16)
        g.color = Color(accent.red, accent.green, accent.blue, 48)
        g.stroke = BasicStroke(5f)
        g.drawRoundRect(lens.x - 2, lens.y - 2, lens.width + 4, lens.height + 4, 18, 18)
        g.color = accent
        g.stroke = BasicStroke(1.6f)
        g.drawRoundRect(lens.x, lens.y, lens.width, lens.height, 16, 16)
        g.color = nodeColor
        g.fillRoundRect(lens.x + 8, lens.y + 10, 6, lens.height - 20, 3, 3)

        val event = runtimeOverlay.lastEventByNode[node.id]
        g.color = accent
        g.fillOval(lens.x + 24, lens.y + 16, 8, 8)
        g.font = font.deriveFont(Font.BOLD, 9f)
        g.drawString("LIVE", lens.x + 38, lens.y + 24)
        val ageText = if (ageMs < 1_000) "${ageMs} ms" else String.format("%.1f s", ageMs / 1000.0)
        val ageWidth = g.fontMetrics.stringWidth(ageText)
        g.color = secondary
        g.drawString(ageText, lens.x + lens.width - ageWidth - 14, lens.y + 24)

        g.color = textColor
        g.font = font.deriveFont(Font.BOLD, 15f)
        g.drawString(node.label.take(34), lens.x + 24, lens.y + 49)
        g.color = secondary
        g.font = font.deriveFont(Font.PLAIN, 10f)
        val detail = node.detail.take(52)
        g.drawString(detail, lens.x + 24, lens.y + 67)

        val eventText = buildString {
            event?.kind?.takeIf { it.isNotBlank() }?.let { append(it.lowercase().replace('_', ' ')) }
            event?.instanceId?.let {
                if (isNotEmpty()) append("  •  ")
                append("#$it")
            }
            event?.valueSummary?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("  •  ")
                append(it)
            }
        }.take(55)
        if (eventText.isNotBlank()) {
            g.color = accent
            g.font = font.deriveFont(Font.PLAIN, 9f)
            g.drawString(eventText, lens.x + 24, lens.y + 83)
        }
        g.stroke = oldStroke
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val model = graph ?: return null
        val point = toModelPoint(event.point)
        val nodeId = boundsById.entries.firstOrNull { it.value.contains(point) }?.key
        if (nodeId != null) return model.node(nodeId)?.detail

        val edge = findEdgeAt(model, point) ?: return null
        val source = navigationSource(model, edge)
        val where = source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
        return "Click to highlight path • double-click to open ${edgeDisplayLabel(edge)}$where"
    }

    private fun findEdgeAt(model: FlowGraph, point: Point): FlowEdge? =
        model.edges.asSequence()
            .mapNotNull { edge ->
                val points = edgeRoute(edge) ?: return@mapNotNull null
                edge to pointToPolylineDistance(point.x.toDouble(), point.y.toDouble(), points)
            }
            .filter { (_, distance) -> distance <= EDGE_HIT_DISTANCE / zoom }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    private fun edgeRoute(edge: FlowEdge): List<Point>? {
        if (circuitViewEnabled) {
            circuitRoutingCache?.routes?.get(edge)?.let { return it }
        }
        val a = boundsById[edge.from] ?: return null
        val b = boundsById[edge.to] ?: return null
        return if (circuitViewEnabled) {
            buildSingleCircuitRoute(edge, a, b, laneOffset = 0)
        } else {
            val line = edgeLineFromBounds(edge, a, b)
            listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
        }
    }

    private data class CircuitRouteSeed(
        val edge: FlowEdge,
        val points: List<Point>,
        val preferredTrunkX: Int,
        val meanY: Int,
    )

    private fun buildCircuitRouting(model: FlowGraph, bounds: Map<String, Rectangle>): CircuitRoutingCache {
        val seeds = model.edges.mapNotNull { edge ->
            val a = bounds[edge.from] ?: return@mapNotNull null
            val b = bounds[edge.to] ?: return@mapNotNull null
            val seedPoints = buildSingleCircuitRoute(edge, a, b, laneOffset = 0)
            val trunkX = seedPoints.drop(1).dropLast(1).firstOrNull { it.x != seedPoints.first().x }?.x
                ?: ((seedPoints.first().x + seedPoints.last().x) / 2)
            CircuitRouteSeed(
                edge = edge,
                points = seedPoints,
                preferredTrunkX = snapCircuitCoordinate(trunkX),
                meanY = ((seedPoints.first().y + seedPoints.last().y) / 2),
            )
        }

        val grouped = seeds.groupBy { it.preferredTrunkX }
        val routes = linkedMapOf<FlowEdge, List<Point>>()
        grouped.toSortedMap().forEach { (trunkBase, group) ->
            val sorted = group.sortedBy { it.meanY }
            val centeredOffsets = centeredLaneOffsets(sorted.size)
            sorted.forEachIndexed { index, seed ->
                val laneOffset = centeredOffsets[index] * CIRCUIT_LANE_SPACING
                val a = bounds[seed.edge.from] ?: return@forEachIndexed
                val b = bounds[seed.edge.to] ?: return@forEachIndexed
                routes[seed.edge] = buildSingleCircuitRoute(seed.edge, a, b, laneOffset = laneOffset, forcedTrunkX = trunkBase)
            }
        }

        return CircuitRoutingCache(routes = routes, junctions = computeCircuitJunctions(routes))
    }

    private fun centeredLaneOffsets(count: Int): List<Int> {
        if (count <= 1) return listOf(0)
        val base = mutableListOf<Int>()
        if (count % 2 == 1) base += 0
        var step = 1
        while (base.size < count) {
            base += -step
            if (base.size < count) base += step
            step++
        }
        return base
    }

    private fun buildSingleCircuitRoute(
        edge: FlowEdge,
        a: Rectangle,
        b: Rectangle,
        laneOffset: Int,
        forcedTrunkX: Int? = null,
    ): List<Point> {
        val base = edgeLineFromBounds(edge, a, b)
        val dir = if (base.x2 >= base.x1) 1 else -1
        val hash = kotlin.math.abs((edge.from + "→" + edge.to + (edge.label ?: edge.kind.name)).hashCode())
        val startStub = 16 + (hash % 3) * 6
        val endStub = 16 + ((hash / 3) % 3) * 6
        val start = Point(base.x1, snapCircuitCoordinate(base.y1, vertical = true))
        val end = Point(base.x2, snapCircuitCoordinate(base.y2, vertical = true))
        val startOut = Point(base.x1 + dir * startStub, start.y)
        val endIn = Point(base.x2 - dir * endStub, end.y)

        val sameColumn = kotlin.math.abs(a.centerX - b.centerX) < ((a.width + b.width) / 2 + 28)
        val limitedGap = if (dir > 0) endIn.x - startOut.x < 56 else startOut.x - endIn.x < 56
        val baseTrunk = when {
            sameColumn || limitedGap -> {
                val outside = if (dir > 0) maxOf(a.x + a.width, b.x + b.width) + 34 else minOf(a.x, b.x) - 34
                snapCircuitCoordinate(outside)
            }
            else -> snapCircuitCoordinate((startOut.x + endIn.x) / 2)
        }
        val routeX = (forcedTrunkX ?: baseTrunk) + laneOffset

        val points = mutableListOf(start, startOut)
        if (routeX != startOut.x) points += Point(routeX, startOut.y)
        if (end.y != start.y) points += Point(routeX, end.y)
        if (endIn.x != routeX || endIn.y != end.y) points += endIn
        points += end
        return simplifyPolyline(points)
    }

    private fun snapCircuitCoordinate(value: Int, vertical: Boolean = false): Int {
        val spacing = if (vertical) CIRCUIT_GRID_SPACING / 2 else CIRCUIT_GRID_SPACING
        val base = if (vertical) spacing / 2 else 0
        val relative = value - base
        return ((relative + spacing / 2) / spacing) * spacing + base
    }

    private fun computeCircuitJunctions(routes: Map<FlowEdge, List<Point>>): Set<Point> {
        data class Segment(val edge: FlowEdge, val a: Point, val b: Point)
        fun isHorizontal(s: Segment) = s.a.y == s.b.y
        fun isVertical(s: Segment) = s.a.x == s.b.x
        fun minX(s: Segment) = minOf(s.a.x, s.b.x)
        fun maxX(s: Segment) = maxOf(s.a.x, s.b.x)
        fun minY(s: Segment) = minOf(s.a.y, s.b.y)
        fun maxY(s: Segment) = maxOf(s.a.y, s.b.y)
        fun pointOnSegment(p: Point, s: Segment): Boolean = when {
            isHorizontal(s) -> p.y == s.a.y && p.x in minX(s)..maxX(s)
            isVertical(s) -> p.x == s.a.x && p.y in minY(s)..maxY(s)
            else -> false
        }

        val segments = routes.flatMap { (edge, points) ->
            points.zipWithNext().map { (a, b) -> Segment(edge, a, b) }
        }
        val junctions = linkedSetOf<Point>()
        val pointUse = mutableMapOf<Point, MutableSet<FlowEdge>>()
        routes.forEach { (edge, points) ->
            points.drop(1).dropLast(1).forEach { point ->
                pointUse.getOrPut(Point(point)) { linkedSetOf() } += edge
            }
        }
        pointUse.filterValues { it.size >= 2 }.keys.forEach { junctions += it }

        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                val first = segments[i]
                val second = segments[j]
                if (first.edge == second.edge) continue
                if (isHorizontal(first) && isVertical(second)) {
                    val p = Point(second.a.x, first.a.y)
                    if (p.x in minX(first)..maxX(first) && p.y in minY(second)..maxY(second)) junctions += p
                } else if (isVertical(first) && isHorizontal(second)) {
                    val p = Point(first.a.x, second.a.y)
                    if (p.x in minX(second)..maxX(second) && p.y in minY(first)..maxY(first)) junctions += p
                }
            }
        }

        routes.forEach { (edge, points) ->
            points.drop(1).dropLast(1).forEach { point ->
                segments.filter { it.edge != edge && pointOnSegment(point, it) }.forEach { junctions += Point(point) }
            }
        }
        return junctions
    }

    private fun simplifyPolyline(points: List<Point>): List<Point> {
        if (points.size <= 2) return points
        val simplified = mutableListOf<Point>()
        points.forEach { point ->
            if (simplified.isEmpty() || simplified.last() != point) {
                simplified += Point(point)
            }
        }
        var index = 1
        while (index < simplified.lastIndex) {
            val a = simplified[index - 1]
            val b = simplified[index]
            val c = simplified[index + 1]
            val collinear = (a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)
            if (collinear) simplified.removeAt(index) else index++
        }
        return simplified
    }

    private fun pointToPolylineDistance(px: Double, py: Double, points: List<Point>): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val distance = pointToSegmentDistance(px, py, a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble())
            if (distance < best) best = distance
        }
        return best
    }

    private fun pointToSegmentDistance(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
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

    private fun computeEdgePathFocus(model: FlowGraph, seed: FlowEdge): EdgePathFocus {
        val pathEdges = linkedSetOf<FlowEdge>()
        val pathNodes = linkedSetOf<String>()
        pathEdges += seed
        pathNodes += seed.from
        pathNodes += seed.to

        fun expandable(nodeId: String): Boolean {
            val kind = model.node(nodeId)?.kind
            return kind !in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)
        }

        val upstream = ArrayDeque<String>()
        upstream += seed.from
        val upstreamSeen = mutableSetOf<String>()
        while (upstream.isNotEmpty()) {
            val current = upstream.removeFirst()
            if (!upstreamSeen.add(current)) continue
            if (current != seed.from && !expandable(current)) continue
            model.edges.forEach { edge ->
                if (edge.to != current || edge == seed) return@forEach
                if (edge.kind == EdgeKind.READS && seed.kind != EdgeKind.READS) return@forEach
                pathEdges += edge
                pathNodes += edge.from
                pathNodes += edge.to
                upstream += edge.from
            }
        }

        val downstream = ArrayDeque<String>()
        downstream += seed.to
        val downstreamSeen = mutableSetOf<String>()
        while (downstream.isNotEmpty()) {
            val current = downstream.removeFirst()
            if (!downstreamSeen.add(current)) continue
            if (current != seed.to && !expandable(current)) continue
            model.edges.forEach { edge ->
                if (edge.from != current || edge == seed) return@forEach
                if (seed.kind == EdgeKind.READS && edge.kind != EdgeKind.READS) return@forEach
                pathEdges += edge
                pathNodes += edge.from
                pathNodes += edge.to
                downstream += edge.to
            }
        }

        return EdgePathFocus(seed = seed, edges = pathEdges, nodes = pathNodes)
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

    private fun nodeMatchesSearch(node: FlowNode): Boolean {
        val query = searchQuery
        if (query.isBlank()) return false
        val q = query.lowercase()
        return sequenceOf(
            node.label,
            node.detail,
            node.id,
            node.runtimeKey,
            node.groupLabel,
            node.groupKey,
            node.source?.file?.name,
            node.source?.file?.path,
        ).filterNotNull().any { it.lowercase().contains(q) }
    }

    private fun isComposeStateNode(node: FlowNode): Boolean =
        node.kind == NodeKind.STATE && node.detail.contains("Compose state", ignoreCase = true)

    private fun composeStateShape(r: Rectangle, inset: Int = 0): Polygon {
        val x = r.x - inset
        val y = r.y - inset
        val w = r.width + inset * 2
        val h = r.height + inset * 2
        val cut = (11 + inset / 2).coerceAtMost((h / 3).coerceAtLeast(6))
        return Polygon(
            intArrayOf(x + cut, x + w - cut, x + w, x + w, x + w - cut, x + cut, x, x),
            intArrayOf(y, y, y + cut, y + h - cut, y + h, y + h, y + h - cut, y + cut),
            8,
        )
    }

    private fun fillNodeShape(g: Graphics2D, node: FlowNode, r: Rectangle) {
        if (isComposeStateNode(node)) g.fillPolygon(composeStateShape(r))
        else g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16)
    }

    private fun drawNodeShapeOutline(g: Graphics2D, node: FlowNode, r: Rectangle, inset: Int = 0) {
        if (isComposeStateNode(node)) {
            g.drawPolygon(composeStateShape(r, inset))
        } else {
            g.drawRoundRect(r.x - inset, r.y - inset, r.width + inset * 2, r.height + inset * 2, 16 + inset * 2, 16 + inset * 2)
        }
    }

    private fun drawComposeStateBadge(g: Graphics2D, r: Rectangle, overviewCompact: Boolean) {
        if (overviewCompact || r.width < 130 || r.height < 54) return
        val badgeW = 24
        val badgeH = 14
        val x = r.x + r.width - badgeW - 12
        val y = r.y + 8
        val dark = ((background ?: Color(45, 45, 45)).let { it.red + it.green + it.blue } / 3) < 140
        g.color = if (dark) Color(18, 52, 60, 205) else Color(236, 250, 248, 225)
        g.fillRoundRect(x, y, badgeW, badgeH, 7, 7)
        g.color = if (dark) Color(155, 245, 225) else Color(20, 105, 95)
        g.font = font.deriveFont(Font.BOLD, 8f)
        val fm = g.fontMetrics
        val label = "CS"
        g.drawString(label, x + (badgeW - fm.stringWidth(label)) / 2, y + 10)
    }

    private fun drawNode(g: Graphics2D, node: FlowNode, r: Rectangle) {
        val overviewCompact = r.height <= PROJECT_OVERVIEW_NODE_H
        val color = when {
            isComposeStateNode(node) -> Color(55, 138, 145)
            else -> when (node.kind) {
            NodeKind.STATE -> Color(75, 115, 185)
            NodeKind.FIELD -> Color(68, 135, 160)
            NodeKind.WRITER -> Color(185, 95, 85)
            NodeKind.EXPOSURE -> Color(80, 145, 110)
            NodeKind.OPERATOR -> Color(120, 105, 175)
            NodeKind.COLLECTOR -> Color(185, 140, 65)
            NodeKind.BEHAVIOR -> Color(170, 105, 70)
            NodeKind.READ -> Color(105, 110, 120)
            NodeKind.COMPOSABLE -> Color(145, 82, 170)
            NodeKind.CYCLE -> Color(135, 85, 155)
            NodeKind.CLUSTER -> Color(85, 95, 110)
            }
        }
        g.color = color
        fillNodeShape(g, node, r)
        if (isComposeStateNode(node)) drawComposeStateBadge(g, r, overviewCompact)

        // Compose nodes show only compact live-inspection status on the graph itself. The actual
        // pixels live in the dedicated UI Preview tab so the render is not squeezed into the node.
        if (node.kind == NodeKind.COMPOSABLE && !overviewCompact && r.width >= 190) {
            drawComposeSurface(g, node, r)
        }

        if (node.id == selectedId) {
            val oldStroke = g.stroke
            g.color = UIManager.getColor("Focus.color") ?: Color.WHITE
            g.stroke = BasicStroke(3f)
            drawNodeShapeOutline(g, node, r, inset = 2)
            g.stroke = oldStroke
        }

        if (nodeMatchesSearch(node)) {
            val oldStroke = g.stroke
            val dark = ((background ?: Color(45, 45, 45)).red +
                (background ?: Color(45, 45, 45)).green +
                (background ?: Color(45, 45, 45)).blue) / 3 < 140
            val searchColor = if (dark) Color(255, 205, 82) else Color(195, 125, 18)
            g.color = withAlpha(searchColor, 58)
            g.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            drawNodeShapeOutline(g, node, r, inset = 8)
            g.color = searchColor
            g.stroke = BasicStroke(if (overviewCompact) 2.4f else 3.2f)
            drawNodeShapeOutline(g, node, r, inset = 5)
            g.stroke = oldStroke
        }

        val runtimeCount = runtimeOverlay.nodeCounts[node.id] ?: 0
        val runtimeChanges = runtimeOverlay.changeCounts[node.id] ?: 0
        val runtimeDeliveries = runtimeOverlay.deliveryCounts[node.id] ?: 0
        val runtimeCollections = runtimeOverlay.collectCounts[node.id] ?: 0
        val runtimeRequests = runtimeOverlay.emitRequestCounts[node.id] ?: 0
        val runtimeReads = runtimeOverlay.readCounts[node.id] ?: 0
        val hasPersistentRuntimeActivity = runtimeCount > 0 || runtimeChanges > 0 || runtimeDeliveries > 0 || runtimeCollections > 0 || runtimeRequests > 0
        val hasAnyRuntimeCounter = hasPersistentRuntimeActivity || runtimeReads > 0
        if (hasAnyRuntimeCounter) {
            val oldStroke = g.stroke

            // Reads are shown as counters only. Persistent live highlighting is reserved for actual
            // mutations/emissions/recompositions/deliveries so a hot Compose read loop cannot look
            // like thousands of state changes.
            val alpha = 235
            if (hasPersistentRuntimeActivity) {
                g.color = Color(70, 205, 205, alpha)
                g.stroke = BasicStroke(3.0f)
                drawNodeShapeOutline(g, node, r, inset = 7)
            }
            if (node.kind == NodeKind.COMPOSABLE && composeRenderService.snapshot(node.id) != null && !overviewCompact) {
                val surface = composeSurfaceRect(r)
                g.color = Color(70, 225, 225, 95)
                g.fillRoundRect(surface.x, surface.y, surface.width, surface.height, 9, 9)
                g.color = Color(120, 255, 255, alpha)
                g.stroke = BasicStroke(2.5f)
                g.drawRoundRect(surface.x, surface.y, surface.width, surface.height, 9, 9)
            }

            // Historical activity remains available as a compact badge and persistent outline.
            g.color = Color(70, 145, 170)
            if (overviewCompact) {
                val activity = runtimeCount + runtimeChanges + runtimeDeliveries + runtimeCollections + runtimeRequests + runtimeReads
                val badgeText = "×$activity"
                val badgeWidth = (badgeText.length * 6 + 10).coerceAtLeast(28)
                g.fillRoundRect(r.x + r.width - badgeWidth - 5, r.y + 4, badgeWidth, 14, 7, 7)
                g.color = Color.WHITE
                g.font = font.deriveFont(Font.BOLD, 8f)
                g.drawString(badgeText, r.x + r.width - badgeWidth, r.y + 14)
            } else {
                val badgeText = buildString {
                    if (runtimeCount > 0) append(if (node.kind == NodeKind.COMPOSABLE) "recompose ×$runtimeCount" else "emit ×$runtimeCount")
                    if (runtimeChanges > 0) { if (isNotEmpty()) append(" • "); append("change ×$runtimeChanges") }
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
            val labelLimit = when {
                node.kind == NodeKind.COMPOSABLE -> 18
                isComposeStateNode(node) -> 24
                else -> 33
            }
            val detailLimit = if (node.kind == NodeKind.COMPOSABLE) 24 else 54
            g.drawString(node.label.take(labelLimit), r.x + 12, r.y + 26)
            g.font = font.deriveFont(Font.PLAIN, 10f)
            g.drawString(node.detail.take(detailLimit), r.x + 12, r.y + 51)
        }
    }

    private fun composeSurfaceRect(nodeRect: Rectangle): Rectangle =
        Rectangle(
            nodeRect.x + nodeRect.width - 78,
            nodeRect.y + 9,
            66,
            22,
        )

    private fun drawComposeSurface(g: Graphics2D, node: FlowNode, nodeRect: Rectangle) {
        val surface = composeSurfaceRect(nodeRect)
        val state = composeRenderService.state(node.id)
        val ready = state as? ComposeRenderState.Ready
        val text = when {
            ready != null -> "LIVE UI"
            state is ComposeRenderState.Loading -> "UI …"
            state is ComposeRenderState.Failed -> "UI !"
            else -> "UI"
        }

        g.color = when {
            ready != null -> Color(38, 108, 78, 225)
            state is ComposeRenderState.Loading -> Color(78, 82, 126, 225)
            state is ComposeRenderState.Failed -> Color(126, 62, 62, 225)
            else -> Color(70, 67, 82, 215)
        }
        g.fillRoundRect(surface.x, surface.y, surface.width, surface.height, 11, 11)
        g.color = Color(255, 255, 255, 210)
        g.drawRoundRect(surface.x, surface.y, surface.width, surface.height, 11, 11)
        g.font = font.deriveFont(Font.BOLD, if (text.length > 7) 7f else 8f)
        val fm = g.fontMetrics
        val tx = surface.x + ((surface.width - fm.stringWidth(text)) / 2).coerceAtLeast(4)
        val ty = surface.y + ((surface.height - fm.height) / 2) + fm.ascent
        g.drawString(text, tx, ty)
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
        runtimeCount: Int,
        interCluster: Boolean,
        pathStyle: EdgePathStyle,
    ) {
        val points = edgeRoute(edge) ?: return
        val oldStroke = g.stroke
        val normal = UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY

        val runtimeColor = if (runtimeCount > 0) Color(70, 205, 205) else null
        val directionColor = when (direction) {
            EdgeDirection.FORWARD -> directionalColor(forward = true)
            EdgeDirection.BACKWARD -> directionalColor(forward = false)
            EdgeDirection.BOTH -> cycleColor()
            EdgeDirection.POSSIBLE -> possibleColor()
            EdgeDirection.NONE -> null
        }

        val distance = directionDistance ?: if (direct) 1 else 4
        val distanceAlpha = when (distance) {
            1 -> 255
            2 -> 220
            3 -> 180
            else -> 135
        }
        val baseColor = when (pathStyle) {
            EdgePathStyle.SEED -> Color(245, 194, 78)
            EdgePathStyle.PATH -> Color(72, 220, 190)
            EdgePathStyle.DIMMED -> withAlpha(normal, 34)
            EdgePathStyle.NONE -> if (hasSelection) {
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
                    interCluster -> withAlpha(normal, 92)
                    else -> normal
                }
            }
        }

        val width = when {
            pathStyle == EdgePathStyle.SEED -> 5.2f
            pathStyle == EdgePathStyle.PATH -> 3.5f
            pathStyle == EdgePathStyle.DIMMED -> 0.85f
            hasSelection && direct && direction in setOf(EdgeDirection.FORWARD, EdgeDirection.BACKWARD, EdgeDirection.BOTH) -> 4.8f
            hasSelection && direction == EdgeDirection.POSSIBLE -> 1.8f
            hasSelection && direction != EdgeDirection.NONE -> when (distance) {
                1 -> 4.0f
                2 -> 3.1f
                3 -> 2.4f
                else -> 1.8f
            }
            runtimeCount > 0 -> 4.6f
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

        if (circuitViewEnabled && pathStyle != EdgePathStyle.DIMMED) {
            val glowAlpha = if (pathStyle == EdgePathStyle.SEED) 88 else 56
            val glowStroke = BasicStroke(width + if (pathStyle == EdgePathStyle.SEED) 4.5f else 3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = withAlpha(baseColor, glowAlpha)
            g.stroke = glowStroke
            drawPolyline(g, points)
        }

        g.color = baseColor
        g.stroke = if (dash != null) {
            if (circuitViewEnabled) {
                BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
            } else {
                BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
            }
        } else {
            if (circuitViewEnabled) BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) else BasicStroke(width)
        }

        drawPolyline(g, points)
        val arrow = if (direct && direction != EdgeDirection.NONE) 11 else 9
        drawArrowHead(g, points, arrow)

        g.font = font.deriveFont(
            if (edge.kind == EdgeKind.PROPAGATES || (direct && direction != EdgeDirection.NONE)) Font.BOLD else Font.PLAIN,
            if (direct && direction != EdgeDirection.NONE) 10f else 9f,
        )
        val overlayLabel = buildString {
            append(edgeDisplayLabel(edge))
            if (runtimeCount > 0) append(" • live ×$runtimeCount")
        }
        val showEdgeLabel = pathStyle in setOf(EdgePathStyle.SEED, EdgePathStyle.PATH) ||
            hasSelection || direct || runtimeCount > 0 ||
            (!interCluster && zoom >= 0.42 && pathStyle != EdgePathStyle.DIMMED)
        if (showEdgeLabel) {
            val labelPoint = polylineMidPoint(points)
            g.drawString(overlayLabel.take(90), labelPoint.x + 8, labelPoint.y - 6)
        }
        g.stroke = oldStroke
    }

    private fun drawPolyline(g: Graphics2D, points: List<Point>) {
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            g.drawLine(a.x, a.y, b.x, b.y)
        }
    }

    private fun drawArrowHead(g: Graphics2D, points: List<Point>, size: Int) {
        if (points.size < 2) return
        val end = points.last()
        val prev = points.asReversed().zipWithNext().firstOrNull { (a, b) -> a != b }?.second ?: points[points.lastIndex - 1]
        val dx = end.x - prev.x
        val dy = end.y - prev.y
        if (dx == 0 && dy == 0) return
        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            val dir = if (dx >= 0) 1 else -1
            g.drawLine(end.x, end.y, end.x - dir * size, end.y - 5)
            g.drawLine(end.x, end.y, end.x - dir * size, end.y + 5)
        } else {
            val dir = if (dy >= 0) 1 else -1
            g.drawLine(end.x, end.y, end.x - 5, end.y - dir * size)
            g.drawLine(end.x, end.y, end.x + 5, end.y - dir * size)
        }
    }

    private fun polylineMidPoint(points: List<Point>): Point {
        if (points.isEmpty()) return Point()
        if (points.size == 1) return Point(points.first())
        val lengths = mutableListOf<Double>()
        var total = 0.0
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            val length = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            lengths += length
            total += length
        }
        var target = total / 2.0
        for (index in lengths.indices) {
            val length = lengths[index]
            val a = points[index]
            val b = points[index + 1]
            if (target <= length && length > 0.0) {
                val t = target / length
                return Point(
                    (a.x + (b.x - a.x) * t).toInt(),
                    (a.y + (b.y - a.y) * t).toInt(),
                )
            }
            target -= length
        }
        return Point(points.last())
    }


    private fun drawCircuitJunctions(g: Graphics2D, junctions: Set<Point>) {
        if (!circuitViewEnabled || junctions.isEmpty()) return
        val dark = ((background ?: Color(45, 45, 45)).red + (background ?: Color(45, 45, 45)).green + (background ?: Color(45, 45, 45)).blue) / 3 < 140
        val outer = if (dark) Color(255, 245, 210, 220) else Color(50, 80, 75, 200)
        val inner = if (dark) Color(0, 255, 205, 235) else Color(0, 145, 120, 235)
        junctions.forEach { point ->
            g.color = outer
            g.fillOval(point.x - 5, point.y - 5, 10, 10)
            g.color = inner
            g.fillOval(point.x - 3, point.y - 3, 6, 6)
        }
    }

    private fun drawCircuitBackdrop(g: Graphics2D) {
        val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
        val dark = ((background ?: Color(45,45,45)).red + (background ?: Color(45,45,45)).green + (background ?: Color(45,45,45)).blue) / 3 < 140
        val major = if (dark) Color(0, 255, 190, 28) else Color(0, 110, 90, 24)
        val minor = if (dark) Color(0, 255, 190, 12) else Color(0, 110, 90, 10)
        val dot = if (dark) Color(255, 205, 90, 42) else Color(160, 120, 20, 32)
        val startX = (clip.x / CIRCUIT_GRID_SPACING) * CIRCUIT_GRID_SPACING
        val startY = (clip.y / CIRCUIT_GRID_SPACING) * CIRCUIT_GRID_SPACING
        val oldStroke = g.stroke
        for (x in startX..(clip.x + clip.width + CIRCUIT_GRID_SPACING) step CIRCUIT_GRID_SPACING) {
            g.color = if (((x / CIRCUIT_GRID_SPACING) % 4) == 0) major else minor
            g.stroke = BasicStroke(if (((x / CIRCUIT_GRID_SPACING) % 4) == 0) 1.15f else 1f)
            g.drawLine(x, clip.y, x, clip.y + clip.height)
        }
        for (y in startY..(clip.y + clip.height + CIRCUIT_GRID_SPACING) step CIRCUIT_GRID_SPACING) {
            g.color = if (((y / CIRCUIT_GRID_SPACING) % 4) == 0) major else minor
            g.stroke = BasicStroke(if (((y / CIRCUIT_GRID_SPACING) % 4) == 0) 1.15f else 1f)
            g.drawLine(clip.x, y, clip.x + clip.width, y)
        }
        g.stroke = oldStroke
        for (x in startX..(clip.x + clip.width + CIRCUIT_GRID_SPACING) step CIRCUIT_GRID_SPACING * 2) {
            for (y in startY..(clip.y + clip.height + CIRCUIT_GRID_SPACING) step CIRCUIT_GRID_SPACING * 2) {
                g.color = dot
                g.fillOval(x - 1, y - 1, 3, 3)
            }
        }
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
        NodeKind.COMPOSABLE -> 7
        NodeKind.CYCLE -> 8
        NodeKind.CLUSTER -> 9
        NodeKind.READ -> 10
    }

    private companion object {
        const val EDGE_HIT_DISTANCE = 12.0
        const val MIN_ZOOM = 0.20
        const val MAX_ZOOM = 3.50
        const val ZOOM_STEP = 1.18
        // Wheel/trackpad zoom is intentionally finer than +/- toolbar zoom. exp(0.075) ~= 1.078,
        // so a traditional one-notch wheel changes scale by ~8% instead of 18%, while precise
        // trackpad rotations remain fully fractional.
        const val WHEEL_ZOOM_SENSITIVITY = 0.075
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
        const val PROJECT_OVERVIEW_MIN_ZOOM = 0.20
        const val PROJECT_OVERVIEW_MAX_GRID_COLUMNS = 18
        const val PROJECT_OVERVIEW_MAX_CLUSTER_NODES = 24
        const val PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS = 6
    }
}
