package com.oskiapps.flowgraph.model

/**
 * Flow/State analysis must not own Compose discovery. Merge independently produced graphs by
 * stable source identity, retaining isolated UI declarations and only the real observed/static
 * state-consumption edges emitted by the Flow analysis.
 */
internal fun FlowGraph.mergeIndependentComposeGraph(compose: FlowGraph): FlowGraph {
    val mergedNodes = linkedMapOf<String, FlowNode>()
    nodes.forEach { mergedNodes[it.id] = it }
    compose.nodes.forEach { mergedNodes.putIfAbsent(it.id, it) }
    val ids = mergedNodes.keys
    val mergedEdges = linkedSetOf<FlowEdge>()
    (edges + compose.edges).forEach { edge ->
        if (edge.from in ids && edge.to in ids) mergedEdges += edge
    }
    return copy(
        nodes = mergedNodes.values.toList(),
        edges = mergedEdges.toList(),
        diagnostics = diagnostics + compose.diagnostics,
    )
}
