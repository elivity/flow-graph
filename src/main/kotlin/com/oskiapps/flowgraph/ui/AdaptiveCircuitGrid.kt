package com.oskiapps.flowgraph.ui

/** Screen-space density cap for the circuit backdrop. Geometry and routing still use the
 * original CIRCUIT_GRID_SPACING; this only controls decorative grid painting.
 */
internal fun adaptiveCircuitGridStep(
    baseStep: Int,
    zoom: Double,
    modelExtent: Int,
    minScreenPixels: Double = 18.0,
    maxLines: Int = 160,
): Int {
    require(baseStep > 0 && zoom > 0.0 && zoom.isFinite())
    var spacing = baseStep.toLong()
    val extent = modelExtent.coerceAtLeast(0).toLong()
    while ((spacing * zoom < minScreenPixels || extent / spacing > maxLines) &&
        spacing <= Int.MAX_VALUE / 2L) {
        spacing *= 2
    }
    return spacing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Floor division keeps grid alignment stable while panning through negative coordinates. */
internal fun circuitGridPositions(start: Int, extent: Int, spacing: Int): Sequence<Int> = sequence {
    require(spacing > 0)
    val step = spacing.toLong()
    val first = Math.floorDiv(start.toLong(), step) * step
    val last = start.toLong() + extent.coerceAtLeast(0).toLong() + step
    var position = first
    while (position <= last) {
        if (position in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) yield(position.toInt())
        position += step
    }
}
