package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.focusOnField(fieldId: String): FlowGraph {
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



internal fun FlowGraph.focusConnections(
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



internal fun FlowGraph.focusConnectionsDirectional(
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



internal fun FlowGraph.focusDownstream(
    nodeId: String,
    maxDepth: Int? = 3,
    includePossible: Boolean = false,
): FlowGraph {
    val origin = nodes.firstOrNull { it.id == nodeId } ?: return this
    val keep = reachable(nodeId, forward = true, maxDepth = maxDepth, includePossible = includePossible) + nodeId
    return filtered("Affected by ${origin.label}", keep)
}



internal fun FlowGraph.focusUpstream(
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



internal fun FlowGraph.focusStateField(
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



internal fun FlowGraph.focusStateFieldDirectional(
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


