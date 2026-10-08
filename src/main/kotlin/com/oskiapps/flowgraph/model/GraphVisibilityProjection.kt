package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.withoutReads(): FlowGraph {
    val readIds = nodes.filter { it.kind == NodeKind.READ }.mapTo(linkedSetOf()) { it.id }
    if (readIds.isEmpty()) return this
    return copy(
        nodes = nodes.filter { it.id !in readIds },
        edges = edges.filter { it.from !in readIds && it.to !in readIds },
    )
}

/**
 * Removes inferred/possible causal connections from the rendered graph without changing the
 * underlying analysis result. Primary Flow/State and Compose nodes are retained even when the
 * removed relation was their only connection; helper/operator nodes that become completely
 * orphaned are dropped so a relayout can pack the remaining graph tightly.
 */



internal fun FlowGraph.withoutPossibleConnections(): FlowGraph {
    if (edges.none { it.isPossibleCoupling() }) return this

    val keptEdges = edges.filterNot { it.isPossibleCoupling() }
    val incidentIds = keptEdges.flatMapTo(linkedSetOf()) { edge -> listOf(edge.from, edge.to) }
    val keptNodes = nodes.filter { node ->
        node.kind == NodeKind.STATE ||
            node.kind == NodeKind.COMPOSABLE ||
            node.id in incidentIds
    }
    val keptIds = keptNodes.mapTo(hashSetOf()) { it.id }
    val safeEdges = keptEdges.filter { it.from in keptIds && it.to in keptIds }
    val keptDependencies = fieldDependencies.filter { dependency ->
        dependency.sourceNodeId in keptIds &&
            dependency.viaOperatorId in keptIds &&
            dependency.outputFieldId in keptIds &&
            dependency.outputStateId in keptIds
    }
    return copy(
        nodes = keptNodes,
        edges = safeEdges,
        fieldDependencies = keptDependencies,
    )
}

/**
 * Removes visual Compose framework/library call-site nodes while preserving the project
 * composition hierarchy. A chain such as:
 *
 *   ProjectScreen -> LazyColumn -> Row -> ProjectCard -> Text
 *
 * becomes:
 *
 *   ProjectScreen -> ProjectCard
 *
 * Framework call sites are the synthetic nodes emitted by the analyzer for AndroidX Compose
 * calls. Source @Composable functions from the project use a @compose runtime key and are never
 * removed here. COMPOSES edges are contracted through any number of removed framework nodes so
 * descendants do not become disconnected islands.
 */



internal fun FlowGraph.withoutExternalComposeCallSites(): FlowGraph {
    val removedIds = nodes.asSequence()
        .filter { node ->
            node.kind == NodeKind.COMPOSABLE &&
                node.runtimeKey == null &&
                (node.id.startsWith("compose-call:") || node.detail.startsWith("Compose UI call"))
        }
        .mapTo(linkedSetOf()) { it.id }
    if (removedIds.isEmpty()) return this

    val keptNodes = nodes.filter { it.id !in removedIds }
    val keptIds = keptNodes.mapTo(hashSetOf()) { it.id }
    val outgoingCompose = edges.asSequence()
        .filter { it.kind == EdgeKind.COMPOSES }
        .groupBy { it.from }

    val contractedComposeEdges = linkedSetOf<FlowEdge>()
    val projectComposeIds = keptNodes.asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.id }
        .toList()

    data class ComposeStep(val id: String, val source: SourceLocation?)

    projectComposeIds.forEach { sourceId ->
        val visitedRemoved = mutableSetOf<String>()
        val queue = ArrayDeque<ComposeStep>()
        outgoingCompose[sourceId].orEmpty().forEach { edge ->
            when {
                edge.to in removedIds -> {
                    if (visitedRemoved.add(edge.to)) queue.addLast(ComposeStep(edge.to, edge.source))
                }
                edge.to in keptIds -> contractedComposeEdges += edge
            }
        }

        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            outgoingCompose[step.id].orEmpty().forEach { edge ->
                when {
                    edge.to in removedIds -> {
                        if (visitedRemoved.add(edge.to)) {
                            queue.addLast(ComposeStep(edge.to, edge.source ?: step.source))
                        }
                    }
                    edge.to in keptIds -> {
                        contractedComposeEdges += FlowEdge(
                            from = sourceId,
                            to = edge.to,
                            kind = EdgeKind.COMPOSES,
                            label = edge.label ?: "composes",
                            source = edge.source ?: step.source,
                        )
                    }
                }
            }
        }
    }

    val nonComposeEdges = edges.filter { edge ->
        edge.kind != EdgeKind.COMPOSES && edge.from in keptIds && edge.to in keptIds
    }
    val composeEdges = contractedComposeEdges
        .filter { it.from in keptIds && it.to in keptIds }
        .distinctBy { edge ->
            listOf(edge.from, edge.to, edge.source?.file?.path.orEmpty(), edge.source?.offset?.toString().orEmpty())
        }
    val keptDependencies = fieldDependencies.filter { dependency ->
        dependency.sourceNodeId in keptIds &&
            dependency.viaOperatorId in keptIds &&
            dependency.outputFieldId in keptIds &&
            dependency.outputStateId in keptIds
    }

    return copy(
        nodes = keptNodes,
        edges = nonComposeEdges + composeEdges,
        fieldDependencies = keptDependencies,
    )
}


