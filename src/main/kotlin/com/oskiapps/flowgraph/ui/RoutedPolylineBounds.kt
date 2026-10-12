package com.oskiapps.flowgraph.ui

import java.awt.Point
import java.awt.Rectangle

/** Actual routing bounds, including every detour; never infer these from endpoint nodes. */
internal fun routedPolylineBounds(points: List<Point>): Rectangle? {
    if (points.size < 2) return null
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    return Rectangle(minX, minY, (maxX - minX).coerceAtLeast(1), (maxY - minY).coerceAtLeast(1))
}
