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

internal fun FlowGraphPanel.resetDetailExpansionDepths() {
    val initial = selectedDepth()
    detailUpstreamDepth = initial
    detailDownstreamDepth = initial
}



internal fun FlowGraphPanel.depthLabel(value: Int?): String = value?.toString() ?: "all"



internal fun FlowGraphPanel.focusedDetailGraph(projected: FlowGraph, field: String?): FlowGraph {
    val id = detailFocusNodeId ?: selectedNodeId ?: return projected
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



internal fun FlowGraphPanel.expandDetail(upstream: Boolean) {
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



internal fun FlowGraphPanel.updateDetailExpansionControls(projected: FlowGraph, field: String?) {
    val id = selectedNodeId
    val show = selectionFocusActive && id != null && projected.node(id) != null
    expandLeft.isVisible = show
    expandRight.isVisible = show
    if (!show) {
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



internal fun FlowGraphPanel.displayedRuntimeOverlay(): RuntimeOverlay = historicalRuntimeOverlay ?: runtimeOverlay



internal fun FlowGraphPanel.selectedDepth(): Int? = when (depth.selectedItem?.toString()) {
    "1" -> 1
    "2" -> 2
    "3" -> 3
    else -> null
}



internal fun FlowGraphPanel.selectedField(): String? =
    fieldFocus.selectedItem?.toString()?.takeUnless { it == "All fields" }


