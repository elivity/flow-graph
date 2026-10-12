package com.oskiapps.flowgraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComposeImpactProjectionTest {
    private fun graph(confidence: CausalConfidence?, label: String): FlowGraph = FlowGraph(
        rootLabel = "Test",
        nodes = listOf(
            FlowNode("state", "state", "", NodeKind.STATE, null),
            FlowNode("collector", "collectAsStateWithLifecycle", "", NodeKind.COLLECTOR, null),
            FlowNode("screen", "Screen", "", NodeKind.COMPOSABLE, null),
        ),
        edges = listOf(
            FlowEdge("state", "collector", EdgeKind.COLLECTS),
            FlowEdge("collector", "screen", EdgeKind.UPDATES_COMPOSE,
                label = label, confidence = confidence),
        ),
    )

    @Test
    fun `collapsed graph keeps unverified Compose collection as possible`() {
        val original = graph(CausalConfidence.POSSIBLE, "State collected (value read not verified)")
        val projected = original.stateChangeProjection()
        val dependency = assertNotNull(projected.edges.find { it.kind == EdgeKind.UPDATES_COMPOSE })
        assertEquals("state", dependency.from)
        assertEquals("screen", dependency.to)
        assertEquals(CausalConfidence.POSSIBLE, dependency.confidence)
        assertEquals("State collected (value read not verified)", dependency.label)
    }

    @Test
    fun `collapsed graph retains verified read evidence`() {
        val original = graph(null, "Compose State value read (can invalidate)")
        val dependency = assertNotNull(original.stateChangeProjection().edges.find {
            it.kind == EdgeKind.UPDATES_COMPOSE
        })
        assertEquals(null, dependency.confidence)
        assertEquals("Compose State value read (can invalidate)", dependency.label)
    }
}
