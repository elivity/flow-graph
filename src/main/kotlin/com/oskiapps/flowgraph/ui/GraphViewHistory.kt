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

internal fun FlowGraphPanel.rememberGraphView() {
    if (fullGraph == null) return
    navigationHistory.push(
        GraphViewSnapshot(
            activeGraph, selectedNodeId, selectionFocusActive,
            detailUpstreamDepth, detailDownstreamDepth, selectedField(), detailFocusNodeId
        )
    )
    backToGraph.isEnabled = true
}



internal fun FlowGraphPanel.navigateBack() {
    val previous = navigationHistory.back() ?: return
    activeGraph = previous.activeGraph ?: fullGraph
    selectedNodeId = previous.nodeId
    detailFocusNodeId = previous.focusNodeId
    selectionFocusActive = previous.focused
    detailUpstreamDepth = previous.upstreamDepth
    detailDownstreamDepth = previous.downstreamDepth
    if (previous.nodeId != null) {
        updateFieldFocusChoices(previous.nodeId)
        fieldFocus.selectedItem = previous.field ?: "All fields"
    } else {
        fieldFocus.removeAllItems()
        fieldFocus.addItem("All fields")
        fieldFocus.isEnabled = false
    }
    canvas.selectedId = previous.nodeId
    backToGraph.isEnabled = navigationHistory.canGoBack
    renderActive(autoFit = false)
    updateComposePreview()
    hint.text = "Previous graph view • Back follows navigation history • Show all resets"
}

/** Independent causal radius for the focused detail map. Null means all levels. */



internal fun FlowGraphPanel.restoreFullGraph() {
    navigationHistory.clear()
    activeGraph = fullGraph ?: return
    selectionFocusActive = false
    selectedNodeId = null
    detailFocusNodeId = null
    backToGraph.isEnabled = false
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

/**
 * Semantic visibility changes apply to both the full project overview and focused detail views.
 * They are layout changes, not paint-only toggles: throw away cached/frozen geometry, rebuild
 * the visible graph from the checked layers, pack it from scratch, and fit the result.
 */


