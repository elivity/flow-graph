package com.oskiapps.flowgraph.ui

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutedEdgeHitTestingTest {
    @Test
    fun `bend is clickable even when original straight edge is far away`() {
        val route = listOf(Point(0, 0), Point(0, 100), Point(200, 100), Point(200, 0))
        assertEquals(0.0, routedPolylineDistance(100.0, 100.0, route), 0.001)
        assertTrue(routedPolylineDistance(100.0, 0.0, route) > 12.0)
    }

    @Test
    fun `all segments including vertical bends are hit tested`() {
        val route = listOf(Point(0, 0), Point(0, 80), Point(120, 80))
        assertEquals(3.0, routedPolylineDistance(3.0, 40.0, route), 0.001)
        assertEquals(4.0, routedPolylineDistance(60.0, 84.0, route), 0.001)
    }
}
