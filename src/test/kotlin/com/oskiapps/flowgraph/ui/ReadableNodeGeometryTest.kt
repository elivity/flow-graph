package com.oskiapps.flowgraph.ui

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadableNodeGeometryTest {
    @Test
    fun `magnifier renders full detail even at extreme main zoom out`() {
        assertTrue(magnifierRenderScale(0.001, 0.5) >= 1.0)
        assertEquals(3.0, magnifierRenderScale(1.5, 0.5))
        assertEquals(1.6, magnifierRenderScale(0.1, 0.8))
    }

    @Test
    fun `screen space label stays readable at arbitrary canvas zoom`() {
        val viewport = Rectangle(0, 0, 800, 600)
        val graphNode = Rectangle(50_000, 35_000, 250, 76)
        val projected = projectedNodeBounds(graphNode, 0.001, 100, 100)
        val label = assertNotNull(readableNameBounds(projected, 130, 17, viewport))
        assertEquals(130, label.width)
        assertEquals(17, label.height)
        assertTrue(viewport.contains(label))
        assertTrue(label.y + label.height < projected.y)
    }

    @Test
    fun `offscreen node does not emit a label`() {
        val bounds = projectedNodeBounds(Rectangle(40_000, 0, 250, 76), 0.1, 0, 0)
        assertEquals(null, readableNameBounds(bounds, 90, 17, Rectangle(0, 0, 800, 600)))
    }

    @Test
    fun `dense labels never overlap each other`() {
        val occupancy = NameLabelOccupancy()
        assertTrue(occupancy.reserve(Rectangle(10, 10, 120, 17)))
        assertFalse(occupancy.reserve(Rectangle(100, 12, 120, 17)))
        assertTrue(occupancy.reserve(Rectangle(140, 10, 120, 17)))
        assertTrue(occupancy.reserve(Rectangle(10, 31, 120, 17)))
    }
}
