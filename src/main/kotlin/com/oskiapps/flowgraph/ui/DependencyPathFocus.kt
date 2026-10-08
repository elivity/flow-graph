package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.EdgePathFocus
import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
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

internal fun GraphCanvas.navigationSource(model: FlowGraph, edge: FlowEdge): SourceLocation? {
    edge.source?.let { return it }
    val from = model.node(edge.from)
    val to = model.node(edge.to)

    // Prefer the endpoint that represents an actual call/write/read site rather than a state
    // declaration. This makes detailed edges naturally navigate to combine/collect/update/etc.
    sequenceOf(to, from)
        .filterNotNull()
        .firstOrNull { it.kind !in setOf(NodeKind.STATE, NodeKind.FIELD) && it.source != null }
        ?.source
        ?.let { return it }

    return from?.source ?: to?.source
}



internal fun GraphCanvas.computeEdgePathFocus(
    model: FlowGraph,
    layout: NodeLayout,
    sceneEdges: List<VisualEdge>,
    seed: VisualEdge,
): EdgePathFocus {
    val pathEdges = linkedSetOf<VisualEdge>()
    val pathNodes = linkedSetOf<String>()
    pathEdges += seed
    pathNodes += seed.fromVisualId
    pathNodes += seed.toVisualId

    fun expandable(visualId: String): Boolean {
        val sourceId = sourceNodeId(layout, visualId)
        val kind = model.node(sourceId)?.kind
        return kind !in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)
    }

    // Traverse the rendered scene, not the canonical graph. All-Flows deliberately duplicates
    // shared Flow/Compose nodes into independent lanes; using canonical ids here made selecting
    // one edge light up every visual copy/branch backed by the same variable. Visual ids keep
    // focus local to the exact lane the user clicked.
    val incomingByVisual = sceneEdges.groupBy { it.toVisualId }
    val outgoingByVisual = sceneEdges.groupBy { it.fromVisualId }

    val upstream = ArrayDeque<String>()
    upstream += seed.fromVisualId
    val upstreamSeen = mutableSetOf<String>()
    while (upstream.isNotEmpty()) {
        val current = upstream.removeFirst()
        if (!upstreamSeen.add(current)) continue
        if (current != seed.fromVisualId && !expandable(current)) continue
        incomingByVisual[current].orEmpty().forEach { edge ->
            if (edge == seed) return@forEach
            if (edge.source.kind == EdgeKind.READS && seed.source.kind != EdgeKind.READS) return@forEach
            pathEdges += edge
            pathNodes += edge.fromVisualId
            pathNodes += edge.toVisualId
            upstream += edge.fromVisualId
        }
    }

    val downstream = ArrayDeque<String>()
    downstream += seed.toVisualId
    val downstreamSeen = mutableSetOf<String>()
    while (downstream.isNotEmpty()) {
        val current = downstream.removeFirst()
        if (!downstreamSeen.add(current)) continue
        if (current != seed.toVisualId && !expandable(current)) continue
        val downstreamCandidates = outgoingByVisual[current].orEmpty().filter { edge ->
            edge != seed && (seed.source.kind != EdgeKind.READS || edge.source.kind == EdgeKind.READS)
        }
        // Do not fan out from a shared Flow/State variable. Once the selected visual path reaches
        // a branch point, stop there; the user clicked one branch, not every consumer of the same
        // canonical variable. Clicking a different outgoing edge focuses that branch instead.
        if (downstreamCandidates.size == 1) {
            val edge = downstreamCandidates.single()
            pathEdges += edge
            pathNodes += edge.fromVisualId
            pathNodes += edge.toVisualId
            downstream += edge.toVisualId
        }
    }

    return EdgePathFocus(seed = seed, edges = pathEdges, nodes = pathNodes)
}



internal fun GraphCanvas.reachableEdgeDepths(
    model: FlowGraph,
    startId: String,
    forward: Boolean,
    includePossible: Boolean,
): Map<FlowEdge, Int> {
    val result = linkedMapOf<FlowEdge, Int>()
    val nodeDepth = mutableMapOf(startId to 0)
    val queue = ArrayDeque<String>()
    queue.addLast(startId)

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentDepth = nodeDepth[current] ?: 0
        val currentNode = model.node(current)
        if (current != startId && currentNode?.kind in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)) {
            continue
        }

        model.edges.forEach { edge ->
            val matches = if (forward) edge.from == current else edge.to == current
            if (!matches) return@forEach
            if (edge.kind == EdgeKind.READS) return@forEach
            if (!includePossible && isPossibleEdge(edge)) return@forEach

            val next = if (forward) edge.to else edge.from
            val depth = currentDepth + 1
            val previousEdgeDepth = result[edge]
            if (previousEdgeDepth == null || depth < previousEdgeDepth) result[edge] = depth

            val previousNodeDepth = nodeDepth[next]
            if (previousNodeDepth == null || depth < previousNodeDepth) {
                nodeDepth[next] = depth
                queue.addLast(next)
            }
        }
    }
    return result
}



internal fun GraphCanvas.isPossibleEdge(edge: FlowEdge): Boolean =
    edge.kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE || edge.confidence == CausalConfidence.POSSIBLE



internal fun GraphCanvas.computeFocusedColumns(model: FlowGraph, selected: String): Map<String, Int> {
    val forward = nodeDistances(model, selected, forward = true)
    val backward = nodeDistances(model, selected, forward = false)
    val raw = mutableMapOf<String, Int>()
    raw[selected] = 0

    model.nodes.forEach { node ->
        if (node.id == selected || node.kind == NodeKind.READ) return@forEach
        val f = forward[node.id]
        val b = backward[node.id]
        when {
            f != null && b != null -> raw[node.id] = 0 // cycle / bidirectional relation
            f != null -> raw[node.id] = f
            b != null -> raw[node.id] = -b
        }
    }

    // One-hop possible/context nodes should sit just outside the definite cone without changing it.
    model.edges.filter(::isPossibleEdge).forEach { edge ->
        if (edge.from in raw && edge.to !in raw) raw[edge.to] = (raw[edge.from] ?: 0) + 1
        if (edge.to in raw && edge.from !in raw) raw[edge.from] = (raw[edge.to] ?: 0) - 1
    }

    val minColumn = raw.values.minOrNull() ?: 0
    return raw.mapValues { (_, value) -> value - minColumn }
}



internal fun GraphCanvas.nodeDistances(model: FlowGraph, startId: String, forward: Boolean): Map<String, Int> {
    val distances = mutableMapOf(startId to 0)
    val queue = ArrayDeque<String>()
    queue.addLast(startId)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val depth = distances.getValue(current)
        val currentNode = model.node(current)
        if (current != startId && currentNode?.kind in setOf(NodeKind.CYCLE, NodeKind.CLUSTER, NodeKind.READ)) {
            continue
        }
        model.edges.forEach { edge ->
            val matches = if (forward) edge.from == current else edge.to == current
            if (!matches || edge.kind == EdgeKind.READS || isPossibleEdge(edge)) return@forEach
            val next = if (forward) edge.to else edge.from
            if (next !in distances) {
                distances[next] = depth + 1
                queue.addLast(next)
            }
        }
    }
    distances.remove(startId)
    return distances
}


