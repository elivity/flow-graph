package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.readsFrom(stateId: String): List<FlowNode> {
    val byId = nodesById
    return edges.asSequence()
        .filter { it.from == stateId && it.kind == EdgeKind.READS }
        .mapNotNull { byId[it.to] }
        .filter { it.kind == NodeKind.READ }
        .distinctBy { it.id }
        .sortedBy { it.label }
        .toList()
}



internal fun FlowGraph.transitionsFrom(stateId: String): List<StateTransition> =
    stateTransitions().filter { it.sourceStateId == stateId }



internal fun FlowGraph.transitionsTo(stateId: String): List<StateTransition> =
    stateTransitions().filter { it.targetStateId == stateId }



internal fun FlowGraph.impactFor(nodeId: String): FlowImpact? {
    val byId = nodesById
    val node = byId[nodeId] ?: return null
    val downstreamIds = reachable(nodeId, forward = true)
    val upstreamIds = reachable(nodeId, forward = false)
    // A field belongs to its containing state; that structural FIELD_OF link
    // does not mean the containing state is a *derived* downstream state.
    // Keep traversing through it to discover actual downstream consumers.
    val containingStateIds = if (node.kind == NodeKind.FIELD) {
        edges.asSequence()
            .filter { it.from == nodeId && it.kind == EdgeKind.FIELD_OF }
            .map { it.to }
            .toSet()
    } else {
        emptySet()
    }

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
            .filter { it.kind == NodeKind.STATE && it.id !in containingStateIds }
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



internal fun FlowGraph.reachable(
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


