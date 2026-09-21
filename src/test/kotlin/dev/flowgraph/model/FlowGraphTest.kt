package dev.flowgraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowGraphTest {
    private fun sampleGraph() = FlowGraph(
        rootLabel = "_state",
        nodes = listOf(
            FlowNode("w1", "select()", "", NodeKind.WRITER, null),
            FlowNode("w2", "setLoading()", "", NodeKind.WRITER, null),
            FlowNode("selected", "selectedSong", "", NodeKind.FIELD, null),
            FlowNode("loading", "loading", "", NodeKind.FIELD, null),
            FlowNode("state", "_state", "", NodeKind.STATE, null),
            FlowNode("map", "map", "", NodeKind.OPERATOR, null),
            FlowNode("ui", "uiState", "", NodeKind.STATE, null),
            FlowNode("collect", "collectAsStateWithLifecycle", "", NodeKind.COLLECTOR, null),
        ),
        edges = listOf(
            FlowEdge("w1", "selected", EdgeKind.WRITES_FIELD),
            FlowEdge("w2", "loading", EdgeKind.WRITES_FIELD),
            FlowEdge("selected", "state", EdgeKind.FIELD_OF),
            FlowEdge("loading", "state", EdgeKind.FIELD_OF),
            FlowEdge("state", "map", EdgeKind.TRANSFORMS),
            FlowEdge("map", "ui", EdgeKind.DERIVES),
            FlowEdge("ui", "collect", EdgeKind.COLLECTS),
        ),
    )

    @Test
    fun `field focus keeps only matching writers and containing state`() {
        val focused = sampleGraph().focusOnField("selected")

        assertEquals(setOf("w1", "selected", "state"), focused.nodes.map { it.id }.toSet())
        assertEquals(2, focused.edges.size)
        assertTrue(focused.edges.any { it.from == "w1" && it.to == "selected" })
        assertTrue(focused.edges.any { it.from == "selected" && it.to == "state" })
    }

    @Test
    fun `downstream focus includes derived flows and collectors`() {
        val focused = sampleGraph().focusDownstream("state")

        assertEquals(setOf("state", "map", "ui", "collect"), focused.nodes.map { it.id }.toSet())
    }

    @Test
    fun `impact reports transitive affected flow collector and writer`() {
        val impact = sampleGraph().impactFor("selected")!!

        assertEquals(
            setOf("_state", "uiState"),
            impact.downstreamStates.map { it.label }.toSet()
        )
        assertEquals(listOf("collectAsStateWithLifecycle"), impact.downstreamCollectors.map { it.label })
        assertEquals(listOf("select()"), impact.upstreamWriters.map { it.label })
    }

    @Test
    fun `field specific causes exclude unrelated combine inputs`() {
        val graph = FlowGraph(
            rootLabel = "uiState",
            nodes = listOf(
                FlowNode("user", "userFlow", "", NodeKind.STATE, null),
                FlowNode("settings", "settingsFlow", "", NodeKind.STATE, null),
                FlowNode("combine", "combine", "", NodeKind.OPERATOR, null),
                FlowNode("theme", "theme", "", NodeKind.FIELD, null),
                FlowNode("ui", "uiState", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("user", "combine", EdgeKind.TRANSFORMS, setOf("selectedUser")),
                FlowEdge("settings", "combine", EdgeKind.TRANSFORMS, setOf("theme")),
                FlowEdge("combine", "theme", EdgeKind.PRODUCES_FIELD),
                FlowEdge("theme", "ui", EdgeKind.FIELD_OF),
            ),
            fieldDependencies = listOf(
                FieldDependency(
                    sourceNodeId = "settings",
                    sourceField = "theme",
                    viaOperatorId = "combine",
                    outputFieldId = "theme",
                    outputField = "theme",
                    outputStateId = "ui",
                    confidence = ProvenanceConfidence.HIGH,
                ),
            ),
        )

        val focused = graph.focusUpstream("theme")

        assertEquals(setOf("settings", "combine", "theme", "ui"), focused.nodes.map { it.id }.toSet())
        assertTrue("user" !in focused.nodes.map { it.id })
        assertEquals(setOf("theme"), graph.edges.first { it.from == "settings" }.affectedFields)
    }

    @Test
    fun `state change projection collapses operator chain into state to state edge`() {
        val projection = sampleGraph().stateChangeProjection()

        assertEquals(setOf("state", "ui"), projection.nodes.map { it.id }.toSet())
        val edge = projection.edges.single { it.from == "state" && it.to == "ui" }
        assertEquals(EdgeKind.PROPAGATES, edge.kind)
        assertTrue(edge.label?.contains("map") == true)
    }

    @Test
    fun `side effect writer becomes state transition`() {
        val graph = FlowGraph(
            rootLabel = "source",
            nodes = listOf(
                FlowNode("source", "source", "", NodeKind.STATE, null),
                FlowNode("collect", "collectLatest", "", NodeKind.COLLECTOR, null),
                FlowNode("writer", "syncPlayback()", "", NodeKind.WRITER, null),
                FlowNode("field", "activeSong", "", NodeKind.FIELD, null),
                FlowNode("target", "_playbackState", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("source", "collect", EdgeKind.COLLECTS),
                FlowEdge("collect", "writer", EdgeKind.TRIGGERS_WRITE, setOf("activeSong"), "syncPlayback()"),
                FlowEdge("writer", "field", EdgeKind.WRITES_FIELD, setOf("activeSong")),
                FlowEdge("field", "target", EdgeKind.FIELD_OF, setOf("activeSong")),
            ),
        )

        val transition = graph.transitionsFrom("source").single()
        assertEquals("target", transition.targetStateId)
        assertEquals(StateTransitionKind.SIDE_EFFECT, transition.kind)
        assertTrue("activeSong" in transition.affectedFields)
        assertTrue(transition.summary.contains("collectLatest"))
        assertTrue(transition.summary.contains("syncPlayback"))
    }

    @Test
    fun `read only observers stay visible in separate projection branch`() {
        val graph = FlowGraph(
            rootLabel = "source",
            nodes = listOf(
                FlowNode("source", "source", "", NodeKind.STATE, null),
                FlowNode("read", "render() • value read", "", NodeKind.READ, null),
            ),
            edges = listOf(
                FlowEdge("source", "read", EdgeKind.READS, label = "value read"),
            ),
        )

        val projection = graph.stateChangeProjection(includeReads = true)
        assertEquals(setOf("source", "read"), projection.nodes.map { it.id }.toSet())
        assertTrue(projection.edges.any { it.kind == EdgeKind.READS })
    }

    @Test
    fun `possible read behavior can connect one state to another`() {
        val graph = FlowGraph(
            rootLabel = "source",
            nodes = listOf(
                FlowNode("source", "source", "", NodeKind.STATE, null),
                FlowNode("behavior", "refresh()", "", NodeKind.BEHAVIOR, null),
                FlowNode("writer", "refresh() • update", "", NodeKind.WRITER, null),
                FlowNode("target", "target", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge(
                    "source", "behavior", EdgeKind.POSSIBLY_TRIGGERS_WRITE,
                    label = "reads → behavior", confidence = CausalConfidence.POSSIBLE,
                ),
                FlowEdge(
                    "behavior", "writer", EdgeKind.POSSIBLY_TRIGGERS_WRITE,
                    label = "possible write", confidence = CausalConfidence.POSSIBLE,
                ),
                FlowEdge("writer", "target", EdgeKind.WRITES),
            ),
        )

        val transition = graph.transitionsFrom("source").single()
        assertEquals("target", transition.targetStateId)
        assertEquals(StateTransitionKind.SIDE_EFFECT, transition.kind)
        assertEquals(CausalConfidence.POSSIBLE, transition.confidence)
        assertTrue(transition.summary.startsWith("possible:"))
    }

    @Test
    fun `connection focus keeps causal cone but removes sibling branches`() {
        val graph = FlowGraph(
            rootLabel = "A",
            nodes = listOf(
                FlowNode("A", "A", "", NodeKind.STATE, null),
                FlowNode("B", "B", "", NodeKind.STATE, null),
                FlowNode("C", "C", "", NodeKind.STATE, null),
                FlowNode("D", "D", "", NodeKind.STATE, null),
                FlowNode("E", "E", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("A", "B", EdgeKind.PROPAGATES),
                FlowEdge("A", "C", EdgeKind.PROPAGATES),
                FlowEdge("B", "D", EdgeKind.PROPAGATES),
                FlowEdge("E", "B", EdgeKind.PROPAGATES),
            ),
        )

        val focused = graph.focusConnections("B")

        assertEquals(setOf("A", "B", "D", "E"), focused.nodes.map { it.id }.toSet())
        assertTrue("C" !in focused.nodes.map { it.id })
        assertEquals(3, focused.edges.size)
    }

    @Test
    fun `simulation follows transitive state changes`() {
        val graph = FlowGraph(
            rootLabel = "A",
            nodes = listOf(
                FlowNode("A", "A", "", NodeKind.STATE, null),
                FlowNode("map", "map", "", NodeKind.OPERATOR, null),
                FlowNode("B", "B", "", NodeKind.STATE, null),
                FlowNode("latest", "collectLatest", "", NodeKind.COLLECTOR, null),
                FlowNode("writer", "update", "", NodeKind.WRITER, null),
                FlowNode("C", "C", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("A", "map", EdgeKind.TRANSFORMS),
                FlowEdge("map", "B", EdgeKind.DERIVES),
                FlowEdge("B", "latest", EdgeKind.COLLECTS),
                FlowEdge("latest", "writer", EdgeKind.TRIGGERS_WRITE),
                FlowEdge("writer", "C", EdgeKind.WRITES),
            ),
        )

        val result = graph.simulate(SimulationSpec("A"))

        assertEquals(setOf("A", "B", "C"), result.reachedStateIds)
        assertEquals(2, result.hops.size)
        assertEquals(SimulationStatus.DEFINITE, result.hops[0].status)
        assertEquals(SimulationStatus.CANCELLATION_AWARE, result.hops[1].status)
    }

    @Test
    fun `equal StateFlow assignment suppresses simulation`() {
        val graph = sampleGraph()
        val result = graph.simulate(SimulationSpec("state", equalToCurrentValue = true))

        assertTrue(result.hops.isEmpty())
        assertEquals(setOf("state"), result.reachedStateIds)
        assertTrue(result.diagnostics.single().contains("suppresses"))
    }

    @Test
    fun `field simulation prunes combine input that cannot affect selected field`() {
        val graph = FlowGraph(
            rootLabel = "settings",
            nodes = listOf(
                FlowNode("settings", "settings", "", NodeKind.STATE, null),
                FlowNode("combine", "combine", "", NodeKind.OPERATOR, null),
                FlowNode("uiField", "theme", "", NodeKind.FIELD, null),
                FlowNode("ui", "ui", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("settings", "combine", EdgeKind.TRANSFORMS, setOf("theme")),
                FlowEdge("combine", "uiField", EdgeKind.PRODUCES_FIELD),
                FlowEdge("uiField", "ui", EdgeKind.FIELD_OF),
            ),
            fieldDependencies = listOf(
                FieldDependency("settings", "theme", "combine", "uiField", "theme", "ui", ProvenanceConfidence.HIGH),
            ),
        )

        val matching = graph.simulate(SimulationSpec("settings", changedField = "theme"))
        val unrelated = graph.simulate(SimulationSpec("settings", changedField = "language"))

        assertEquals(setOf("settings", "ui"), matching.reachedStateIds)
        assertEquals(setOf("settings"), unrelated.reachedStateIds)
    }

    @Test
    fun `runtime key can fall back to a unique JVM field name`() {
        val graph = FlowGraph(
            rootLabel = "state",
            nodes = listOf(
                FlowNode(
                    id = "state",
                    label = "state",
                    detail = "",
                    kind = NodeKind.STATE,
                    source = null,
                    runtimeKey = "com.example.Companion.state",
                ),
            ),
            edges = emptyList(),
        )

        assertEquals("state", graph.stateByRuntimeKey("com.example.Outer.state")?.id)
    }

    @Test
    fun `possible coupling is visible but does not recursively expand by default`() {
        val graph = FlowGraph(
            rootLabel = "A",
            nodes = listOf(
                FlowNode("A", "A", "", NodeKind.STATE, null),
                FlowNode("B", "B", "", NodeKind.STATE, null),
                FlowNode("C", "C", "", NodeKind.STATE, null),
                FlowNode("D", "D", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("A", "B", EdgeKind.PROPAGATES),
                FlowEdge("B", "C", EdgeKind.PROPAGATES, confidence = CausalConfidence.POSSIBLE),
                FlowEdge("C", "D", EdgeKind.PROPAGATES),
            ),
        )

        val focused = graph.focusConnections("A", maxDepth = 3, includePossible = false)

        assertEquals(setOf("A", "B", "C"), focused.nodes.map { it.id }.toSet())
        assertTrue("D" !in focused.nodes.map { it.id })
        assertTrue(focused.edges.any { it.from == "B" && it.to == "C" })
    }

    @Test
    fun `depth limit keeps nearest causal noodle only`() {
        val graph = FlowGraph(
            rootLabel = "A",
            nodes = (listOf("A", "B", "C", "D", "E")).map { FlowNode(it, it, "", NodeKind.STATE, null) },
            edges = listOf(
                FlowEdge("A", "B", EdgeKind.PROPAGATES),
                FlowEdge("B", "C", EdgeKind.PROPAGATES),
                FlowEdge("C", "D", EdgeKind.PROPAGATES),
                FlowEdge("D", "E", EdgeKind.PROPAGATES),
            ),
        )

        val focused = graph.focusConnections("C", maxDepth = 1)
        assertEquals(setOf("B", "C", "D"), focused.nodes.map { it.id }.toSet())
    }

    @Test
    fun `cycle collapse preserves selected pivot and collapses peers`() {
        val graph = FlowGraph(
            rootLabel = "B",
            nodes = listOf(
                FlowNode("A", "A", "", NodeKind.STATE, null),
                FlowNode("B", "B", "", NodeKind.STATE, null),
                FlowNode("C", "C", "", NodeKind.STATE, null),
                FlowNode("D", "D", "", NodeKind.STATE, null),
            ),
            edges = listOf(
                FlowEdge("A", "B", EdgeKind.PROPAGATES),
                FlowEdge("B", "C", EdgeKind.PROPAGATES),
                FlowEdge("C", "A", EdgeKind.PROPAGATES),
                FlowEdge("C", "D", EdgeKind.PROPAGATES),
            ),
        )

        val collapsed = graph.collapseCausalCycles(preserveNodeId = "B")
        assertTrue(collapsed.nodes.any { it.id == "B" })
        assertTrue(collapsed.nodes.any { it.kind == NodeKind.CYCLE })
        assertEquals(3, collapsed.nodes.size) // B, cycle peers, D
    }


}
