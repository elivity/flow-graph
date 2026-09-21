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
    /** A source @Composable function that consumes Flow-backed state or is called by one. */
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
    /** A composable invokes another source composable. */
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
    private val nodesById: Map<String, FlowNode> = nodes.associateBy { it.id }
    private val edgesByTo: Map<String, List<FlowEdge>> = edges.groupBy { it.to }
    private val runtimeLookupCache = ConcurrentHashMap<String, FlowNode>()
    private val runtimeLookupMisses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var stateTransitionsCache: List<StateTransition>? = null
    @Volatile
    private var stateTransitionsByTargetCache: Map<String, List<StateTransition>>? = null

    fun node(nodeId: String): FlowNode? = nodesById[nodeId]

    fun incomingEdges(nodeId: String): List<FlowEdge> = edgesByTo[nodeId].orEmpty()

    fun stateTransitionsTo(nodeId: String): List<StateTransition> {
        stateTransitionsByTargetCache?.let { return it[nodeId].orEmpty() }
        val grouped = stateTransitions().groupBy { it.targetStateId }
        stateTransitionsByTargetCache = grouped
        return grouped[nodeId].orEmpty()
    }

    fun fieldsForState(stateId: String): List<FlowNode> {
        val fieldIds = edges.asSequence()
            .filter { it.kind == EdgeKind.FIELD_OF && it.to == stateId }
            .map { it.from }
            .toSet()
        return nodes.filter { it.id in fieldIds && it.kind == NodeKind.FIELD }.sortedBy { it.label }
    }

    /** Maps both source-property runtime keys and v0.11 synthetic cold-flow operator keys. */
    fun nodeByRuntimeKey(key: String): FlowNode? {
        val normalized = key.trim()
        if (normalized.isEmpty()) return null
        runtimeLookupCache[normalized]?.let { return it }
        if (normalized in runtimeLookupMisses) return null

        val resolved = resolveNodeByRuntimeKey(normalized)
        if (resolved != null) runtimeLookupCache[normalized] = resolved else runtimeLookupMisses += normalized
        return resolved
    }

    private fun resolveNodeByRuntimeKey(normalized: String): FlowNode? {
        if (normalized.startsWith("@flowop|")) {
            val parts = normalized.split('|')
            val line = parts.getOrNull(2)?.toIntOrNull()
            val callName = parts.getOrNull(3)?.trim().orEmpty()
            if (line != null && callName.isNotEmpty()) {
                val candidates = nodes.filter { node ->
                    node.kind in setOf(NodeKind.OPERATOR, NodeKind.COLLECTOR, NodeKind.EXPOSURE) &&
                        node.source?.line?.plus(1) == line &&
                        node.label.removeSuffix("*") == callName
                }
                if (candidates.size == 1) return candidates.first()
                // Source line is usually enough in a focused graph even when a custom operator label
                // differs slightly from the bytecode method name.
                val byLine = nodes.filter { node ->
                    node.kind in setOf(NodeKind.OPERATOR, NodeKind.COLLECTOR, NodeKind.EXPOSURE) &&
                        node.source?.line?.plus(1) == line
                }
                if (byLine.size == 1) return byLine.first()
            }
            return null
        }
        if (normalized.startsWith("@collect|")) {
            val site = normalized.removePrefix("@collect|")
            val line = site.substringAfterLast(':', "").toIntOrNull()
            if (line != null) {
                val candidates = nodes.filter { node ->
                    node.kind == NodeKind.COLLECTOR && node.source?.line?.plus(1) == line
                }
                if (candidates.size == 1) return candidates.first()
            }
            return null
        }
        if (normalized.startsWith("@compose|")) {
            return nodes.filter { it.kind == NodeKind.COMPOSABLE && it.runtimeKey == normalized }.singleOrNull()
        }
        if (normalized.startsWith("@composestate2|")) {
            val parts = normalized.split('|')
            val sourceFile = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val line = parts.getOrNull(2)?.toIntOrNull()
            val callName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            val sourceFunction = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            if (sourceFile != null && line != null && callName != null && sourceFunction != null) {
                // v0.17.34+: exact source declaration identity. Function context is essential: Kotlin
                // inline/composable lowering can assign the same source line to unrelated State
                // factories. Never fall back to file+line for a v2 runtime event.
                val exactSourceKey =
                    "@composestate-source2|$sourceFile|$line|$callName|$sourceFunction"
                val exact = nodes.filter { node ->
                    node.kind == NodeKind.STATE && node.runtimeKey == exactSourceKey
                }
                if (exact.size == 1) return exact.first()
                return null
            }
            return null
        }
        if (normalized.startsWith("@composestate|")) {
            val parts = normalized.split('|')
            val line = parts.getOrNull(2)?.toIntOrNull()
            val callName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            val sourceFile = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            if (line != null && callName != null && sourceFile != null) {
                // Legacy v0.17.32 runtime key: source factory without function context.
                // Do not collapse every State-producing call on one bytecode/source line into the
                // first static Compose-state node; animated/updated states can write at frame rate.
                val exactSourceKey = "@composestate-source|$sourceFile|$line|$callName"
                val exact = nodes.filter { node ->
                    node.kind == NodeKind.STATE && node.runtimeKey == exactSourceKey
                }
                if (exact.size == 1) return exact.first()

                // Backward compatibility for graphs produced by pre-v0.17.32 analyzers. Restrict
                // legacy matching to an explicit old line key and the same source file; never use
                // arbitrary node.source line fallback for a modern dynamic Compose-state event.
                val legacy = nodes.filter { node ->
                    node.kind == NodeKind.STATE &&
                        node.runtimeKey == "@composestate-line|$line" &&
                        node.source?.file?.name == sourceFile
                }
                if (legacy.size == 1) return legacy.first()
                return null
            }
            return null
        }
        return stateByRuntimeKey(normalized)
    }

    fun stateByRuntimeKey(key: String): FlowNode? {
        val normalized = key.trim()
        if (normalized.isEmpty()) return null
        val exact = nodes.firstOrNull {
            it.kind == NodeKind.STATE && (it.runtimeKey == normalized || normalized in it.runtimeAliases)
        }
        if (exact != null) return exact
        val suffix = nodes.filter { it.kind == NodeKind.STATE && it.runtimeKey?.endsWith(normalized) == true }
        if (suffix.size == 1) return suffix.first()

        // Fuzzy label/trailing-field matching is only valid for real JVM field-backed state. Local
        // and delegated Compose State uses synthetic @composestate* identities and must never win
        // this fallback. Otherwise an unrelated runtime key such as `SomeRepository.state` can be
        // mapped to a local Compose variable merely because its source label is also `state`. That
        // was the source of impossible values such as [] appearing on ThumbState.
        val fieldBackedCandidates = nodes.filter { node ->
            node.kind == NodeKind.STATE &&
                node.runtimeKey != null &&
                !node.runtimeKey.startsWith("@")
        }
        val byLabel = fieldBackedCandidates.filter { it.label == normalized }
        if (byLabel.size == 1) return byLabel.first()
        // Automatic bytecode instrumentation can only see JVM owners/fields. Companion/top-level
        // lowering can therefore produce a key whose owner differs from the source-level owner.
        // Keep the trailing-field fallback, but only inside field-backed candidates.
        val trailingName = normalized.substringAfterLast('.')
        return fieldBackedCandidates.filter { it.label == trailingName }.singleOrNull()
    }

    fun dependenciesForField(fieldId: String): List<FieldDependency> =
        fieldDependencies.filter { it.outputFieldId == fieldId }

    fun fieldEffectsFrom(nodeId: String): List<FieldDependency> =
        fieldDependencies.filter { it.sourceNodeId == nodeId || it.viaOperatorId == nodeId }

    /**
     * Collapses the detailed graph into semantic state-to-state change paths.
     * READ-only branches never become transitions; BEHAVIOR branches do when they eventually write
     * another tracked Flow/StateFlow.
     */
    fun stateTransitions(): List<StateTransition> {
        stateTransitionsCache?.let { return it }
        val computed = computeStateTransitions()
        stateTransitionsCache = computed
        return computed
    }

    private fun computeStateTransitions(): List<StateTransition> {
        val byId = nodesById
        val adjacency = edges.groupBy { it.from }
        val result = linkedMapOf<String, StateTransition>()

        nodes.asSequence().filter { it.kind == NodeKind.STATE }.forEach { source ->
            data class Step(
                val nodeId: String,
                val via: List<String>,
                val visited: Set<String>,
                val fields: Set<String>,
                val edgeKinds: Set<EdgeKind>,
                val confidence: CausalConfidence,
            )

            val queue = ArrayDeque<Step>()
            queue += Step(
                nodeId = source.id,
                via = emptyList(),
                visited = setOf(source.id),
                fields = emptySet(),
                edgeKinds = emptySet(),
                confidence = CausalConfidence.DEFINITE,
            )

            while (queue.isNotEmpty()) {
                val step = queue.removeFirst()
                adjacency[step.nodeId].orEmpty().forEach edgeLoop@ { edge ->
                    // Read-only observer edges intentionally terminate state propagation.
                    if (edge.kind == EdgeKind.READS) return@edgeLoop
                    if (edge.to in step.visited) return@edgeLoop
                    val nextNode = byId[edge.to] ?: return@edgeLoop
                    if (nextNode.kind == NodeKind.READ) return@edgeLoop

                    val nextFields = step.fields + edge.affectedFields +
                        (if (nextNode.kind == NodeKind.FIELD) setOf(nextNode.label) else emptySet())
                    val nextKinds = step.edgeKinds + edge.kind
                    val nextConfidence = if (
                        step.confidence == CausalConfidence.POSSIBLE ||
                        edge.kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE ||
                        edge.confidence == CausalConfidence.POSSIBLE
                    ) CausalConfidence.POSSIBLE else CausalConfidence.DEFINITE

                    if (nextNode.kind == NodeKind.STATE) {
                        if (nextNode.id == source.id) return@edgeLoop
                        val viaNodes = step.via
                        val viaLabels = viaNodes.mapNotNull(byId::get)
                            .filter { it.kind != NodeKind.FIELD }
                            .map { it.label }
                        val kind = when {
                            EdgeKind.TRIGGERS_WRITE in nextKinds ||
                                EdgeKind.POSSIBLY_TRIGGERS_WRITE in nextKinds ||
                                viaNodes.any { byId[it]?.kind == NodeKind.WRITER || byId[it]?.kind == NodeKind.BEHAVIOR } ->
                                StateTransitionKind.SIDE_EFFECT
                            viaNodes.any { byId[it]?.kind == NodeKind.EXPOSURE } &&
                                viaNodes.none { byId[it]?.kind == NodeKind.OPERATOR } -> StateTransitionKind.EXPOSURE
                            else -> StateTransitionKind.DERIVED
                        }
                        val summary = buildString {
                            if (nextConfidence == CausalConfidence.POSSIBLE) append("possible: ")
                            if (viaLabels.isEmpty()) append("propagates")
                            else append(viaLabels.joinToString(" → "))
                            if (nextFields.isNotEmpty()) {
                                append(" • ")
                                append(nextFields.sorted().joinToString(", "))
                            }
                        }
                        val key = "${source.id}->${nextNode.id}:$summary"
                        result.putIfAbsent(
                            key,
                            StateTransition(
                                sourceStateId = source.id,
                                targetStateId = nextNode.id,
                                viaNodeIds = viaNodes,
                                kind = kind,
                                affectedFields = nextFields,
                                summary = summary,
                                confidence = nextConfidence,
                            ),
                        )
                        return@edgeLoop
                    }

                    if (step.via.size >= MAX_TRANSITION_PATH) return@edgeLoop
                    queue += Step(
                        nodeId = nextNode.id,
                        via = step.via + nextNode.id,
                        visited = step.visited + nextNode.id,
                        fields = nextFields,
                        edgeKinds = nextKinds,
                        confidence = nextConfidence,
                    )
                }
            }
        }
        return result.values.toList()
    }

    /**
     * Default graph: StateFlow-to-StateFlow change edges plus a separate set of read-only observer
     * nodes. The canvas renders READ nodes in their own cluster below the propagation graph.
     */
    fun stateChangeProjection(includeReads: Boolean = true): FlowGraph {
        val transitions = stateTransitions()
        val stateIds = transitions.flatMapTo(linkedSetOf()) { listOf(it.sourceStateId, it.targetStateId) }
        nodes.filterTo(mutableListOf()) { it.kind == NodeKind.STATE }.forEach { stateIds += it.id }

        val projectedEdges = transitions
            .groupBy { it.sourceStateId to it.targetStateId }
            .map { (pair, alternatives) ->
                val summaries = alternatives.map { it.summary }.distinct()
                val label = summaries.take(2).joinToString(" | ") +
                    if (summaries.size > 2) " | +${summaries.size - 2} paths" else ""
                val navigationSource = alternatives.asSequence()
                    .flatMap { it.viaNodeIds.asSequence() }
                    .mapNotNull { nodesById[it] }
                    .firstOrNull {
                        it.source != null && it.kind in setOf(
                            NodeKind.COLLECTOR,
                            NodeKind.OPERATOR,
                            NodeKind.BEHAVIOR,
                            NodeKind.WRITER,
                            NodeKind.EXPOSURE,
                        )
                    }
                    ?.source

                FlowEdge(
                    from = pair.first,
                    to = pair.second,
                    kind = EdgeKind.PROPAGATES,
                    affectedFields = alternatives.flatMapTo(linkedSetOf()) { it.affectedFields },
                    label = label,
                    confidence = if (alternatives.all { it.confidence == CausalConfidence.POSSIBLE }) {
                        CausalConfidence.POSSIBLE
                    } else CausalConfidence.DEFINITE,
                    source = navigationSource,
                )
            }
            .toMutableList()

        // Compose remains visible even in the collapsed State Changes view. Collapse the
        // collectAsState* plumbing into StateFlow -> @Composable edges, then keep the source
        // composable call hierarchy. Show operators can still reveal the collector node itself.
        val composeNodes = nodes.filter { it.kind == NodeKind.COMPOSABLE }
        val composeIds = composeNodes.mapTo(linkedSetOf()) { it.id }
        edges.filter { it.kind == EdgeKind.UPDATES_COMPOSE && it.to in composeIds }.forEach { update ->
            upstreamStatesFor(update.from, maxDepth = 8).forEach { sourceState ->
                projectedEdges += FlowEdge(
                    from = sourceState,
                    to = update.to,
                    kind = EdgeKind.UPDATES_COMPOSE,
                    label = update.label ?: "collectAsState → recompose",
                    source = update.source,
                )
            }
        }
        projectedEdges += edges.filter { it.kind == EdgeKind.COMPOSES && it.from in composeIds && it.to in composeIds }

        val readNodes = if (includeReads) nodes.filter { it.kind == NodeKind.READ } else emptyList()
        val readIds = readNodes.mapTo(linkedSetOf()) { it.id }
        if (includeReads) {
            projectedEdges += edges.filter { edge ->
                edge.kind == EdgeKind.READS && edge.from in stateIds && edge.to in readIds
            }
        }

        return copy(
            rootLabel = "$rootLabel • state changes",
            nodes = nodes.filter { it.id in stateIds && it.kind == NodeKind.STATE } + composeNodes + readNodes,
            edges = projectedEdges.distinctBy { Triple(it.from, it.to, it.kind) },
        )
    }

    private fun upstreamStatesFor(startId: String, maxDepth: Int): Set<String> {
        data class Step(val id: String, val depth: Int)
        val result = linkedSetOf<String>()
        val visited = mutableSetOf(startId)
        val queue = ArrayDeque<Step>()
        queue += Step(startId, 0)
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            if (step.depth >= maxDepth) continue
            edges.asSequence()
                .filter { it.to == step.id && it.kind != EdgeKind.READS && it.kind != EdgeKind.COMPOSES }
                .forEach { edge ->
                    if (!visited.add(edge.from)) return@forEach
                    val source = nodesById[edge.from] ?: return@forEach
                    if (source.kind == NodeKind.STATE) result += source.id
                    else queue += Step(source.id, step.depth + 1)
                }
        }
        return result
    }

    fun withoutReads(): FlowGraph {
        val readIds = nodes.filter { it.kind == NodeKind.READ }.mapTo(linkedSetOf()) { it.id }
        if (readIds.isEmpty()) return this
        return copy(
            nodes = nodes.filter { it.id !in readIds },
            edges = edges.filter { it.from !in readIds && it.to !in readIds },
        )
    }

    fun readsFrom(stateId: String): List<FlowNode> {
        val byId = nodesById
        return edges.asSequence()
            .filter { it.from == stateId && it.kind == EdgeKind.READS }
            .mapNotNull { byId[it.to] }
            .filter { it.kind == NodeKind.READ }
            .distinctBy { it.id }
            .sortedBy { it.label }
            .toList()
    }

    fun transitionsFrom(stateId: String): List<StateTransition> =
        stateTransitions().filter { it.sourceStateId == stateId }

    fun transitionsTo(stateId: String): List<StateTransition> =
        stateTransitions().filter { it.targetStateId == stateId }

    fun focusOnField(fieldId: String): FlowGraph {
        val field = nodes.firstOrNull { it.id == fieldId && it.kind == NodeKind.FIELD } ?: return this
        val writerIds = edges.asSequence()
            .filter { it.kind == EdgeKind.WRITES_FIELD && it.to == fieldId }
            .map { it.from }
            .toSet()
        val stateIds = edges.asSequence()
            .filter { it.kind == EdgeKind.FIELD_OF && it.from == fieldId }
            .map { it.to }
            .toSet()
        val provenance = dependenciesForField(fieldId)
        val provenanceIds = provenance.flatMapTo(linkedSetOf()) {
            listOf(it.sourceNodeId, it.viaOperatorId, it.outputFieldId, it.outputStateId)
        }
        val keep = writerIds + stateIds + provenanceIds + fieldId
        return filtered("${field.label} writes / provenance", keep)
    }

    /**
     * Keeps only the selected node's causal cone.
     *
     * By default only high-confidence propagation expands the cone. POSSIBLE edges are still kept
     * as one-hop context, but they do not recursively flood the graph unless [includePossible] is
     * enabled. [maxDepth] limits causal distance in either direction; null means unlimited.
     */
    fun focusConnections(
        nodeId: String,
        maxDepth: Int? = 3,
        includePossible: Boolean = false,
        includePossibleContext: Boolean = true,
    ): FlowGraph = focusConnectionsDirectional(
        nodeId = nodeId,
        upstreamDepth = maxDepth,
        downstreamDepth = maxDepth,
        includePossible = includePossible,
        includePossibleContext = includePossibleContext,
    )

    /**
     * Causal-cone focus with independent upstream/downstream depth. This powers the detail-view
     * side expanders: the left button can reveal one more cause level without also changing the
     * downstream side, and vice versa. A null depth means unlimited traversal on that side.
     */
    fun focusConnectionsDirectional(
        nodeId: String,
        upstreamDepth: Int? = 3,
        downstreamDepth: Int? = 3,
        includePossible: Boolean = false,
        includePossibleContext: Boolean = true,
    ): FlowGraph {
        val origin = nodes.firstOrNull { it.id == nodeId } ?: return this
        val definiteKeep = linkedSetOf(nodeId)
        definiteKeep += reachable(
            startId = nodeId,
            forward = false,
            maxDepth = upstreamDepth,
            includePossible = includePossible,
        )
        definiteKeep += reachable(
            startId = nodeId,
            forward = true,
            maxDepth = downstreamDepth,
            includePossible = includePossible,
        )

        val keep = linkedSetOf<String>().apply { addAll(definiteKeep) }
        if (!includePossible && includePossibleContext) {
            edges.asSequence()
                .filter { it.isPossibleCoupling() }
                .filter { it.from in definiteKeep || it.to in definiteKeep }
                .forEach { edge ->
                    keep += edge.from
                    keep += edge.to
                }
        }

        // Keep read-only observers as terminal context, never as traversal bridges.
        edges.asSequence()
            .filter { it.kind == EdgeKind.READS && it.from in definiteKeep }
            .forEach { edge ->
                keep += edge.from
                keep += edge.to
            }

        return filtered("Connections of ${origin.label}", keep)
    }

    /**
     * Keeps nodes that were recently active at runtime plus short connector paths between them.
     *
     * The caller supplies ids that are currently inside its activity window. Connector nodes are
     * included only when they lie on a shortest visible path between two active nodes, so the
     * filtered graph preserves useful causality without bringing the whole component back.
     */
    fun focusRecentActivity(
        activeNodeIds: Set<String>,
        maxConnectorHops: Int = 6,
    ): FlowGraph {
        val active = activeNodeIds.filterTo(linkedSetOf()) { it in nodesById }
        val recentLabel = if (rootLabel.startsWith("All project flows")) {
            "All project flows • recently active"
        } else {
            "Recently active"
        }
        if (active.isEmpty()) {
            return copy(
                rootLabel = "$recentLabel • waiting",
                nodes = emptyList(),
                edges = emptyList(),
                fieldDependencies = emptyList(),
            )
        }
        if (active.size == 1) return filtered(recentLabel, active)

        val adjacency = mutableMapOf<String, MutableList<String>>()
        edges.asSequence()
            .filter { it.kind != EdgeKind.READS }
            .forEach { edge ->
                adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
                adjacency.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
            }

        val keep = linkedSetOf<String>().apply { addAll(active) }

        // For every active node, retain the shortest path to its nearest other active node.
        // This is intentionally bounded: the recent filter must stay compact even in spaghetti.
        active.forEach { start ->
            val queue = ArrayDeque<String>()
            val depth = mutableMapOf(start to 0)
            val parent = mutableMapOf<String, String>()
            queue.addLast(start)
            var found: String? = null

            while (queue.isNotEmpty() && found == null) {
                val current = queue.removeFirst()
                val currentDepth = depth.getValue(current)
                if (currentDepth >= maxConnectorHops) continue
                adjacency[current].orEmpty().forEach neighborLoop@ { next ->
                    if (next in depth) return@neighborLoop
                    depth[next] = currentDepth + 1
                    parent[next] = current
                    if (next in active && next != start) {
                        found = next
                        return@neighborLoop
                    }
                    queue.addLast(next)
                }
            }

            var cursor = found
            while (cursor != null && cursor != start) {
                keep += cursor
                cursor = parent[cursor]
            }
            keep += start
        }

        return filtered(recentLabel, keep)
    }

    fun focusDownstream(
        nodeId: String,
        maxDepth: Int? = 3,
        includePossible: Boolean = false,
    ): FlowGraph {
        val origin = nodes.firstOrNull { it.id == nodeId } ?: return this
        val keep = reachable(nodeId, forward = true, maxDepth = maxDepth, includePossible = includePossible) + nodeId
        return filtered("Affected by ${origin.label}", keep)
    }

    fun focusUpstream(
        nodeId: String,
        maxDepth: Int? = 3,
        includePossible: Boolean = false,
    ): FlowGraph {
        val origin = nodes.firstOrNull { it.id == nodeId } ?: return this
        val specificDependencies = dependenciesForField(nodeId)
        if (specificDependencies.isNotEmpty()) {
            val keep = linkedSetOf(nodeId)
            specificDependencies.forEach { dependency ->
                keep += dependency.sourceNodeId
                keep += dependency.viaOperatorId
                keep += dependency.outputStateId
                keep += reachable(
                    dependency.sourceNodeId,
                    forward = false,
                    maxDepth = maxDepth,
                    includePossible = includePossible,
                )
            }
            return filtered("Causes of ${origin.label}", keep)
        }

        val keep = reachable(nodeId, forward = false, maxDepth = maxDepth, includePossible = includePossible) + nodeId
        return filtered("Causes of ${origin.label}", keep)
    }

    /**
     * Field-sensitive state focus. Known provenance is used to prune first-hop upstream/downstream
     * relationships for the chosen field; unknown-field transitions are retained conservatively.
     */
    fun focusStateField(
        stateId: String,
        fieldName: String,
        maxDepth: Int? = 3,
        includePossible: Boolean = false,
    ): FlowGraph = focusStateFieldDirectional(
        stateId = stateId,
        fieldName = fieldName,
        upstreamDepth = maxDepth,
        downstreamDepth = maxDepth,
        includePossible = includePossible,
    )

    /** Field-sensitive counterpart to [focusConnectionsDirectional]. */
    fun focusStateFieldDirectional(
        stateId: String,
        fieldName: String,
        upstreamDepth: Int? = 3,
        downstreamDepth: Int? = 3,
        includePossible: Boolean = false,
    ): FlowGraph {
        val state = node(stateId)?.takeIf { it.kind == NodeKind.STATE } ?: return this
        val transitions = stateTransitions()

        val firstUpstream = transitions.filter { transition ->
            transition.targetStateId == stateId &&
                (transition.affectedFields.isEmpty() || fieldName in transition.affectedFields)
        }.mapTo(linkedSetOf()) { it.sourceStateId }

        val firstDownstream = transitions.filter { transition ->
            transition.sourceStateId == stateId &&
                (transition.affectedFields.isEmpty() || fieldName in transition.affectedFields)
        }.mapTo(linkedSetOf()) { it.targetStateId }

        val keep = linkedSetOf(stateId)
        if (upstreamDepth == null || upstreamDepth >= 1) keep += firstUpstream
        if (downstreamDepth == null || downstreamDepth >= 1) keep += firstDownstream

        if (upstreamDepth == null || upstreamDepth > 1) {
            firstUpstream.forEach { upstream ->
                keep += reachable(
                    upstream,
                    forward = false,
                    maxDepth = upstreamDepth?.let { it - 1 },
                    includePossible = includePossible,
                )
            }
        }
        if (downstreamDepth == null || downstreamDepth > 1) {
            firstDownstream.forEach { downstream ->
                keep += reachable(
                    downstream,
                    forward = true,
                    maxDepth = downstreamDepth?.let { it - 1 },
                    includePossible = includePossible,
                )
            }
        }

        val fieldNodeIds = fieldsForState(stateId).filter { it.label == fieldName }.mapTo(linkedSetOf()) { it.id }
        keep += fieldNodeIds
        return filtered("${state.label}.$fieldName", keep)
    }

    fun impactFor(nodeId: String): FlowImpact? {
        val byId = nodesById
        val node = byId[nodeId] ?: return null
        val downstreamIds = reachable(nodeId, forward = true)
        val upstreamIds = reachable(nodeId, forward = false)

        return FlowImpact(
            node = node,
            incoming = edges.asSequence()
                .filter { it.to == nodeId }
                .mapNotNull { edge -> byId[edge.from]?.let { edge to it } }
                .toList(),
            outgoing = edges.asSequence()
                .filter { it.from == nodeId }
                .mapNotNull { edge -> byId[edge.to]?.let { edge to it } }
                .toList(),
            downstreamStates = downstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.STATE }
                .sortedBy { it.label },
            downstreamCollectors = downstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.COLLECTOR }
                .sortedBy { it.label },
            downstreamOperators = downstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.OPERATOR || it.kind == NodeKind.EXPOSURE }
                .sortedBy { it.label },
            downstreamReads = downstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.READ }
                .sortedBy { it.label },
            downstreamBehaviors = downstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.BEHAVIOR }
                .sortedBy { it.label },
            upstreamWriters = upstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.WRITER }
                .sortedBy { it.label },
            upstreamStates = upstreamIds.mapNotNull(byId::get)
                .filter { it.kind == NodeKind.STATE }
                .sortedBy { it.label },
        )
    }

    private fun filtered(label: String, keep: Set<String>): FlowGraph = copy(
        rootLabel = label,
        nodes = nodes.filter { it.id in keep },
        edges = edges.filter { it.from in keep && it.to in keep },
        fieldDependencies = fieldDependencies.filter {
            it.sourceNodeId in keep &&
                it.viaOperatorId in keep &&
                it.outputFieldId in keep &&
                it.outputStateId in keep
        },
    )

    private fun reachable(
        startId: String,
        forward: Boolean,
        maxDepth: Int? = null,
        includePossible: Boolean = true,
    ): Set<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(startId to 0)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (maxDepth != null && depth >= maxDepth) continue
            if (current != startId && node(current)?.kind in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)) {
                continue
            }

            edges.asSequence()
                .filter { edge -> if (forward) edge.from == current else edge.to == current }
                .filter { edge -> edge.kind != EdgeKind.READS }
                .filter { edge -> includePossible || !edge.isPossibleCoupling() }
                .forEach { edge ->
                    val id = if (forward) edge.to else edge.from
                    if (id == startId) return@forEach
                    if (result.add(id)) queue.addLast(id to (depth + 1))
                }
        }
        return result
    }

    private fun FlowEdge.isPossibleCoupling(): Boolean =
        kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE || confidence == CausalConfidence.POSSIBLE

    /**
     * Collapses strongly connected components in the projected causal graph. The selected node is
     * preserved as an explicit pivot; the remaining members of its cycle become a single peer node.
     */
    fun collapseCausalCycles(preserveNodeId: String? = null): FlowGraph {
        val candidateIds = nodes.asSequence()
            .filter { it.kind == NodeKind.STATE }
            .map { it.id }
            .toSet()
        if (candidateIds.size < 2) return this

        val causalEdges = edges.filter {
            it.from in candidateIds &&
                it.to in candidateIds &&
                it.kind != EdgeKind.READS &&
                !it.isPossibleCoupling()
        }

        var index = 0
        val indexById = mutableMapOf<String, Int>()
        val lowById = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<List<String>>()
        val adjacency = causalEdges.groupBy { it.from }

        fun strongConnect(id: String) {
            indexById[id] = index
            lowById[id] = index
            index++
            stack.addLast(id)
            onStack += id

            adjacency[id].orEmpty().forEach { edge ->
                val next = edge.to
                if (next !in indexById) {
                    strongConnect(next)
                    lowById[id] = minOf(lowById.getValue(id), lowById.getValue(next))
                } else if (next in onStack) {
                    lowById[id] = minOf(lowById.getValue(id), indexById.getValue(next))
                }
            }

            if (lowById[id] == indexById[id]) {
                val component = mutableListOf<String>()
                while (stack.isNotEmpty()) {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                    if (member == id) break
                }
                if (component.size > 1) components += component
            }
        }

        candidateIds.forEach { if (it !in indexById) strongConnect(it) }
        if (components.isEmpty()) return this

        val replacements = mutableMapOf<String, String>()
        val syntheticNodes = mutableListOf<FlowNode>()

        components.forEachIndexed { componentIndex, members ->
            val selectedInside = preserveNodeId != null && preserveNodeId in members
            val peers = if (selectedInside) members.filterNot { it == preserveNodeId } else members
            if (peers.isEmpty()) return@forEachIndexed

            val syntheticId = "cycle:${componentIndex}:${peers.sorted().joinToString("|").hashCode()}"
            val labels = peers.mapNotNull(nodesById::get).map { it.label }.sorted()
            syntheticNodes += FlowNode(
                id = syntheticId,
                label = if (selectedInside) "↻ cycle peers (${peers.size})" else "↻ state cycle (${peers.size})",
                detail = labels.take(6).joinToString(", ") + if (labels.size > 6) " +${labels.size - 6}" else "",
                kind = NodeKind.CYCLE,
                source = peers.asSequence().mapNotNull(nodesById::get).mapNotNull { it.source }.firstOrNull(),
                runtimeKey = null,
            )
            peers.forEach { replacements[it] = syntheticId }
        }

        if (replacements.isEmpty()) return this

        val rewrittenNodes = nodes.filterNot { it.id in replacements } + syntheticNodes
        val rewrittenEdges = edges.mapNotNull { edge ->
            val from = replacements[edge.from] ?: edge.from
            val to = replacements[edge.to] ?: edge.to
            if (from == to) return@mapNotNull null
            edge.copy(
                from = from,
                to = to,
                label = edge.label ?: if (from.startsWith("cycle:") || to.startsWith("cycle:")) "cycle" else null,
            )
        }.groupBy { Triple(it.from, it.to, it.kind) }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    affectedFields = group.flatMapTo(linkedSetOf()) { it.affectedFields },
                    label = group.mapNotNull { it.label }.distinct().take(2).joinToString(" | ").ifBlank { first.label },
                    source = group.firstNotNullOfOrNull { it.source },
                )
            }

        return copy(
            nodes = rewrittenNodes,
            edges = rewrittenEdges,
            fieldDependencies = fieldDependencies.filter {
                it.sourceNodeId !in replacements &&
                    it.viaOperatorId !in replacements &&
                    it.outputFieldId !in replacements &&
                    it.outputStateId !in replacements
            },
        )
    }

    /**
     * Collapses non-project nodes into terminal framework/dependency clusters. Clusters deliberately
     * summarize a boundary instead of exposing every library-internal Flow as a first-class node.
     */
    fun collapseExternalNodes(
        isProjectNode: (FlowNode) -> Boolean,
        classifyExternal: (FlowNode) -> String,
    ): FlowGraph {
        val external = nodes.filter { it.kind != NodeKind.READ && !isProjectNode(it) }
        if (external.isEmpty()) return this
        val groupById = external.groupBy(classifyExternal)
        val replacement = mutableMapOf<String, String>()
        val clusters = groupById.map { (group, members) ->
            val id = "cluster:$group"
            members.forEach { replacement[it.id] = id }
            FlowNode(
                id = id,
                label = "▸ $group (${members.size} nodes)",
                detail = members.map { it.label }.sorted().take(8).joinToString(", ") +
                    if (members.size > 8) " +${members.size - 8}" else "",
                kind = NodeKind.CLUSTER,
                source = null,
                runtimeKey = null,
            )
        }
        val rewrittenEdges = edges.mapNotNull { edge ->
            val from = replacement[edge.from] ?: edge.from
            val to = replacement[edge.to] ?: edge.to
            if (from == to) return@mapNotNull null
            edge.copy(from = from, to = to)
        }.groupBy { Triple(it.from, it.to, it.kind) }
            .map { (_, group) ->
                val first = group.first()
                first.copy(
                    affectedFields = group.flatMapTo(linkedSetOf()) { it.affectedFields },
                    label = group.mapNotNull { it.label }.distinct().take(2).joinToString(" | ").ifBlank { first.label },
                    source = group.firstNotNullOfOrNull { it.source },
                )
            }
        return copy(
            nodes = nodes.filterNot { it.id in replacement } + clusters,
            edges = rewrittenEdges,
            fieldDependencies = fieldDependencies.filter {
                it.sourceNodeId !in replacement &&
                    it.viaOperatorId !in replacement &&
                    it.outputFieldId !in replacement &&
                    it.outputStateId !in replacement
            },
        )
    }

    private companion object {
        const val MAX_TRANSITION_PATH = 24
    }
}
