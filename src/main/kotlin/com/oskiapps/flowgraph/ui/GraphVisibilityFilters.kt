package com.oskiapps.flowgraph.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer
import com.oskiapps.flowgraph.model.*
import com.oskiapps.flowgraph.service.FlowGraphProjectService
import com.oskiapps.flowgraph.service.ComposeRenderService
import com.oskiapps.flowgraph.service.ComposeRenderSnapshot
import com.oskiapps.flowgraph.service.ComposeRenderState
import com.oskiapps.flowgraph.service.LiveTraceService
import com.oskiapps.flowgraph.service.LiveTraceStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

internal fun FlowGraphPanel.reloadVisibilityFilters() {
    canvas.clearFrozenLayout()
    renderActive(autoFit = true)
}



internal fun FlowGraphPanel.isComposeStateNode(node: FlowNode): Boolean =
    node.kind == NodeKind.STATE && node.detail.contains("Compose state", ignoreCase = true)

/**
 * Flow -> UI impact lens.
 *
 * Work from the semantic state-change projection so this behaves identically whether the
 * canvas is currently showing the collapsed State Changes view or the expanded operator
 * plumbing. A composable qualifies only when a coroutine-backed STATE can reach the state
 * that owns an UPDATES_COMPOSE edge. COMPOSES edges are deliberately not traversed: a child
 * is not labelled Flow-influenced merely because its parent observes state.
 *
 * Compose State may sit between a StateFlow and the composable; it is therefore allowed as a
 * downstream state, but never as a root source for this filter.
 */



internal fun FlowGraphPanel.coroutineInfluencedComposableIds(graph: FlowGraph): Set<String> {
    if (graph.nodes.none { it.kind == NodeKind.COMPOSABLE }) return emptySet()

    val semantic = graph.stateChangeProjection(includeReads = false)
    val coroutineStateIds = semantic.nodes.asSequence()
        .filter { it.kind == NodeKind.STATE && !isComposeStateNode(it) }
        .mapTo(linkedSetOf()) { it.id }
    if (coroutineStateIds.isEmpty()) return emptySet()

    val stateAdjacency = semantic.edges.asSequence()
        .filter { it.kind == EdgeKind.PROPAGATES }
        .groupBy({ it.from }, { it.to })

    val reachableStates = linkedSetOf<String>().apply { addAll(coroutineStateIds) }
    val queue = ArrayDeque<String>().apply { coroutineStateIds.forEach(::addLast) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        stateAdjacency[current].orEmpty().forEach { next ->
            if (reachableStates.add(next)) queue.addLast(next)
        }
    }

    return semantic.edges.asSequence()
        .filter { edge ->
            edge.kind == EdgeKind.UPDATES_COMPOSE &&
                edge.from in reachableStates &&
                semantic.node(edge.to)?.kind == NodeKind.COMPOSABLE
        }
        .mapTo(linkedSetOf()) { it.to }
}

/**
 * Structural rather than paint-only filtering: unrelated Compose nodes and all edges incident
 * to them are removed, so layout can repack the remaining StateFlow -> affected-UI graph.
 * Non-Compose nodes are intentionally left to the normal semantic-layer/operator filters.
 */



internal fun FlowGraphPanel.keepOnlyCoroutineInfluencedComposables(graph: FlowGraph): FlowGraph {
    if (!flowInfluencedComposablesOnly.isSelected) return graph

    val influencedComposeIds = coroutineInfluencedComposableIds(graph)
    val keptNodes = graph.nodes.filter { node ->
        node.kind != NodeKind.COMPOSABLE || node.id in influencedComposeIds
    }
    val keptIds = keptNodes.mapTo(hashSetOf()) { it.id }
    val keptEdges = graph.edges.filter { it.from in keptIds && it.to in keptIds }
    val keptDependencies = graph.fieldDependencies.filter { dependency ->
        dependency.sourceNodeId in keptIds &&
            dependency.viaOperatorId in keptIds &&
            dependency.outputFieldId in keptIds &&
            dependency.outputStateId in keptIds
    }
    return graph.copy(
        rootLabel = graph.rootLabel.replace(" • Flow-influenced UI", "") + " • Flow-influenced UI",
        nodes = keptNodes,
        edges = keptEdges,
        fieldDependencies = keptDependencies,
    )
}

/**
 * Filters any rendered graph into three independently switchable semantic layers:
 * coroutine Flow/StateFlow, Compose State, and Compose UI. Non-primary helper nodes
 * (operators, collectors, writers, reads, etc.) are retained only when they are reachable from
 * a visible State/Flow without crossing a hidden State/Compose boundary. This avoids orphan
 * plumbing while keeping a selected Flow lane meaningful when full-chain mode is enabled.
 */



internal fun FlowGraphPanel.applyOverviewVisibilityFilters(graph: FlowGraph): FlowGraph {
    val keepComposeState = showComposeState.isSelected
    val keepCoroutineFlows = showCoroutineFlows.isSelected
    val keepComposeViews = showComposeViews.isSelected

    if (keepComposeState && keepCoroutineFlows && keepComposeViews) return graph

    val primaryIds = graph.nodes.asSequence()
        .filter { it.kind == NodeKind.STATE || it.kind == NodeKind.COMPOSABLE }
        .mapTo(linkedSetOf()) { it.id }
    val selectedPrimaryIds = graph.nodes.asSequence()
        .filter { node ->
            when {
                node.kind == NodeKind.COMPOSABLE -> keepComposeViews
                node.kind == NodeKind.STATE && isComposeStateNode(node) -> keepComposeState
                node.kind == NodeKind.STATE -> keepCoroutineFlows
                else -> false
            }
        }
        .mapTo(linkedSetOf()) { it.id }

    if (selectedPrimaryIds.isEmpty()) {
        return graph.copy(nodes = emptyList(), edges = emptyList(), fieldDependencies = emptyList())
    }

    val hiddenPrimaryIds = primaryIds - selectedPrimaryIds
    val keepIds = linkedSetOf<String>().apply { addAll(selectedPrimaryIds) }

    // Compose-only mode is intentionally pure: no collector/operator tails hanging off the
    // left. As soon as either State/Flow layer is enabled, preserve its local supporting chain.
    val selectedStateIds = selectedPrimaryIds.filterTo(linkedSetOf()) { id ->
        graph.node(id)?.kind == NodeKind.STATE
    }
    if (selectedStateIds.isNotEmpty()) {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        graph.edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableListOf() } += edge.to
            adjacency.getOrPut(edge.to) { mutableListOf() } += edge.from
        }
        val queue = ArrayDeque<String>()
        selectedStateIds.forEach(queue::addLast)
        val visited = linkedSetOf<String>().apply { addAll(selectedStateIds) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            adjacency[current].orEmpty().forEach { next ->
                if (next in hiddenPrimaryIds || !visited.add(next)) return@forEach
                val node = graph.node(next) ?: return@forEach
                keepIds += next
                // Visible composables are terminal for support discovery. Their own UI tree is
                // already present through selectedPrimaryIds/COMPOSES and should not become a
                // bridge into unrelated helper plumbing.
                if (node.kind != NodeKind.COMPOSABLE) queue.addLast(next)
            }
        }
    }

    val keptNodes = graph.nodes.filter { it.id in keepIds }
    val keptIdSet = keptNodes.mapTo(hashSetOf()) { it.id }
    val keptEdges = graph.edges.filter { it.from in keptIdSet && it.to in keptIdSet }
    val keptDependencies = graph.fieldDependencies.filter { dependency ->
        dependency.sourceNodeId in keptIdSet &&
            dependency.viaOperatorId in keptIdSet &&
            dependency.outputFieldId in keptIdSet &&
            dependency.outputStateId in keptIdSet
    }
    return graph.copy(
        nodes = keptNodes,
        edges = keptEdges,
        fieldDependencies = keptDependencies,
    )
}


