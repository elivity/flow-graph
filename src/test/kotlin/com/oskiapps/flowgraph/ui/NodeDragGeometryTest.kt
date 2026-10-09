package com.oskiapps.flowgraph.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeDragGeometryTest {
    @Test
    fun `relocating node preserves its size and original bounds`() {
        val original = Rectangle(30, 40, 250, 76)
        val moved = placedNodeBounds(original, Point(190, 210))
        assertEquals(Rectangle(190, 210, 250, 76), moved)
        assertEquals(Rectangle(30, 40, 250, 76), original)
    }
}
