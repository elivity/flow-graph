package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.CircuitRouteSeed
import com.oskiapps.flowgraph.ui.GraphCanvas.CircuitRoutingCache
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

internal fun GraphCanvas.edgeRoute(edge: FlowEdge): List<Point>? {
    if (circuitViewEnabled) {
        circuitRoutingCache?.routes?.get(edge)?.let { return it }
    }
    val a = boundsById[edge.from] ?: return null
    val b = boundsById[edge.to] ?: return null
    return if (circuitViewEnabled) {
        buildSingleCircuitRoute(edge, a, b, laneOffset = 0)
    } else {
        val line = edgeLineFromBounds(edge, a, b)
        listOf(Point(line.x1, line.y1), Point(line.x2, line.y2))
    }
}



internal fun GraphCanvas.buildCircuitRouting(model: FlowGraph, bounds: Map<String, Rectangle>): CircuitRoutingCache {
    val seeds = model.edges.mapNotNull { edge ->
        val a = bounds[edge.from] ?: return@mapNotNull null
        val b = bounds[edge.to] ?: return@mapNotNull null
        val seedPoints = buildSingleCircuitRoute(edge, a, b, laneOffset = 0)
        val trunkX = seedPoints.drop(1).dropLast(1).firstOrNull { it.x != seedPoints.first().x }?.x
            ?: ((seedPoints.first().x + seedPoints.last().x) / 2)
        CircuitRouteSeed(
            edge = edge,
            points = seedPoints,
            preferredTrunkX = snapCircuitCoordinate(trunkX),
            meanY = ((seedPoints.first().y + seedPoints.last().y) / 2),
        )
    }

    val grouped = seeds.groupBy { it.preferredTrunkX }
    val routes = linkedMapOf<FlowEdge, List<Point>>()
    grouped.toSortedMap().forEach { (trunkBase, group) ->
        val sorted = group.sortedBy { it.meanY }
        val centeredOffsets = centeredLaneOffsets(sorted.size)
        sorted.forEachIndexed { index, seed ->
            val laneOffset = centeredOffsets[index] * CIRCUIT_LANE_SPACING
            val a = bounds[seed.edge.from] ?: return@forEachIndexed
            val b = bounds[seed.edge.to] ?: return@forEachIndexed
            val candidates = (0..14).flatMap { step ->
                if (step == 0) listOf(trunkBase + laneOffset)
                else listOf(
                    trunkBase + laneOffset + step * CIRCUIT_GRID_SPACING,
                    trunkBase + laneOffset - step * CIRCUIT_GRID_SPACING,
                )
            }
            val route = candidates.map { trunk ->
                buildSingleCircuitRoute(seed.edge, a, b, laneOffset = 0,
                    forcedTrunkX = trunk)
            }.minByOrNull { candidate ->
                circuitRoutePenalty(candidate, seed.edge, bounds, routes.values)
            } ?: seed.points
            routes[seed.edge] = route
        }
    }

    return CircuitRoutingCache(routes = routes, junctions = computeCircuitJunctions(routes))
}

/** Penalize crossing unrelated nodes and reusing an occupied wire segment. */



internal fun GraphCanvas.circuitRoutePenalty(
    route: List<Point>,
    edge: FlowEdge,
    bounds: Map<String, Rectangle>,
    existing: Collection<List<Point>>,
): Long {
    var score = 0L
    val obstacles = bounds.filterKeys { it != edge.from && it != edge.to }
        .values.map { Rectangle(it).apply { grow(12, 12) } }
    for ((a, b) in route.zipWithNext()) {
        val segment = Rectangle(
            minOf(a.x, b.x), minOf(a.y, b.y),
            kotlin.math.abs(b.x - a.x).coerceAtLeast(1),
            kotlin.math.abs(b.y - a.y).coerceAtLeast(1),
        )
        score += obstacles.count { it.intersects(segment) } * 100_000L
        for (wire in existing) {
            for ((c, d) in wire.zipWithNext()) {
                if (a.x == b.x && c.x == d.x && a.x == c.x &&
                    maxOf(minOf(a.y, b.y), minOf(c.y, d.y)) <
                    minOf(maxOf(a.y, b.y), maxOf(c.y, d.y))) score += 5000
                if (a.y == b.y && c.y == d.y && a.y == c.y &&
                    maxOf(minOf(a.x, b.x), minOf(c.x, d.x)) <
                    minOf(maxOf(a.x, b.x), maxOf(c.x, d.x))) score += 5000
                // Distinct orthogonal wires should not cross without a junction.
                val horizontal = if (a.y == b.y) a to b else if (c.y == d.y) c to d else null
                val vertical = if (a.x == b.x) a to b else if (c.x == d.x) c to d else null
                if (horizontal != null && vertical != null &&
                    vertical.first.x in (minOf(horizontal.first.x, horizontal.second.x) + 1)
                        until maxOf(horizontal.first.x, horizontal.second.x) &&
                    horizontal.first.y in (minOf(vertical.first.y, vertical.second.y) + 1)
                        until maxOf(vertical.first.y, vertical.second.y)) score += 1500
            }
        }
        score += (kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)).toLong()
    }
    return score
}



internal fun GraphCanvas.centeredLaneOffsets(count: Int): List<Int> {
    if (count <= 1) return listOf(0)
    val base = mutableListOf<Int>()
    if (count % 2 == 1) base += 0
    var step = 1
    while (base.size < count) {
        base += -step
        if (base.size < count) base += step
        step++
    }
    return base
}



internal fun GraphCanvas.buildSingleCircuitRoute(
    edge: FlowEdge,
    a: Rectangle,
    b: Rectangle,
    laneOffset: Int,
    forcedTrunkX: Int? = null,
): List<Point> {
    val base = edgeLineFromBounds(edge, a, b)
    val dir = if (base.x2 >= base.x1) 1 else -1
    val hash = kotlin.math.abs((edge.from + "→" + edge.to + (edge.label ?: edge.kind.name)).hashCode())
    val startStub = 16 + (hash % 3) * 6
    val endStub = 16 + ((hash / 3) % 3) * 6
    val start = Point(base.x1, snapCircuitCoordinate(base.y1, vertical = true))
    val end = Point(base.x2, snapCircuitCoordinate(base.y2, vertical = true))
    val startOut = Point(base.x1 + dir * startStub, start.y)
    val endIn = Point(base.x2 - dir * endStub, end.y)

    val sameColumn = kotlin.math.abs(a.centerX - b.centerX) < ((a.width + b.width) / 2 + 28)
    val limitedGap = if (dir > 0) endIn.x - startOut.x < 56 else startOut.x - endIn.x < 56
    val baseTrunk = when {
        sameColumn || limitedGap -> {
            val outside = if (dir > 0) maxOf(a.x + a.width, b.x + b.width) + 34 else minOf(a.x, b.x) - 34
            snapCircuitCoordinate(outside)
        }
        else -> snapCircuitCoordinate((startOut.x + endIn.x) / 2)
    }
    val routeX = (forcedTrunkX ?: baseTrunk) + laneOffset

    val points = mutableListOf(start, startOut)
    if (routeX != startOut.x) points += Point(routeX, startOut.y)
    if (end.y != start.y) points += Point(routeX, end.y)
    if (endIn.x != routeX || endIn.y != end.y) points += endIn
    points += end
    return simplifyPolyline(points)
}



internal fun GraphCanvas.snapCircuitCoordinate(value: Int, vertical: Boolean = false): Int {
    val spacing = if (vertical) CIRCUIT_GRID_SPACING / 2 else CIRCUIT_GRID_SPACING
    val base = if (vertical) spacing / 2 else 0
    val relative = value - base
    return ((relative + spacing / 2) / spacing) * spacing + base
}



internal fun GraphCanvas.computeCircuitJunctions(routes: Map<FlowEdge, List<Point>>): Set<Point> {
    data class Segment(val edge: FlowEdge, val a: Point, val b: Point)
    fun isHorizontal(s: Segment) = s.a.y == s.b.y
    fun isVertical(s: Segment) = s.a.x == s.b.x
    fun minX(s: Segment) = minOf(s.a.x, s.b.x)
    fun maxX(s: Segment) = maxOf(s.a.x, s.b.x)
    fun minY(s: Segment) = minOf(s.a.y, s.b.y)
    fun maxY(s: Segment) = maxOf(s.a.y, s.b.y)
    fun pointOnSegment(p: Point, s: Segment): Boolean = when {
        isHorizontal(s) -> p.y == s.a.y && p.x in minX(s)..maxX(s)
        isVertical(s) -> p.x == s.a.x && p.y in minY(s)..maxY(s)
        else -> false
    }

    val segments = routes.flatMap { (edge, points) ->
        points.zipWithNext().map { (a, b) -> Segment(edge, a, b) }
    }
    val junctions = linkedSetOf<Point>()
    val pointUse = mutableMapOf<Point, MutableSet<FlowEdge>>()
    routes.forEach { (edge, points) ->
        points.drop(1).dropLast(1).forEach { point ->
            pointUse.getOrPut(Point(point)) { linkedSetOf() } += edge
        }
    }
    pointUse.filterValues { it.size >= 2 }.keys.forEach { junctions += it }

    for (i in segments.indices) {
        for (j in i + 1 until segments.size) {
            val first = segments[i]
            val second = segments[j]
            if (first.edge == second.edge) continue
            if (isHorizontal(first) && isVertical(second)) {
                val p = Point(second.a.x, first.a.y)
                if (p.x in minX(first)..maxX(first) && p.y in minY(second)..maxY(second)) junctions += p
            } else if (isVertical(first) && isHorizontal(second)) {
                val p = Point(first.a.x, second.a.y)
                if (p.x in minX(second)..maxX(second) && p.y in minY(first)..maxY(first)) junctions += p
            }
        }
    }

    routes.forEach { (edge, points) ->
        points.drop(1).dropLast(1).forEach { point ->
            segments.filter { it.edge != edge && pointOnSegment(point, it) }.forEach { junctions += Point(point) }
        }
    }
    return junctions
}



internal fun GraphCanvas.simplifyPolyline(points: List<Point>): List<Point> {
    if (points.size <= 2) return points
    val simplified = mutableListOf<Point>()
    points.forEach { point ->
        if (simplified.isEmpty() || simplified.last() != point) {
            simplified += Point(point)
        }
    }
    var index = 1
    while (index < simplified.lastIndex) {
        val a = simplified[index - 1]
        val b = simplified[index]
        val c = simplified[index + 1]
        val collinear = (a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)
        if (collinear) simplified.removeAt(index) else index++
    }
    return simplified
}


