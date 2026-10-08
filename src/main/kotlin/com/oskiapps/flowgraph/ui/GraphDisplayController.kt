package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.analysis.analyzeAllProjectFlows

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

internal fun FlowGraphPanel.updateAllFlowsStatus(label: String, color: Color) {
    allFlowsStatusIndicator.text = "● $label"
    allFlowsStatusIndicator.foreground = color
    allFlowsStatusIndicator.toolTipText = "Project-wide Flow analysis: $label"
}



internal fun FlowGraphPanel.analyzeAllFlows() {
    if (DumbService.isDumb(project)) {
        hint.text = "Wait until indexing finishes, then choose Load all flows again."
        updateAllFlowsStatus("Waiting", Color(230, 170, 55))
        return
    }

    allFlows.isEnabled = false
    allFlows.text = "Loading…"
    updateAllFlowsStatus("Loading", Color(230, 170, 55))
    title.text = "Scanning project Flow state + Compose topology…"
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



internal fun FlowGraphPanel.setGraph(graph: FlowGraph) {
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
    navigationHistory.clear()
    showAll.isEnabled = false
    showOperators.isSelected = false
    selectedNodeId = null
    detailFocusNodeId = null
    selectionFocusActive = false
    backToGraph.isEnabled = false
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
        "Project-wide production Flow + Compose topology. The overview is organized as left-to-right consumer lanes: each Compose screen gets its influencing Flow/State tree locally on the left, and Flow-only consumers get their own hierarchical lanes. Shared Flow and Compose nodes may repeat for readability instead of creating long cross-canvas connections. Select any node to isolate its canonical causal cone; Show all restores the lane overview."
    } else {
        "Select a node to isolate its causal cone. Only upstream/downstream connections will remain and the graph will re-layout compactly. Live history is remapped automatically when you switch flows."
    }
    updateComposePreview()
    pendingCurrentComposeCandidates?.let { candidates ->
        if (isAllProjectFlows(graph.rootLabel)) {
            pendingCurrentComposeCandidates = null
            focusBestCurrentComposeMatch(candidates)
        }
    }
    historyCursorMillis?.let { onTimelineScrubbed(it) }
    // Preserve the camera on graph updates; Fit is always explicit.
}



internal fun FlowGraphPanel.graphFitAction() {
    // JScrollPane can change its extent after zoom because scrollbars are revalidated.
    // Layout first, then calculate the final scale and center in the same click.
    val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, canvas)
        as? JViewport ?: return
    val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, canvas)
        as? JScrollPane
    scrollPane?.doLayout()
    viewport.doLayout()
    canvas.fitToViewport()
}



internal fun FlowGraphPanel.cancelPendingAutoFit() {
    autoFitGeneration++
}

/** A single deferred fit; user zoom/pan invalidates it immediately. */



internal fun FlowGraphPanel.scheduleSettledAutoFit() {
    // Never override manual zoom/pan; user can press Fit explicitly.
}



internal fun FlowGraphPanel.renderActive(
    autoFit: Boolean = false,
    preserveFocusedViewport: Boolean = false,
) {
    val base = activeGraph ?: return
    val readFiltered = if (showReads.isSelected) base else base.withoutReads()
    val runtimeBranchOverview =
        liveTrace.isSelected && runtimeBranchesOnly.isSelected && selectedNodeId == null && isAllProjectFlows(base.rootLabel)
    // Runtime branches is an evidence lens, so never throw away the operator/collector graph
    // before applying it. Even with Show operators off we keep the coroutine path here and
    // compact it later into a Flow/state architecture graph.
    var projected = if (runtimeBranchOverview || showOperators.isSelected) {
        readFiltered
    } else {
        readFiltered.stateChangeProjection(includeReads = showReads.isSelected)
    }

    // This is a structural filter, not a paint-only toggle. Remove inferred/possible edges
    // before focus/layout so both All Flows and detail view are rebuilt from the definite graph
    // and can compact into the space that those connections previously occupied.
    if (!showPossibleConnections.isSelected) {
        projected = projected.withoutPossibleConnections()
    }

    // Compose framework call sites are useful when inspecting the exact visual tree, but they
    // can overwhelm the architecture view with layout primitives (Column, Row, Text, Offset,
    // Surface, etc.). Contract them out structurally when requested: project composables stay
    // connected to their nearest project-composable descendants, so filtering does not break
    // the tree into islands and the relayout can become genuinely compact.
    if (projectComposablesOnly.isSelected) {
        projected = projected.withoutExternalComposeCallSites()
    }

    // Session-observed branch lens. Apply this to the detailed Flow graph first so runtime
    // branches retain the operators/collectors/writers which explain *why* one StateFlow led
    // to another instead of degrading into state boxes only.
    if (runtimeBranchOverview) {
        // First contract field-detail nodes while the full causal chain is still available; then
        // apply runtime pruning to the resulting Flow/state graph. Doing it in this order keeps
        // edges such as writer -> StateFlow even when the intermediate FIELD box itself never
        // produced runtime evidence.
        projected = projected.flowStateArchitectureProjection(includeReads = showReads.isSelected)
        projected = projected.focusRuntimeActivityBranches(
            activeNodeIds = runtimeObservedNodeIds(),
            observedEdges = runtimeObservedEdges(),
        )
    }

    // Follow the same high-level/then-drill-down idea used by Compose-oriented explorers:
    // All Flows is an architectural map, while selecting a node opens the complete causal
    // detail. Concise mode is deliberately overview-only so no evidence disappears while
    // debugging a specific state/composable. In runtime-branch mode it shortens labels and
    // deduplicates calls without removing the coroutine graph retained above.
    if (conciseOverview.isSelected && selectedNodeId == null && isAllProjectFlows(base.rootLabel)) {
        projected = projected.conciseOverviewProjection()
    }

    // Optional Flow -> UI impact lens. This is deliberately applied before the ordinary layer
    // visibility filters so it can still determine affected composables when the user hides
    // the coroutine layer itself and wants a Compose-only answer.
    projected = keepOnlyCoroutineInfluencedComposables(projected)

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

    // The three semantic checkboxes are global view filters. Apply them before building the
    // focused causal cone as well as in All Flows, so detail view never resurrects a layer that
    // the user explicitly hid. Helper/operator chains are retained only where the filter says
    // they support a visible state layer.
    projected = applyOverviewVisibilityFilters(projected)

    val filteredSelected = selectedNodeId
    if (filteredSelected != null && projected.node(filteredSelected) == null) {
        selectedNodeId = null
        selectionFocusActive = false
        resetDetailExpansionDepths()
        fieldFocus.isEnabled = false
        fieldFocus.removeAllItems()
        fieldFocus.addItem("All fields")
        canvas.selectedId = null
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
    canvas.recentFocusEnabled = liveTrace.isSelected && recentOnly.isSelected
    canvas.recentFocusIds = if (liveTrace.isSelected && recentOnly.isSelected) recentActiveNodeIds() else emptySet()
    updateDetailExpansionControls(projected, field)
    showAll.isEnabled = selectionFocusActive || base !== fullGraph

    updateComposeRenderPaneVisibility()

    val focusedLabel = when {
        liveTrace.isSelected && recentOnly.isSelected -> "Recently active (${recentActiveNodeIds().size})"
        selectionFocusActive -> {
            val nodeLabel = selectedNodeId?.let { projected.node(it)?.label }
            val baseLabel = if (field != null && nodeLabel != null) "$nodeLabel.$field" else nodeLabel
            baseLabel?.let { "$it • ←${depthLabel(detailUpstreamDepth)} | ${depthLabel(detailDownstreamDepth)}→" }
        }
        base !== fullGraph -> base.rootLabel
        else -> null
    }
    publishGraph(visible, focusedLabel)
    // No automatic viewport changes after rendering.
    val historyCursor = historyCursorMillis
    if (historyCursor != null) updateHistoricalDetails(historyCursor) else updateDetails()
}


