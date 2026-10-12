package com.oskiapps.flowgraph.ui

import java.awt.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelZoomGestureTest {
    private val first = WheelZoomAnchor(120.5, 240.25, Point(160, 90), Point(500, 370))
    private val other = WheelZoomAnchor(900.0, 500.0, Point(220, 130), Point(560, 410))

    @Test
    fun `rapid wheel events retain initial anchor even when later event positions drift`() {
        val gesture = WheelZoomGesture()
        gesture.accept(1000, 0.12, first)
        gesture.accept(1012, 0.15, other)
        gesture.accept(1024, 0.18, other)

        assertEquals(first, gesture.anchor)
        assertEquals(0.45, gesture.takeRemainingExponent(), 0.0000001)
    }

    @Test
    fun `wheel bursts preserve log zoom increments beyond one visual frame`() {
        val gesture = WheelZoomGesture()
        repeat(40) { gesture.accept(1000L + it * 3L, 0.09, first) }
        var total = 0.0
        var frames = 0
        while (gesture.hasPending()) {
            val step = gesture.takeFrameExponent()
            assertTrue(abs(step) <= 0.55 + 0.000001)
            total += step
            frames++
            assertTrue(frames < 30)
        }
        assertEquals(3.6, total, 0.0000001)
        assertFalse(gesture.hasPending())
    }

    @Test
    fun `new gesture captures new cursor only after inactivity`() {
        val gesture = WheelZoomGesture()
        gesture.accept(1000, 0.1, first)
        assertTrue(gesture.continuesAt(1240))
        assertFalse(gesture.continuesAt(1241))
        gesture.accept(1300, -0.1, other)
        assertEquals(other, gesture.anchor)
        assertEquals(-0.1, gesture.pendingExponent, 0.0000001)
    }

    @Test
    fun `camera translation keeps model point at same cursor across zoom levels`() {
        val world = first.modelX
        val cursor = first.viewportPoint.x
        val padding = 800
        listOf(0.02, 0.1, 0.65, 1.0, 2.3, 5.0, 25.0).forEach { zoom ->
            val scrollX = anchoredViewOffset(padding, world, zoom, cursor)
            val actualCursorX = padding + world * zoom - scrollX
            assertTrue(abs(actualCursorX - cursor) <= 0.5)
        }
    }

    @Test
    fun `screen anchored zoom compensates when viewport moves during layout`() {
        val screenCursorY = 410
        val worldY = 234.5
        val padding = 900
        val zoom = 1.75
        val oldViewportTop = 140
        val newViewportTop = 185
        val oldScrollY = anchoredViewOffset(padding, worldY, zoom, screenCursorY - oldViewportTop)
        val newScrollY = anchoredViewOffset(padding, worldY, zoom, screenCursorY - newViewportTop)

        assertEquals(newViewportTop - oldViewportTop, newScrollY - oldScrollY)
        val actualScreenCursor = newViewportTop + padding + worldY * zoom - newScrollY
        assertTrue(abs(actualScreenCursor - screenCursorY) <= 0.5)
    }

    @Test
    fun `cancel removes stale anchor and wheel delta`() {
        val gesture = WheelZoomGesture()
        gesture.accept(1000, 1.4, first)
        gesture.clear()
        assertEquals(null, gesture.anchor)
        assertFalse(gesture.hasPending())
        assertFalse(gesture.continuesAt(1001))
    }
}
