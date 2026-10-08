package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.mergeEquivalentProjectedEdges(projected: List<FlowEdge>): List<FlowEdge> {
    data class EdgeKey(
        val from: String,
        val to: String,
        val kind: EdgeKind,
        val sourcePath: String?,
        val sourceOffset: Int?,
    )

    return projected
        .groupBy { edge ->
            // Composition is call-site topology. Two calls from the same parent declaration to
            // the same child declaration are two real composition sites and must remain two
            // visual branches. Other semantic edges intentionally collapse by endpoint/kind.
            EdgeKey(
                from = edge.from,
                to = edge.to,
                kind = edge.kind,
                sourcePath = edge.source?.file?.path.takeIf { edge.kind == EdgeKind.COMPOSES },
                sourceOffset = edge.source?.offset.takeIf { edge.kind == EdgeKind.COMPOSES },
            )
        }
        .map { (_, alternatives) ->
            val first = alternatives.first()
            val allPossible = alternatives.all { it.effectiveConfidence() == CausalConfidence.POSSIBLE }
            val labels = alternatives.mapNotNull { it.label?.takeIf(String::isNotBlank) }.distinct()
            first.copy(
                affectedFields = alternatives.flatMapTo(linkedSetOf()) { it.affectedFields },
                label = labels.joinToString(" | ").takeIf { it.isNotBlank() },
                confidence = if (allPossible) CausalConfidence.POSSIBLE else null,
                source = alternatives.firstNotNullOfOrNull { it.source },
            )
        }
}


/**
 * Overview-only semantic compaction.
 *
 * The complete analyzed graph remains untouched; this projection is meant for the project-wide
 * architectural map. It removes visual repetition that is useful only while drilling into one
 * target:
 *
 *  - repeated calls from one composable to the same child become one edge with an xN badge;
 *  - read-only observer leaves are grouped by semantic/source owner instead of becoming dozens
 *    of tiny `collect` / `first` / `observe...` clusters;
 *  - causal edge captions are reduced to semantic verbs. The detailed path is still available
 *    as soon as a node is focused because this projection is not used in detail view.
 */



internal fun FlowGraph.conciseOverviewProjection(): FlowGraph {
    if (nodes.isEmpty()) return this

    fun readOwner(node: FlowNode): String {
        node.groupLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val key = node.runtimeKey.orEmpty().substringBeforeLast('.', missingDelimiterValue = "")
        key.substringAfterLast('.').takeIf { it.isNotBlank() }?.let { return it }
        node.source?.file?.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { return it }
        return "Observers"
    }

    // Only fold READ leaves. They are useful evidence, but in a project overview they often
    // dominate the number of connected components and produce low-information labels such as
    // `collect`, `first`, or `invoke()`. Grouping keeps the fact that the state is observed and
    // leaves the exact call sites for the focused graph.
    val reads = nodes.filter { it.kind == NodeKind.READ }
    val groupedReads = reads.groupBy(::readOwner)
    val replacementByReadId = mutableMapOf<String, String>()
    val syntheticReads = mutableListOf<FlowNode>()
    val removedReadIds = linkedSetOf<String>()

    groupedReads.forEach { (owner, members) ->
        if (members.size < 2) return@forEach
        val safeOwner = owner.replace(Regex("[^A-Za-z0-9_.-]+"), "_")
        val syntheticId = "concise-read:$safeOwner"
        members.forEach { member ->
            replacementByReadId[member.id] = syntheticId
            removedReadIds += member.id
        }
        val sampleLabels = members.map { it.label }.distinct().take(3)
        val more = members.map { it.label }.distinct().size - sampleLabels.size
        val detail = buildString {
            append("Read-only observers grouped for concise overview: ")
            append(sampleLabels.joinToString(", "))
            if (more > 0) append(" +$more more")
            append(". Focus a connected state to inspect the individual read sites.")
        }
        syntheticReads += FlowNode(
            id = syntheticId,
            label = "$owner · ${members.size} reads",
            detail = detail,
            kind = NodeKind.READ,
            source = null,
            groupKey = members.firstNotNullOfOrNull { it.groupKey },
            groupLabel = owner,
            groupKind = members.firstNotNullOfOrNull { it.groupKind },
        )
    }

    val compactNodes = nodes.filter { it.id !in removedReadIds } + syntheticReads
    val compactNodeIds = compactNodes.mapTo(hashSetOf()) { it.id }

    val rewrittenEdges = edges.mapNotNull { edge ->
        val from = replacementByReadId[edge.from] ?: edge.from
        val to = replacementByReadId[edge.to] ?: edge.to
        if (from !in compactNodeIds || to !in compactNodeIds || from == to) return@mapNotNull null
        edge.copy(from = from, to = to)
    }

    data class OverviewEdgeKey(val from: String, val to: String, val kind: EdgeKind)

    val compactEdges = rewrittenEdges
        .groupBy { OverviewEdgeKey(it.from, it.to, it.kind) }
        .map { (_, alternatives) ->
            val first = alternatives.first()
            val allPossible = alternatives.all { it.effectiveConfidence() == CausalConfidence.POSSIBLE }
            val count = alternatives.size
            val conciseLabel = when (first.kind) {
                EdgeKind.COMPOSES -> if (count > 1) "composes ×$count" else null
                EdgeKind.UPDATES_COMPOSE -> "updates UI"
                EdgeKind.PROPAGATES -> {
                    val fields = alternatives.flatMapTo(linkedSetOf()) { it.affectedFields }
                    val stateToState =
                        nodesById[first.from]?.kind == NodeKind.STATE &&
                            nodesById[first.to]?.kind == NodeKind.STATE
                    if (!stateToState && !first.label.isNullOrBlank()) {
                        first.label
                    } else {
                        when {
                            fields.isEmpty() -> "changes"
                            fields.size == 1 -> "changes ${fields.first()}"
                            else -> "changes ${fields.size} fields"
                        }
                    }
                }
                EdgeKind.TRANSFORMS -> first.label ?: "transforms"
                EdgeKind.COLLECTS -> first.label ?: "collects"
                EdgeKind.TRIGGERS_WRITE -> first.label ?: "triggers"
                EdgeKind.POSSIBLY_TRIGGERS_WRITE -> first.label ?: "may trigger"
                EdgeKind.WRITES -> first.label ?: "writes"
                EdgeKind.EXPOSES -> first.label ?: "exposes"
                EdgeKind.READS -> null
                else -> first.label
            }
            first.copy(
                affectedFields = alternatives.flatMapTo(linkedSetOf()) { it.affectedFields },
                label = conciseLabel,
                confidence = if (allPossible) CausalConfidence.POSSIBLE else null,
                source = alternatives.firstNotNullOfOrNull { it.source },
            )
        }

    return copy(
        rootLabel = rootLabel.replace(" • concise", "") + " • concise",
        nodes = compactNodes,
        edges = compactEdges,
    )
}


/**
 * Compact architecture projection that keeps the coroutine machinery which explains how one
 * state depends on or triggers another state, while removing field-detail boxes that are useful
 * only in the focused graph.
 *
 * This is especially useful for Runtime branches only: runtime filtering is first performed on
 * the detailed graph, then this projection leaves a readable causal network such as
 * StateFlow -> combine/map/collector -> writer -> StateFlow -> Compose. FIELD nodes are
 * contracted into short semantic edges so they do not turn the overview into a schema diagram.
 */



internal fun FlowGraph.flowStateArchitectureProjection(includeReads: Boolean = true): FlowGraph {
    if (nodes.isEmpty()) return this

    val retainedKinds = linkedSetOf(
        NodeKind.STATE,
        NodeKind.OPERATOR,
        NodeKind.COLLECTOR,
        NodeKind.BEHAVIOR,
        NodeKind.EXPOSURE,
        NodeKind.WRITER,
        NodeKind.COMPOSABLE,
    ).apply {
        if (includeReads) add(NodeKind.READ)
    }
    val retainedNodes = nodes.filter { it.kind in retainedKinds }
    val retainedIds = retainedNodes.mapTo(linkedSetOf()) { it.id }
    if (retainedIds.isEmpty()) {
        return copy(nodes = emptyList(), edges = emptyList(), fieldDependencies = emptyList())
    }

    val resultEdges = mutableListOf<FlowEdge>()
    // Keep real semantic edges whenever both endpoints survive the projection.
    edges.asSequence()
        .filter { it.from in retainedIds && it.to in retainedIds }
        .forEach(resultEdges::add)

    // FIELD boxes carry useful provenance in detail view but add a lot of visual weight to the
    // overview. Contract only through FIELD nodes; never jump across arbitrary omitted helpers.
    val outgoing = edges.groupBy { it.from }
    retainedIds.forEach { sourceId ->
        data class Step(
            val id: String,
            val depth: Int,
            val pathKinds: List<EdgeKind>,
            val affectedFields: Set<String>,
            val confidence: CausalConfidence,
            val source: SourceLocation?,
        )

        val queue = ArrayDeque<Step>()
        outgoing[sourceId].orEmpty().forEach edgeLoop@ { edge ->
            if (edge.to in retainedIds) return@edgeLoop
            if (nodesById[edge.to]?.kind != NodeKind.FIELD) return@edgeLoop
            queue.addLast(
                Step(
                    id = edge.to,
                    depth = 1,
                    pathKinds = listOf(edge.kind),
                    affectedFields = edge.affectedFields,
                    confidence = edge.effectiveConfidence(),
                    source = edge.source,
                ),
            )
        }

        val seenDepth = mutableMapOf<String, Int>()
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            if (step.depth > 4) continue
            val previousDepth = seenDepth[step.id]
            if (previousDepth != null && previousDepth <= step.depth) continue
            seenDepth[step.id] = step.depth

            outgoing[step.id].orEmpty().forEach { edge ->
                val nextConfidence = if (
                    step.confidence == CausalConfidence.POSSIBLE ||
                    edge.effectiveConfidence() == CausalConfidence.POSSIBLE
                ) CausalConfidence.POSSIBLE else CausalConfidence.DEFINITE
                val nextFields = step.affectedFields + edge.affectedFields
                val nextKinds = step.pathKinds + edge.kind
                val nextSource = step.source ?: edge.source

                if (edge.to in retainedIds) {
                    val label = when {
                        EdgeKind.WRITES_FIELD in nextKinds -> "writes state"
                        EdgeKind.PRODUCES_FIELD in nextKinds -> "produces state"
                        else -> "updates state"
                    }
                    resultEdges += FlowEdge(
                        from = sourceId,
                        to = edge.to,
                        kind = EdgeKind.PROPAGATES,
                        affectedFields = nextFields,
                        label = label,
                        confidence = nextConfidence.takeIf { it == CausalConfidence.POSSIBLE },
                        source = nextSource,
                    )
                } else if (nodesById[edge.to]?.kind == NodeKind.FIELD) {
                    queue.addLast(
                        Step(
                            id = edge.to,
                            depth = step.depth + 1,
                            pathKinds = nextKinds,
                            affectedFields = nextFields,
                            confidence = nextConfidence,
                            source = nextSource,
                        ),
                    )
                }
            }
        }
    }

    return copy(
        rootLabel = rootLabel.replace(" • flow/state graph", "") + " • flow/state graph",
        nodes = retainedNodes,
        edges = mergeEquivalentProjectedEdges(resultEdges),
        fieldDependencies = emptyList(),
    )
}


