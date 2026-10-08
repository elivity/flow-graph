package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

enum class NodeKind {
    STATE,
    FIELD,
    WRITER,
    EXPOSURE,
    OPERATOR,
    COLLECTOR,
    /** A code scope that reads a Flow and can write another Flow/StateFlow. */
    BEHAVIOR,
    /** A read/observer site for which no tracked Flow write was discovered. */
    READ,
    /** A source @Composable function or a visual Compose framework call site in the composition tree. */
    COMPOSABLE,
    /** Synthetic strongly-connected state cycle collapsed for readability. */
    CYCLE,
    /** Synthetic framework/dependency cluster collapsed for readability. */
    CLUSTER,
}

data class SourceLocation(
    val file: VirtualFile,
    val offset: Int,
    val line: Int,
)

enum class NodeGroupKind {
    VIEW_MODEL,
    COMPOSE,
    SOURCE,
}

data class FlowNode(
    val id: String,
    val label: String,
    val detail: String,
    val kind: NodeKind,
    val source: SourceLocation?,
    /** Stable-ish source key used by the optional runtime trace bridge, e.g. com.foo.PlayerVm._state. */
    val runtimeKey: String? = null,
    /** Stable semantic owner used by the project overview for ViewModel/screen clustering. */
    val groupKey: String? = null,
    val groupLabel: String? = null,
    val groupKind: NodeGroupKind? = null,
    /** Additional exact runtime identities that map to this node (for example delegated-State access sites). */
    val runtimeAliases: Set<String> = emptySet(),
)

enum class EdgeKind {
    WRITES,
    WRITES_FIELD,
    FIELD_OF,
    PRODUCES_FIELD,
    EXPOSES,
    TRANSFORMS,
    DERIVES,
    COLLECTS,
    /** A running operator/collector/behavior invokes a write into another MutableStateFlow/SharedFlow. */
    TRIGGERS_WRITE,
    /** The source Flow is observed/read here, but no tracked state write is known. */
    READS,
    /** A collectAsState/collectAsStateWithLifecycle result invalidates/recomposes a composable. */
    UPDATES_COMPOSE,
    /** A composable/function/call-site composes another source composable or visual Compose call site. */
    COMPOSES,
    /** Same-scope/helper analysis found a possible causal read -> write relation. */
    POSSIBLY_TRIGGERS_WRITE,
    /** Synthetic edge used only by the collapsed State Changes view. */
    PROPAGATES,
}

enum class CausalConfidence {
    DEFINITE,
    POSSIBLE,
}

data class FlowEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    /** Output fields that this specific input edge can influence, when known. */
    val affectedFields: Set<String> = emptySet(),
    /** Human-readable propagation path for synthetic/collapsed edges. */
    val label: String? = null,
    /** Null for ordinary structural edges; populated for inferred read -> write causality. */
    val confidence: CausalConfidence? = null,
    /**
     * Best call site to navigate to when the user clicks this edge.
     *
     * Detailed edges may leave this null because the UI can use the operator/writer endpoint.
     * Synthetic collapsed propagation edges populate it explicitly because both endpoints are states.
     */
    val source: SourceLocation? = null,
)

enum class ProvenanceConfidence {
    HIGH,
    MEDIUM,
}

data class FieldDependency(
    val sourceNodeId: String,
    val sourceField: String?,
    val viaOperatorId: String,
    val outputFieldId: String,
    val outputField: String,
    val outputStateId: String,
    val confidence: ProvenanceConfidence,
)

enum class StateTransitionKind {
    DERIVED,
    SIDE_EFFECT,
    EXPOSURE,
}

/**
 * A semantic Flow/StateFlow-to-Flow/StateFlow impact path.
 *
 * [confidence] is DEFINITE for direct Flow operator/collector causality and POSSIBLE when the
 * relation was inferred from a broader function/helper scope that both reads the source and writes
 * the target. The latter is deliberately shown for spaghetti-flow discovery instead of silently
 * omitting a potentially important connection.
 */
data class StateTransition(
    val sourceStateId: String,
    val targetStateId: String,
    val viaNodeIds: List<String>,
    val kind: StateTransitionKind,
    val affectedFields: Set<String>,
    val summary: String,
    val confidence: CausalConfidence = CausalConfidence.DEFINITE,
)

data class FlowImpact(
    val node: FlowNode,
    val incoming: List<Pair<FlowEdge, FlowNode>>,
    val outgoing: List<Pair<FlowEdge, FlowNode>>,
    val downstreamStates: List<FlowNode>,
    val downstreamCollectors: List<FlowNode>,
    val downstreamOperators: List<FlowNode>,
    val downstreamReads: List<FlowNode>,
    val downstreamBehaviors: List<FlowNode>,
    val upstreamWriters: List<FlowNode>,
    val upstreamStates: List<FlowNode>,
)



data class FlowGraph(
    val rootLabel: String,
    val nodes: List<FlowNode>,
    val edges: List<FlowEdge>,
    val fieldDependencies: List<FieldDependency> = emptyList(),
    val diagnostics: List<String> = emptyList(),
) {
    // FlowGraph is immutable after analysis. Build hot lookup structures once instead of
    // allocating/filtering the whole node list for every runtime sample and every timeline scrub.
    internal val nodesById: Map<String, FlowNode> = nodes.associateBy { it.id }
    internal val edgesByTo: Map<String, List<FlowEdge>> = edges.groupBy { it.to }
    internal val runtimeLookupCache = ConcurrentHashMap<String, FlowNode>()
    internal val runtimeLookupMisses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    internal var stateTransitionsCache: List<StateTransition>? = null
    @Volatile
    internal var stateTransitionsByTargetCache: Map<String, List<StateTransition>>? = null























    internal companion object {
        const val MAX_TRANSITION_PATH = 24
    }


    /**
     * Collapses strongly connected components in the projected causal graph. The selected node is
     * preserved as an explicit pivot; the remaining members of its cycle become a single peer node.
     */



    /**
     * Several detailed routes may collapse to the same projected edge. Keep one visual edge but
     * preserve the strongest evidence: one definite route makes the projected relationship definite;
     * it must not disappear merely because a second possible route was also discovered.
     */


}
