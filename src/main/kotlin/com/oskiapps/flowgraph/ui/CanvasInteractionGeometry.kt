package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
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

internal fun GraphCanvas.isInteractiveAt(canvasPoint: Point): Boolean {
    val model = graph ?: return false
    val point = toModelPoint(canvasPoint)
    if (boundsById.values.any { it.contains(point) }) return true
    return findEdgeAt(model, point) != null
}



internal fun GraphCanvas.updateCursor(canvasPoint: Point) {
    if (panStartOnScreen != null) {
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        return
    }
    val point = toModelPoint(canvasPoint)
    val sourceHover = boundsById.entries.any { (visualId, bounds) ->
        bounds.contains(point) && sourceIconRect(bounds).contains(point) &&
            graph?.let { model ->
                val layout = cachedLayout ?: return@let false
                model.node(sourceNodeId(layout, visualId))?.source != null
            } == true
    }
    cursor = if (sourceHover || isInteractiveAt(canvasPoint)) {
        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    } else {
        Cursor.getDefaultCursor()
    }
}



internal fun GraphCanvas.sourceNodeId(layout: NodeLayout, visualId: String): String =
    layout.sourceNodeIdByVisualId[visualId] ?: visualId



internal fun GraphCanvas.firstBoundsForSource(layout: NodeLayout, sourceId: String): Rectangle? =
    layout.bounds[sourceId] ?: layout.bounds.entries.firstOrNull { (visualId, _) ->
        sourceNodeId(layout, visualId) == sourceId
    }?.value


