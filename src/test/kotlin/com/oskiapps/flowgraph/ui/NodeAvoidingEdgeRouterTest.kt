package com.oskiapps.flowgraph.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodeAvoidingEdgeRouterTest {
    @Test
    fun `routing avoids an unrelated node while preserving visible endpoints`() {
        val source = Rectangle(0, 0, 80, 60)
        val blocker = Rectangle(110, -30, 90, 120)
        val destination = Rectangle(250, 0, 80, 60)
        val path = assertNotNull(routeAroundNodes(
            Point(80, 30), Point(250, 30), listOf(source, blocker, destination), source, destination
        ))
        assertEquals(Point(80, 30), path.first())
        assertEquals(Point(250, 30), path.last())
        path.zipWithNext().forEach { (a, b) ->
            assertTrue(a.x == b.x || a.y == b.y)
            assertTrue(!blocker.intersectsLine(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()))
        }
    }

    @Test
    fun `unobstructed dependency stays visible`() {
        val source = Rectangle(0, 0, 80, 60)
        val destination = Rectangle(220, 0, 80, 60)
        val path = assertNotNull(routeAroundNodes(
            Point(80, 30), Point(220, 30), listOf(source, destination), source, destination
        ))
        assertEquals(listOf(Point(80, 30), Point(220, 30)), path)
    }
}
