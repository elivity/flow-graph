package com.oskiapps.flowgraph.ui

import java.awt.Point
import java.awt.Rectangle
import java.util.PriorityQueue
import kotlin.math.abs

/** Routes an orthogonal dependency between visible ports without entering unrelated node bounds.
 * The graph model is not modified. Returns null only if no valid route could be constructed.
 */
internal fun routeAroundNodes(
    start: Point,
    end: Point,
    nodeBounds: Collection<Rectangle>,
    source: Rectangle,
    destination: Rectangle,
    clearance: Int = 8,
): List<Point>? {
    val minX = minOf(start.x, end.x) - 280
    val maxX = maxOf(start.x, end.x) + 280
    val minY = minOf(start.y, end.y) - 280
    val maxY = maxOf(start.y, end.y) + 280
    val obstacles = nodeBounds.asSequence()
        .filterNot { it === source || it === destination || it == source || it == destination }
        .map { Rectangle(it).apply { grow(clearance, clearance) } }
        .filter { it.x <= maxX && it.x + it.width >= minX && it.y <= maxY && it.y + it.height >= minY }
        .toList()
    val xs = (listOf(start.x, end.x, minX, maxX) + obstacles.flatMap {
        listOf(it.x - 2, it.x + it.width + 2)
    }).distinct().sorted()
    val ys = (listOf(start.y, end.y, minY, maxY) + obstacles.flatMap {
        listOf(it.y - 2, it.y + it.height + 2)
    }).distinct().sorted()
    val width = xs.size
    val height = ys.size
    fun id(x: Int, y: Int) = y * width + x
    fun blocked(a: Point, b: Point): Boolean = obstacles.any { r ->
        if (a.y == b.y) a.y in r.y..(r.y + r.height) &&
            maxOf(a.x, b.x) >= r.x && minOf(a.x, b.x) <= r.x + r.width
        else a.x in r.x..(r.x + r.width) &&
            maxOf(a.y, b.y) >= r.y && minOf(a.y, b.y) <= r.y + r.height
    }
    val startId = id(xs.indexOf(start.x), ys.indexOf(start.y))
    val endId = id(xs.indexOf(end.x), ys.indexOf(end.y))
    val total = width * height
    // Distinguish the direction of arrival to prefer fewer bends.
    val distance = LongArray(total * 3) { Long.MAX_VALUE }
    val previous = IntArray(total * 3) { -1 }
    val queue = PriorityQueue(compareBy<Pair<Long, Int>> { it.first })
    val first = startId * 3
    distance[first] = 0
    queue.add(0L to first)
    var finish = -1
    while (queue.isNotEmpty()) {
        val (cost, state) = queue.remove()
        if (cost != distance[state]) continue
        val position = state / 3
        if (position == endId) { finish = state; break }
        val x = position % width
        val y = position / width
        val point = Point(xs[x], ys[y])
        for ((nx, ny, direction) in arrayOf(
            Triple(x - 1, y, 1), Triple(x + 1, y, 1),
            Triple(x, y - 1, 2), Triple(x, y + 1, 2)
        )) {
            if (nx !in 0 until width || ny !in 0 until height) continue
            val nextPoint = Point(xs[nx], ys[ny])
            if (blocked(point, nextPoint)) continue
            val next = id(nx, ny) * 3 + direction
            val bend = if (state % 3 != 0 && state % 3 != direction) 24L else 0L
            val nextCost = cost + abs(nextPoint.x - point.x) + abs(nextPoint.y - point.y) + bend
            if (nextCost < distance[next]) {
                distance[next] = nextCost
                previous[next] = state
                queue.add(nextCost to next)
            }
        }
    }
    if (finish < 0) return null
    val reversed = mutableListOf<Point>()
    var current = finish
    while (current >= 0) {
        val position = current / 3
        reversed.add(Point(xs[position % width], ys[position / width]))
        current = previous[current]
    }
    val points = reversed.asReversed()
    return points.filterIndexed { i, point ->
        i == 0 || i == points.lastIndex || run {
            val before = points[i - 1]
            val after = points[i + 1]
            !(before.x == point.x && point.x == after.x || before.y == point.y && point.y == after.y)
        }
    }
}

/** Cache only within an unchanged visual layout. Dragging or re-layout invalidates it. */
internal fun GraphCanvas.nodeAvoidingRoute(edge: com.oskiapps.flowgraph.model.FlowEdge, a: Rectangle, b: Rectangle): List<Point> {
    val layout = cachedLayout
    if (obstacleRouteLayout !== layout) {
        obstacleRouteLayout = layout
        obstacleRouteCache.clear()
    }
    val key = "${edge.from}|${edge.to}|${a.x},${a.y},${a.width},${a.height}|${b.x},${b.y},${b.width},${b.height}"
    return obstacleRouteCache.getOrPut(key) {
        val line = edgeLineFromBounds(edge, a, b)
        val start = Point(line.x1, line.y1)
        val end = Point(line.x2, line.y2)
        val obstacles = layout?.bounds?.values ?: boundsById.values
        routeAroundNodes(start, end, obstacles, a, b)
            ?: listOf(start, end) // Never drop a dependency if routing cannot succeed.
    }
}
