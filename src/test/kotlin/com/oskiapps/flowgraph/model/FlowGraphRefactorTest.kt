package com.oskiapps.flowgraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression coverage for model operations extracted from FlowGraph. */
class FlowGraphRefactorTest {
    private val sample = FlowGraph(
        rootLabel = "engine",
        nodes = listOf(
            FlowNode("engine", "EngineState", "", NodeKind.STATE, null),
            FlowNode("spark", "spark", "", NodeKind.FIELD, null),
            FlowNode("collector", "collectAsState", "", NodeKind.COLLECTOR, null),
        ),
        edges = listOf(
            FlowEdge("spark", "engine", EdgeKind.FIELD_OF),
            FlowEdge("engine", "collector", EdgeKind.COLLECTS),
        ),
    )

    @Test
    fun `node lookup returns matching node and null for missing`() {
        assertEquals("EngineState", sample.node("engine")?.label)
        assertNull(sample.node("unknown"))
    }

    @Test
    fun `incoming edges preserve causal direction`() {
        assertEquals(listOf("spark"), sample.incomingEdges("engine").map { it.from })
        assertTrue(sample.incomingEdges("spark").isEmpty())
    }

    @Test
    fun `fields for state include only connected field nodes`() {
        assertEquals(listOf("spark"), sample.fieldsForState("engine").map { it.label })
    }
}
