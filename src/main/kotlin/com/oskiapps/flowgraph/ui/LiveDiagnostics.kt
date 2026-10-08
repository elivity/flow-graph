package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_UNMATCHED_KEYS

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

internal fun FlowGraphPanel.clearRuntimeOverlay() {
    runtimeOverlay = RuntimeOverlay.EMPTY
    historicalRuntimeOverlay = null
    historyCursorMillis = null
    recentRuntimeEmissionNanos.clear()
    runtimeEventBuffer.clear()
    runtimeEventBuffer.resetDropped()
    liveTraceService.clearRecentEvents()
    runtimeTimeline.clearHistory()
    resetRuntimeMatchingDiagnostics()
    clearRuntime.isEnabled = false
    canvas.runtimeOverlay = RuntimeOverlay.EMPTY
    canvas.runtimeReferenceMillis = null
    lastRecentFilterIds = emptySet()
    lastRecentFilterRefreshMillis = 0L
    lastRuntimeBranchFilterIds = emptySet()
    if (liveTrace.isSelected && (recentOnly.isSelected || runtimeBranchesOnly.isSelected)) renderActive(autoFit = false) else canvas.repaint()
    updateLiveTraceDiagnostics()
}



internal fun FlowGraphPanel.resetRuntimeMatchingDiagnostics() {
    runtimeEventsSeen = 0
    matchedRuntimeEvents = 0
    unmatchedRuntimeEvents = 0
    uiInteractionEventsSeen = 0
    unmatchedRuntimeKeys.clear()
}



internal fun FlowGraphPanel.updateLiveTraceDiagnostics() {
    val status = liveTransportStatus
    liveDiagnostics.isVisible = liveTrace.isSelected && liveInfo.isSelected

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
        " • skipped outside graph=${status.filteredProfilerEvents} • UI queue=${runtimeEventBuffer.pending} • dropped=${runtimeEventBuffer.droppedCount}"
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

    val currentUiLine = currentUiDiagnostic?.let { "Current UI:\n$it" }
    liveDiagnostics.text = listOfNotNull(
        serverLine,
        clientLine,
        eventLine,
        pauseLine,
        deepRuntimeLine,
        currentUiLine,
        unmatchedLine,
        transportLine,
    ).joinToString("\n")
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



internal fun FlowGraphPanel.pauseRuntimeRecordingForHistory() {
    liveTraceService.pauseRecording()

    // Process everything already handed to the UI listener before the pause. The queue is
    // bounded, so this remains finite and avoids losing pre-pause overlay counts.
    while (true) {
        val event = runtimeEventBuffer.poll() ?: break
        onRuntimeEvent(event, updateDetails = false, refreshUi = false, markActivity = true)
    }

    // The service retains a larger authoritative history than the bounded UI queue, so rebuild
    // the profiler strip from it at the exact pause boundary. No pruning occurs while paused.
    runtimeTimeline.setEvents(liveTraceService.recentEvents(RuntimeTimelinePanel.MAX_EVENTS))
    liveTransportStatus = liveTraceService.status()
    canvas.runtimeOverlay = displayedRuntimeOverlay()
    clearRuntime.isEnabled = liveTraceService.recentEvents(1).isNotEmpty()
    if (liveTrace.isSelected && runtimeBranchesOnly.isSelected && selectedNodeId == null) {
        val observedIds = runtimeObservedNodeIds()
        if (observedIds != lastRuntimeBranchFilterIds) {
            lastRuntimeBranchFilterIds = observedIds
            canvas.clearFrozenLayout()
            renderActive(autoFit = false)
        } else {
            canvas.repaint()
        }
    } else {
        canvas.repaint()
    }
    updateLiveTraceDiagnostics()
}


