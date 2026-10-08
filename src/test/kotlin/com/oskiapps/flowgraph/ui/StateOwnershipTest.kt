package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.RuntimeTraceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StateOwnershipTest {
    private fun snapshot(id: String) = GraphViewSnapshot(null, id, true, 2, 3, null, id)
    private fun event(key: String) = RuntimeTraceEvent(0L, key, "emit", null, null)

    @Test fun `history restores previous views in order`() {
        val history = GraphNavigationHistory(2)
        listOf("one", "two", "three").forEach { history.push(snapshot(it)) }
        assertEquals(2, history.size)
        assertEquals("three", history.back()?.nodeId)
        assertEquals("two", history.back()?.nodeId)
        assertNull(history.back())
        assertFalse(history.canGoBack)
    }

    @Test fun `history clear resets navigation`() {
        val history = GraphNavigationHistory()
        history.push(snapshot("engine"))
        history.clear()
        assertNull(history.back())
    }

    @Test fun `bounded event buffer discards oldest and drains in order`() {
        val buffer = RuntimeEventBuffer(2)
        listOf("a", "b", "c").forEach { buffer.offer(event(it)) }
        assertEquals(1L, buffer.droppedCount)
        assertEquals("b", buffer.poll()?.stateKey)
        assertEquals("c", buffer.poll()?.stateKey)
        assertEquals(0, buffer.pending)
        assertNull(buffer.poll())
    }

    @Test fun `event buffer clear removes previous live session`() {
        val buffer = RuntimeEventBuffer(2)
        buffer.offer(event("old"))
        buffer.clear()
        assertEquals(0, buffer.pending)
        buffer.offer(event("new"))
        assertEquals("new", buffer.poll()?.stateKey)
    }
}
