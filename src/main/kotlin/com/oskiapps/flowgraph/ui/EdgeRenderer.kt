package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.EdgeDirection
import com.oskiapps.flowgraph.ui.GraphCanvas.EdgeLine
import com.oskiapps.flowgraph.ui.GraphCanvas.EdgePathStyle
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

internal fun GraphCanvas.drawArrow(
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
    val hasRepeatedVisualInstances = cachedLayout?.sourceNodeIdByVisualId?.any { (visualId, sourceId) ->
        visualId != sourceId
    } == true
    val points = if (!hasRepeatedVisualInstances) {
        edgeRoute(edge) ?: run {
            val line = edgeLineFromBounds(edge, a, b)
            listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
        }
    } else if (circuitViewEnabled) {
        buildSingleCircuitRoute(edge, a, b, laneOffset = 0)
    } else {
        val line = edgeLineFromBounds(edge, a, b)
        listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
    }
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



internal fun GraphCanvas.drawPolyline(g: Graphics2D, points: List<Point>) {
    for (index in 0 until points.lastIndex) {
        val a = points[index]
        val b = points[index + 1]
        g.drawLine(a.x, a.y, b.x, b.y)
    }
}



internal fun GraphCanvas.drawArrowHead(g: Graphics2D, points: List<Point>, size: Int) {
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



internal fun GraphCanvas.polylineMidPoint(points: List<Point>): Point {
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



internal fun GraphCanvas.drawCircuitJunctions(g: Graphics2D, junctions: Set<Point>) {
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



internal fun GraphCanvas.edgeLineFromBounds(edge: FlowEdge, a: Rectangle, b: Rectangle): EdgeLine {
    val right = b.centerX >= a.centerX
    return EdgeLine(
        edge = edge,
        x1 = if (right) a.x + a.width else a.x,
        y1 = a.y + a.height / 2,
        x2 = if (right) b.x else b.x + b.width,
        y2 = b.y + b.height / 2,
    )
}



internal fun GraphCanvas.edgeDisplayLabel(edge: FlowEdge): String = edge.label ?: run {
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


