package com.oskiapps.flowgraph.ui

import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val MIN_READABLE_LABEL_ZOOM = 0.85

/** Render the lens at a real readable scale, not at the canvas's zoomed-out detail level. */
internal fun magnifierRenderScale(canvasZoom: Double, twoNodeScale: Double): Double =
    maxOf(1.0, canvasZoom * 2.0, twoNodeScale * 2.0)

/** Graph model bounds projected to the original (unscaled) Swing canvas in device pixels. */
internal fun projectedNodeBounds(
    node: Rectangle, zoom: Double, paddingX: Int, paddingY: Int,
): Rectangle = Rectangle(
    (paddingX + node.x * zoom).roundToInt(),
    (paddingY + node.y * zoom).roundToInt(),
    ceil(node.width * zoom).toInt().coerceAtLeast(1),
    ceil(node.height * zoom).toInt().coerceAtLeast(1),
)

/** Keep a zoom-independent name tag near its node and inside the visible viewport. */
internal fun readableNameBounds(
    nodeOnScreen: Rectangle, labelWidth: Int, labelHeight: Int, visible: Rectangle,
): Rectangle? {
    if (!visible.intersects(nodeOnScreen) || visible.width <= 0 || visible.height <= 0) return null
    val w = labelWidth.coerceAtMost(visible.width)
    val h = labelHeight.coerceAtMost(visible.height)
    val x = (nodeOnScreen.centerX - w / 2.0).roundToInt()
        .coerceIn(visible.x, visible.x + visible.width - w)
    // Above the node so the name never obscures its IN/OUT ports or source icon.
    val y = (nodeOnScreen.y - h - 3)
        .coerceIn(visible.y, visible.y + visible.height - h)
    return Rectangle(x, y, w, h)
}

/** Small, bounded occupancy index: at extreme zoom many nodes project to the same pixels. */
internal class NameLabelOccupancy(private val rowHeight: Int = 20) {
    private val occupiedRows = HashMap<Int, MutableList<Rectangle>>()

    fun reserve(bounds: Rectangle): Boolean {
        val first = Math.floorDiv(bounds.y, rowHeight)
        val last = Math.floorDiv(bounds.y + bounds.height - 1, rowHeight)
        for (row in first..last) {
            if (occupiedRows[row]?.any { it.intersects(bounds) } == true) return false
        }
        for (row in first..last) occupiedRows.getOrPut(row) { mutableListOf() }.add(bounds)
        return true
    }
}
