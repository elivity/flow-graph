package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.HISTORY_TIME_FORMAT
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.HISTORY_EDGE_WINDOW_MS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_COMPOSE_INSTANCES_PER_NODE
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_HISTORY_DETAIL_EVENTS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_REPLAY_EVENTS

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

internal fun FlowGraphPanel.replayRuntimeHistory() {
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



internal fun FlowGraphPanel.onTimelineScrubbed(cursorMillis: Long?) {
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



internal fun FlowGraphPanel.buildHistoricalOverlay(
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



internal fun FlowGraphPanel.updateHistoricalDetails(cursorMillis: Long) {
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


