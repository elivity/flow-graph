package com.oskiapps.flowgraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndependentComposeGraphMergeTest {
    @Test
    fun `compose-only project retains all isolated screens without flows`() {
        val emptyFlowGraph = FlowGraph("All project flows", emptyList(), emptyList())
        val ui = FlowGraph(
            "Project composables",
            listOf(
                FlowNode("compose:a", "RecipesScreen", "@Composable", NodeKind.COMPOSABLE, null),
                FlowNode("compose:b", "IngredientsScreen", "@Composable", NodeKind.COMPOSABLE, null),
            ),
            emptyList(),
        )
        val result = emptyFlowGraph.mergeIndependentComposeGraph(ui)
        assertEquals(setOf("compose:a", "compose:b"), result.nodes.map { it.id }.toSet())
        assertTrue(result.edges.isEmpty())
        assertEquals(2, result.stateChangeProjection().nodes.size)
    }

    @Test
    fun `static calls merge without inventing a state consumer edge`() {
        val state = FlowNode("state:s", "state", "StateFlow", NodeKind.STATE, null)
        val screen = FlowNode("compose:screen", "RecipesScreen", "@Composable", NodeKind.COMPOSABLE, null)
        val detail = FlowNode("compose:detail", "RecipeDetails", "@Composable", NodeKind.COMPOSABLE, null)
        val flow = FlowGraph("All project flows", listOf(state, screen), emptyList())
        val compose = FlowGraph(
            "Project composables", listOf(screen, detail),
            listOf(FlowEdge(screen.id, detail.id, EdgeKind.COMPOSES)),
        )
        val merged = flow.mergeIndependentComposeGraph(compose)
        assertEquals(3, merged.nodes.size)
        assertEquals(1, merged.edges.size)
        assertEquals(EdgeKind.COMPOSES, merged.edges.single().kind)
        assertFalse(merged.edges.any { it.kind == EdgeKind.UPDATES_COMPOSE })
    }

    @Test
    fun `existing verified flow to composable relationship is preserved`() {
        val state = FlowNode("state:s", "state", "StateFlow", NodeKind.STATE, null)
        val screen = FlowNode("compose:screen", "RecipesScreen", "@Composable", NodeKind.COMPOSABLE, null)
        val dependency = FlowEdge(state.id, screen.id, EdgeKind.UPDATES_COMPOSE)
        val flow = FlowGraph("All project flows", listOf(state, screen), listOf(dependency))
        val compose = FlowGraph("Project composables", listOf(screen), emptyList())
        val merged = flow.mergeIndependentComposeGraph(compose)
        assertEquals(2, merged.nodes.size)
        assertEquals(listOf(dependency), merged.edges)
    }
}
