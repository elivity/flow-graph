package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.filtered(label: String, keep: Set<String>): FlowGraph = copy(
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



internal fun FlowGraph.collapseCausalCycles(preserveNodeId: String? = null): FlowGraph {
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



internal fun FlowGraph.collapseExternalNodes(
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


