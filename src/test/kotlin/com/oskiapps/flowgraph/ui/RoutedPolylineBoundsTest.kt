package com.oskiapps.flowgraph.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutedPolylineBoundsTest {
    @Test
    fun `a detour remains in view even when endpoint bounding box is outside`() {
        val route = listOf(Point(0, 0), Point(0, 200), Point(220, 200), Point(220, 0))
        val visible = Rectangle(90, 195, 50, 25)
        assertTrue(routedPolylineBounds(route)!!.intersects(visible))
        assertFalse(Rectangle(0, 0, 220, 50).intersects(visible))
    }

    @Test
    fun `empty and single point routes have no drawable bounds`() {
        assertNull(routedPolylineBounds(emptyList()))
        assertNull(routedPolylineBounds(listOf(Point(10, 10))))
    }

    @Test
    fun `orthogonal straight segment has nonempty bounds`() {
        assertEquals(Rectangle(10, 20, 100, 1), routedPolylineBounds(listOf(Point(10,20), Point(110,20))))
    }
}
