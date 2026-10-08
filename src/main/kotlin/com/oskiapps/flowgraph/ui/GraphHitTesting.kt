package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.EDGE_HIT_DISTANCE
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

internal fun GraphCanvas.findEdgeAt(model: FlowGraph, point: Point): VisualEdge? {
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val sceneEdges = if (layout.visualEdges.isNotEmpty()) {
        layout.visualEdges.asSequence()
    } else {
        model.edges.asSequence().map { edge -> VisualEdge(edge.from, edge.to, edge) }
    }
    return sceneEdges
        .mapNotNull { visualEdge ->
            val a = layout.bounds[visualEdge.fromVisualId] ?: return@mapNotNull null
            val b = layout.bounds[visualEdge.toVisualId] ?: return@mapNotNull null
            val hasRepeatedVisualInstances = layout.sourceNodeIdByVisualId.any { (visualId, sourceId) ->
                visualId != sourceId
            }
            val points = if (!hasRepeatedVisualInstances) {
                edgeRoute(visualEdge.source) ?: run {
                    val line = edgeLineFromBounds(visualEdge.source, a, b)
                    listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
                }
            } else if (circuitViewEnabled) {
                buildSingleCircuitRoute(visualEdge.source, a, b, laneOffset = 0)
            } else {
                val line = edgeLineFromBounds(visualEdge.source, a, b)
                listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
            }
            visualEdge to pointToPolylineDistance(point.x.toDouble(), point.y.toDouble(), points)
        }
        .filter { (_, distance) -> distance <= EDGE_HIT_DISTANCE / zoom }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}



internal fun GraphCanvas.pointToPolylineDistance(px: Double, py: Double, points: List<Point>): Double {
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



internal fun GraphCanvas.pointToSegmentDistance(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    if (dx == 0.0 && dy == 0.0) return kotlin.math.hypot(px - x1, py - y1)
    val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    val nearestX = x1 + t * dx
    val nearestY = y1 + t * dy
    return kotlin.math.hypot(px - nearestX, py - nearestY)
}


