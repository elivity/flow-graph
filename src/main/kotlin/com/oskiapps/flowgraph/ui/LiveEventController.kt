package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.HISTORY_EDGE_WINDOW_MS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.LIVE_EDGE_WINDOW_NANOS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.LIVE_DETAILS_REFRESH_MS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.LIVE_DIAGNOSTICS_REFRESH_MS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_COMPOSE_INSTANCES_PER_NODE
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_RUNTIME_EVENTS_PER_FLUSH
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.RECENT_FILTER_REFRESH_MS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.RUNTIME_FLUSH_BUDGET_NANOS

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

internal fun FlowGraphPanel.flushRuntimeEvents() {
    if (!liveTrace.isSelected) {
        runtimeEventBuffer.clear()
        return
    }
    var processed = 0
    val batch = ArrayList<RuntimeTraceEvent>(MAX_RUNTIME_EVENTS_PER_FLUSH)
    val deadlineNanos = System.nanoTime() + RUNTIME_FLUSH_BUDGET_NANOS
    while (processed < MAX_RUNTIME_EVENTS_PER_FLUSH && (processed == 0 || System.nanoTime() < deadlineNanos)) {
        val event = runtimeEventBuffer.poll() ?: break
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
    if (liveTrace.isSelected && recentOnly.isSelected && historyCursorMillis == null) refreshRecentActivityFilterIfNeeded()
    if (liveTrace.isSelected && runtimeBranchesOnly.isSelected && historyCursorMillis == null && selectedNodeId == null) {
        val observedIds = runtimeObservedNodeIds()
        if (observedIds != lastRuntimeBranchFilterIds) {
            lastRuntimeBranchFilterIds = observedIds
            // Structural membership changed. Rebuild layout, but never auto-fit on a live event:
            // the camera should stay where the user is inspecting while the active branch grows.
            canvas.clearFrozenLayout()
            renderActive(autoFit = false)
        }
    }

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

/** Every node for which the current live session has concrete runtime evidence. */



internal fun FlowGraphPanel.runtimeObservedNodeIds(): Set<String> {
    val overlay = runtimeOverlay
    return linkedSetOf<String>().apply {
        addAll(overlay.nodeCounts.keys)
        addAll(overlay.changeCounts.keys)
        addAll(overlay.readCounts.keys)
        addAll(overlay.deliveryCounts.keys)
        addAll(overlay.collectCounts.keys)
        addAll(overlay.emitRequestCounts.keys)
        addAll(overlay.lastEventByNode.keys)
        addAll(overlay.composeInstanceEventsByNode.keys)
        addAll(overlay.lastActivityAtMillis.keys)
        overlay.edgeCounts.keys.forEach { (from, to) ->
            add(from)
            add(to)
        }
    }
}



internal fun FlowGraphPanel.runtimeObservedEdges(): Set<Pair<String, String>> =
    runtimeOverlay.edgeCounts.filterValues { it > 0 }.keys



internal fun FlowGraphPanel.recentActiveNodeIds(now: Long = System.currentTimeMillis()): Set<String> {
    val historical = historyCursorMillis != null
    val overlay = displayedRuntimeOverlay()
    return overlay.lastActivityAtMillis.asSequence()
        .filter { (_, at) -> historical || now - at <= RUNTIME_ACTIVITY_COOLDOWN_MS }
        .mapTo(linkedSetOf()) { it.key }
}



internal fun FlowGraphPanel.refreshRecentActivityFilterIfNeeded(
    now: Long = System.currentTimeMillis(),
    force: Boolean = false,
) {
    if (!liveTrace.isSelected || !recentOnly.isSelected) return
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



internal fun FlowGraphPanel.onRuntimeEvent(
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


