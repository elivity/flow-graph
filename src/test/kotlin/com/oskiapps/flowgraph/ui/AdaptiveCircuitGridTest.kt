package com.oskiapps.flowgraph.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveCircuitGridTest {
    @Test
    fun `zoomed out 800px viewport paints only a bounded number of grid lines`() {
        val step = adaptiveCircuitGridStep(28, 0.001, 800_000)
        val count = circuitGridPositions(0, 800_000, step).count()
        assertTrue(count <= 164, "Expected at most 164 lines, got $count")
        assertTrue(step * 0.001 >= 18.0)
    }

    @Test
    fun `normal scale preserves original grid spacing`() {
        assertEquals(28, adaptiveCircuitGridStep(28, 1.0, 800))
    }

    @Test
    fun `pan does not shift the grid phase`() {
        val step = adaptiveCircuitGridStep(28, 0.1, 8000)
        val left = circuitGridPositions(-300, 8000, step).toList()
        val right = circuitGridPositions(-100, 8000, step).toList()
        assertTrue(left.intersect(right.toSet()).isNotEmpty())
        assertTrue((left + right).all { it % step == 0 })
    }

    @Test
    fun `grid positions are safe near integer boundary`() {
        val positions = circuitGridPositions(Int.MAX_VALUE - 100, 500, 28).toList()
        assertTrue(positions.size < 30)
        assertTrue(positions.all { it <= Int.MAX_VALUE })
    }
}
