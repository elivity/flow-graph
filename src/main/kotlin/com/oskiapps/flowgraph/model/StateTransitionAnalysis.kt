package com.oskiapps.flowgraph.model

import com.oskiapps.flowgraph.model.FlowGraph.Companion.MAX_TRANSITION_PATH

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.stateTransitionsTo(nodeId: String): List<StateTransition> {
    stateTransitionsByTargetCache?.let { return it[nodeId].orEmpty() }
    val grouped = stateTransitions().groupBy { it.targetStateId }
    stateTransitionsByTargetCache = grouped
    return grouped[nodeId].orEmpty()
}



internal fun FlowGraph.stateTransitions(): List<StateTransition> {
    stateTransitionsCache?.let { return it }
    val computed = computeStateTransitions()
    stateTransitionsCache = computed
    return computed
}



internal fun FlowGraph.computeStateTransitions(): List<StateTransition> {
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
                // Read-only observers and Compose topology are terminal projections, not
                // state-to-state propagation. In particular, All Flows can contain a large
                // project-wide COMPOSES graph; traversing it once for every StateFlow would be
                // both semantically wrong and unnecessarily expensive. Compose is projected
                // separately by stateChangeProjection().
                if (edge.kind == EdgeKind.READS || edge.kind == EdgeKind.UPDATES_COMPOSE || edge.kind == EdgeKind.COMPOSES) {
                    return@edgeLoop
                }
                if (edge.to in step.visited) return@edgeLoop
                val nextNode = byId[edge.to] ?: return@edgeLoop
                if (nextNode.kind == NodeKind.READ || nextNode.kind == NodeKind.COMPOSABLE) return@edgeLoop

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



internal fun FlowGraph.stateChangeProjection(includeReads: Boolean = true): FlowGraph {
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
        val sourceStates = linkedMapOf<String, CausalConfidence>()
        val updateConfidence = update.effectiveConfidence()
        // Direct Compose State reads already originate at a STATE node. Older projection logic
        // only searched *upstream* from update.from, so those edges disappeared in the default
        // collapsed All Flows view and made otherwise connected Compose trees look like islands.
        if (nodesById[update.from]?.kind == NodeKind.STATE) {
            // A direct Compose-State read should stay attached to that exact state. Do not jump
            // across an upstream derived-state boundary and draw a second source -> composable
            // shortcut; the state-transition edge already explains source -> derived, and the
            // derived value can suppress recomposition when it does not change.
            sourceStates[update.from] = updateConfidence
        } else {
            upstreamStatesFor(
                startId = update.from,
                maxDepth = 8,
                initialConfidence = updateConfidence,
            ).forEach { (stateId, confidence) ->
                val existing = sourceStates[stateId]
                if (existing == null || confidence == CausalConfidence.DEFINITE) sourceStates[stateId] = confidence
            }
        }
        sourceStates.forEach { (sourceState, confidence) ->
            projectedEdges += FlowEdge(
                from = sourceState,
                to = update.to,
                kind = EdgeKind.UPDATES_COMPOSE,
                label = update.label ?: "collectAsState → recompose",
                confidence = confidence.takeIf { it == CausalConfidence.POSSIBLE },
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
        edges = mergeEquivalentProjectedEdges(projectedEdges),
    )
}


