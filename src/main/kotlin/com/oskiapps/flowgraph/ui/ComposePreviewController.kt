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

internal fun FlowGraphPanel.updateComposePreview() {
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
            if (node.id.startsWith("compose-call:")) {
                append("This is a static Compose framework call-site node. Select a named source @Composable to inspect live pixels.")
            } else {
                append("This is a Compose host node without an inspectable source @Composable. Select a named @Composable child.")
            }
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



internal fun FlowGraphPanel.showComposeRenderDialog(node: FlowNode, snapshot: ComposeRenderSnapshot) {
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



internal fun FlowGraphPanel.setCurrentUiDiagnostic(message: String?, reveal: Boolean = false) {
    currentUiDiagnostic = message?.trim()?.takeIf { it.isNotEmpty() }
    if (reveal && currentUiDiagnostic != null) {
        liveInfo.isSelected = true
        liveDiagnostics.isVisible = liveTrace.isSelected
    }
    updateLiveTraceDiagnostics()
    if (reveal) {
        liveDiagnostics.caretPosition = 0
        liveDiagnostics.revalidate()
        liveDiagnostics.parent?.revalidate()
        revalidate()
        repaint()
    }
}



internal fun FlowGraphPanel.jumpToCurrentPhoneComposable() {
    val status = liveTraceService.status()
    if (!status.running || status.activeClients <= 0) {
        val message = "Current UI requires a connected live debug app."
        hint.text = message
        setCurrentUiDiagnostic(message, reveal = true)
        return
    }

    val requestedAt = System.currentTimeMillis()
    currentComposeRequestStartedAt = requestedAt
    currentUi.isEnabled = false
    currentUi.text = "Locating…"
    hint.text = "Inspecting the Compose UI currently visible on the phone…"
    setCurrentUiDiagnostic("Current UI: request sent; waiting for the connected app…")

    if (liveTraceService.requestCurrentCompose() <= 0) {
        currentUi.text = "Current UI"
        currentUi.isEnabled = true
        val message = "The connected app did not accept the Current UI request."
        hint.text = "Current UI failed • see ⓘ diagnostics"
        setCurrentUiDiagnostic(message, reveal = true)
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
                val message = response.valueSummary ?: "Could not identify a currently visible composable."
                hint.text = "Current UI failed • see ⓘ diagnostics"
                setCurrentUiDiagnostic(message, reveal = true)
                return@addActionListener
            }
            val candidates = response.valueSummary.orEmpty()
                .lineSequence()
                .mapIndexedNotNull { index, line ->
                    val key = line.substringBefore('\t').trim()
                    if (!isSourceComposeRuntimeKey(key)) return@mapIndexedNotNull null
                    val area = line.substringAfter('\t', "0").trim().toLongOrNull() ?: 0L
                    CurrentUiRuntimeCandidate(key, area.coerceAtLeast(0L), index)
                }
                .groupBy { it.runtimeKey }
                .map { (_, sameKey) ->
                    sameKey.maxWithOrNull(
                        compareBy<CurrentUiRuntimeCandidate> { it.visibleArea }
                            .thenBy { -it.responseOrder }
                    )!!
                }
                .sortedBy { it.responseOrder }
            if (candidates.isEmpty()) {
                val message = "No inspectable source composable is currently visible on the phone."
                hint.text = "Current UI found no source composable • see ⓘ diagnostics"
                setCurrentUiDiagnostic(message, reveal = true)
                return@addActionListener
            }
            if (!focusBestCurrentComposeMatch(candidates)) {
                val loaded = fullGraph
                if (loaded == null || !isAllProjectFlows(loaded.rootLabel)) {
                    pendingCurrentComposeCandidates = candidates
                    hint.text = "Current UI found. Loading all flows so Flow Graph can locate its composable node…"
                    setCurrentUiDiagnostic("Current UI: runtime composable candidates received (${candidates.size}); loading All Flows to locate the matching graph node.")
                    analyzeAllFlows()
                } else {
                    val message = "The current phone UI is visible to Compose, but none of its composables are represented in the loaded Flow graph. Runtime candidates received: ${candidates.size}."
                    hint.text = "Current UI has no graph match • see ⓘ diagnostics"
                    setCurrentUiDiagnostic(message, reveal = true)
                }
            }
            return@addActionListener
        }
        if (System.currentTimeMillis() - requestedAt >= 5_000L) {
            timer.stop()
            currentUi.text = "Current UI"
            currentUi.isEnabled = liveTraceService.status().activeClients > 0
            val message = "Timed out while locating the current phone composable after 5 seconds."
            hint.text = "Current UI timed out • see ⓘ diagnostics"
            setCurrentUiDiagnostic(message, reveal = true)
        }
    }
    timer.isRepeats = true
    timer.start()
}



internal fun FlowGraphPanel.focusBestCurrentComposeMatch(candidates: List<CurrentUiRuntimeCandidate>): Boolean {
    val graph = fullGraph ?: return false

    data class ResolvedCandidate(
        val runtime: CurrentUiRuntimeCandidate,
        val node: FlowNode,
        val composeDepth: Int,
        val semanticView: Boolean,
    )

    val composeEdges = graph.edges.filter { it.kind == EdgeKind.COMPOSES }
    val composeOutgoing = composeEdges.groupBy { it.from }
    val composeIncoming = composeEdges.groupBy { it.to }
    val composeNodeIds = graph.nodes.asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.id }
        .toSet()

    // Use the shortest root-to-node depth. It is deterministic for shared/repeated composables
    // and cannot spin forever when recursive composables form a cycle.
    val composeDepth = mutableMapOf<String, Int>()
    val queue = ArrayDeque<String>()
    composeNodeIds
        .filter { composeIncoming[it].orEmpty().none { edge -> edge.from in composeNodeIds } }
        .sorted()
        .forEach { root ->
            composeDepth[root] = 0
            queue.addLast(root)
        }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val nextDepth = (composeDepth[current] ?: 0) + 1
        composeOutgoing[current].orEmpty().forEach { edge ->
            if (edge.to !in composeNodeIds || edge.to in composeDepth) return@forEach
            composeDepth[edge.to] = nextDepth
            queue.addLast(edge.to)
        }
    }

    fun looksLikeViewBoundary(label: String): Boolean {
        val normalized = label.removeSuffix("*")
        return listOf("Screen", "Route", "Page", "Tab", "Content", "Pane", "View")
            .any { token -> normalized.contains(token, ignoreCase = true) }
    }

    val resolvedByNode = linkedMapOf<String, ResolvedCandidate>()
    candidates.forEach { runtime ->
        val node = graph.nodeByRuntimeKey(runtime.runtimeKey)
            ?.takeIf { it.kind == NodeKind.COMPOSABLE }
            ?: return@forEach
        val candidate = ResolvedCandidate(
            runtime = runtime,
            node = node,
            composeDepth = composeDepth[node.id] ?: 0,
            semanticView = looksLikeViewBoundary(node.label),
        )
        val previous = resolvedByNode[node.id]
        if (previous == null || runtime.visibleArea > previous.runtime.visibleArea) {
            resolvedByNode[node.id] = candidate
        }
    }
    val resolved = resolvedByNode.values.toList()
    if (resolved.isEmpty()) return false

    val largestArea = resolved.maxOf { it.runtime.visibleArea }.coerceAtLeast(1L)
    // A child must occupy a meaningful portion of the current window before it can displace
    // its screen/root. This keeps tiny buttons/cards from winning merely because they are deep.
    val meaningfulArea = max(1L, (largestArea * 35L) / 100L)
    val substantial = resolved.filter { it.runtime.visibleArea >= meaningfulArea }
        .ifEmpty { resolved }
    val semanticSubstantial = substantial.filter { it.semanticView }
    val pool = semanticSubstantial.ifEmpty { substantial }

    // Prefer the most specific visible view. For equal composition depth, prefer the larger
    // surface, then preserve the runtime's recency/order tie-breaker.
    val selected = pool.maxWithOrNull(
        compareBy<ResolvedCandidate> { it.composeDepth }
            .thenBy { it.runtime.visibleArea }
            .thenBy { -it.runtime.responseOrder }
    ) ?: return false

    activeGraph = graph
    selectionFocusActive = false
    selectNode(selected.node.id)
    SwingUtilities.invokeLater {
        canvas.centerNode(selected.node.id)
        val areaPercent = ((selected.runtime.visibleArea * 100.0) / largestArea.toDouble())
            .coerceIn(0.0, 100.0)
        hint.text = "Current phone UI → ${selected.node.label}"
        setCurrentUiDiagnostic(
            "Current UI matched the most specific substantial visible project composable: " +
                "${selected.node.label} (${selected.node.runtimeKey ?: selected.node.id}); " +
                "composeDepth=${selected.composeDepth}, visibleArea=${"%.1f".format(areaPercent)}% of largest candidate, " +
                "candidates=${resolved.size}, substantial=${substantial.size}."
        )
    }
    return true
}

/** Keep static graph tools visible; reveal runtime tooling only in Live mode. */


