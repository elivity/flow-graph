package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.FlowLanePlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.FlowTreePlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_FLOW_INSTANCES_PER_LANE
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_FLOW_LANES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_FLOW_LANE_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_FLOW_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_W
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_VERTICAL_GAP
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

internal fun GraphCanvas.buildUpstreamFlowTreePlacement(
    model: FlowGraph,
    sinkId: String,
    visualPrefix: String,
    includeReads: Boolean,
    maxDepth: Int,
    maxInstances: Int,
): FlowTreePlacement? {
    val sink = model.node(sinkId) ?: return null
    if (sink.kind == NodeKind.COMPOSABLE) return null

    val nodeById = model.nodes.associateBy { it.id }
    val flowEdges = model.edges.asSequence()
        .filter { edge ->
            edge.kind != EdgeKind.COMPOSES &&
                edge.kind != EdgeKind.UPDATES_COMPOSE &&
                (includeReads || edge.kind != EdgeKind.READS) &&
                nodeById[edge.from]?.kind != NodeKind.COMPOSABLE &&
                nodeById[edge.to]?.kind != NodeKind.COMPOSABLE
        }
        .toList()
    val incoming = flowEdges.groupBy { it.to }
    val edgeComparator = compareBy<FlowEdge> { edge -> nodeById[edge.from]?.source?.file?.path.orEmpty() }
        .thenBy { edge -> nodeById[edge.from]?.source?.offset ?: Int.MAX_VALUE }
        .thenBy { edge -> nodeSortKey(nodeById[edge.from]?.kind ?: NodeKind.CLUSTER) }
        .thenBy { edge -> nodeById[edge.from]?.label.orEmpty() }
        .thenBy { edge -> edge.kind.ordinal }

    var sequence = 0
    var instanceCount = 0
    val sourceByVisual = linkedMapOf<String, String>()
    val upstreamChildrenByVisual = mutableMapOf<String, MutableList<String>>()
    val depthByVisual = mutableMapOf<String, Int>()
    val visualEdges = mutableListOf<VisualEdge>()
    val sourceIds = linkedSetOf<String>()

    fun newVisualId(): String = "$visualPrefix${sequence++}"

    fun createLeaf(sourceId: String, depth: Int): String? {
        if (instanceCount >= maxInstances) return null
        val visualId = newVisualId()
        instanceCount++
        sourceByVisual[visualId] = sourceId
        sourceIds += sourceId
        depthByVisual[visualId] = depth
        upstreamChildrenByVisual[visualId] = mutableListOf()
        return visualId
    }

    fun expand(sourceId: String, depth: Int, path: LinkedHashSet<String>): String? {
        val visualId = createLeaf(sourceId, depth) ?: return null
        if (depth >= maxDepth) return visualId

        val nextPath = LinkedHashSet(path)
        nextPath += sourceId
        incoming[sourceId].orEmpty()
            .sortedWith(edgeComparator)
            .forEach { edge ->
                if (instanceCount >= maxInstances) return@forEach
                val upstreamId = edge.from
                val upstreamVisual = if (upstreamId in nextPath) {
                    // Show the recursive/cyclic edge once, then stop expanding that branch.
                    createLeaf(upstreamId, depth + 1)
                } else {
                    expand(upstreamId, depth + 1, nextPath)
                }
                if (upstreamVisual != null) {
                    upstreamChildrenByVisual.getValue(visualId) += upstreamVisual
                    visualEdges += VisualEdge(upstreamVisual, visualId, edge)
                }
            }
        return visualId
    }

    val sinkVisualId = expand(sinkId, 0, linkedSetOf()) ?: return null
    val rowStride = PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_VERTICAL_GAP
    var nextLeafRow = 0
    val topByVisual = mutableMapOf<String, Int>()
    val placing = mutableSetOf<String>()

    fun place(visualId: String): Int {
        topByVisual[visualId]?.let { return it }
        if (!placing.add(visualId)) {
            val fallback = PROJECT_OVERVIEW_CLUSTER_HEADER + nextLeafRow++ * rowStride
            topByVisual[visualId] = fallback
            return fallback
        }
        val upstream = upstreamChildrenByVisual[visualId].orEmpty().filter { it !in placing }
        val y = if (upstream.isEmpty()) {
            PROJECT_OVERVIEW_CLUSTER_HEADER + nextLeafRow++ * rowStride
        } else {
            val centers = upstream.map { child -> place(child) + PROJECT_OVERVIEW_NODE_H / 2 }
            ((centers.first() + centers.last()) / 2) - PROJECT_OVERVIEW_NODE_H / 2
        }
        placing.remove(visualId)
        topByVisual[visualId] = y
        return y
    }
    place(sinkVisualId)
    sourceByVisual.keys.filter { it !in topByVisual }.forEach(::place)

    val maxUsedDepth = depthByVisual.values.maxOrNull() ?: 0
    val columnStride = PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_FLOW_HORIZONTAL_GAP
    val bounds = linkedMapOf<String, Rectangle>()
    sourceByVisual.keys.forEach { visualId ->
        val upstreamDepth = depthByVisual.getValue(visualId)
        bounds[visualId] = Rectangle(
            PROJECT_OVERVIEW_CLUSTER_PADDING + (maxUsedDepth - upstreamDepth) * columnStride,
            topByVisual.getValue(visualId),
            PROJECT_OVERVIEW_NODE_W,
            PROJECT_OVERVIEW_NODE_H,
        )
    }

    val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    return FlowTreePlacement(
        bounds = bounds,
        sourceNodeIdByVisualId = sourceByVisual,
        visualEdges = visualEdges,
        sinkVisualId = sinkVisualId,
        sourceIds = sourceIds,
        width = width,
        height = height,
    )
}

/**
 * A bare terminal call such as `collect`, `first`, `invoke()` or `setContent` is a poor
 * navigation label. Add its semantic/source owner when the node name itself does not identify
 * the surface. Screen names remain unchanged because their file/group already matches them.
 */



internal fun GraphCanvas.semanticClusterNodeLabel(node: FlowNode): String {
    val nodeLabel = node.label.trim().ifEmpty { node.id }
    val normalizedNode = nodeLabel.removeSuffix("()")
    val groupOwner = node.groupLabel?.trim()?.takeIf { candidate ->
        candidate.isNotEmpty() &&
            !candidate.removeSuffix("()").equals(normalizedNode, ignoreCase = true)
    }
    val fileOwner = node.source?.file?.name
        ?.substringBeforeLast('.')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val owner = groupOwner ?: fileOwner ?: return nodeLabel

    val normalizedOwner = owner.removeSuffix("()")
    if (normalizedNode.equals(normalizedOwner, ignoreCase = true)) return nodeLabel
    if (nodeLabel.startsWith("$owner.") || nodeLabel.startsWith("$owner ")) return nodeLabel
    return "$owner — $nodeLabel"
}

/**
 * Creates consumer-oriented Flow-only lanes for nodes that are not already explained beside a
 * Compose screen. Shared upstream nodes are repeated per sink, eliminating long cross-lane
 * fan-out edges from the project overview.
 */



internal fun GraphCanvas.buildStandaloneFlowLanePlacements(
    model: FlowGraph,
    alreadyRepresented: Set<String>,
): List<FlowLanePlacement> {
    val flowNodes = model.nodes.filter { it.kind != NodeKind.COMPOSABLE }
    if (flowNodes.isEmpty()) return emptyList()
    val flowIds = flowNodes.mapTo(linkedSetOf()) { it.id }
    val nodeById = flowNodes.associateBy { it.id }
    val overviewEdges = model.edges.filter { edge ->
        edge.kind != EdgeKind.COMPOSES &&
            edge.kind != EdgeKind.UPDATES_COMPOSE &&
            edge.from in flowIds &&
            edge.to in flowIds
    }
    val outgoing = overviewEdges.groupBy { it.from }

    val nodeComparator = compareBy<String> { id ->
        when (nodeById[id]?.kind) {
            NodeKind.COLLECTOR -> 0
            NodeKind.READ -> 1
            NodeKind.BEHAVIOR -> 2
            NodeKind.STATE -> 3
            NodeKind.EXPOSURE -> 4
            NodeKind.OPERATOR -> 5
            NodeKind.FIELD -> 6
            NodeKind.WRITER -> 7
            NodeKind.CYCLE -> 8
            NodeKind.CLUSTER -> 9
            NodeKind.COMPOSABLE, null -> 10
        }
    }.thenBy { id -> nodeById[id]?.source?.file?.path.orEmpty() }
        .thenBy { id -> nodeById[id]?.source?.offset ?: Int.MAX_VALUE }
        .thenBy { id -> nodeById[id]?.label.orEmpty() }

    val terminalSinks = flowIds.asSequence()
        .filter { it !in alreadyRepresented }
        .filter { id -> outgoing[id].orEmpty().none { edge -> edge.to in flowIds } }
        .sortedWith(nodeComparator)
        .toMutableList()

    val lanes = mutableListOf<FlowLanePlacement>()
    val represented = linkedSetOf<String>()
    represented += alreadyRepresented
    var laneSequence = 0

    fun addLane(sinkId: String): Boolean {
        if (lanes.size >= MAX_PROJECT_FLOW_LANES) return false
        val tree = buildUpstreamFlowTreePlacement(
            model = model,
            sinkId = sinkId,
            visualPrefix = "flow-lane:${laneSequence++}:",
            includeReads = true,
            maxDepth = MAX_PROJECT_FLOW_LANE_DEPTH,
            maxInstances = MAX_PROJECT_FLOW_INSTANCES_PER_LANE,
        ) ?: return true
        represented += tree.sourceIds
        val duplicateCount = tree.sourceNodeIdByVisualId.size - tree.sourceIds.size
        val label = buildString {
            val sinkNode = nodeById[sinkId]
            append("Flow lane • ").append(sinkNode?.let(::semanticClusterNodeLabel) ?: sinkId)
            append(" • ").append(tree.sourceIds.size).append(" node")
            if (tree.sourceIds.size != 1) append('s')
            if (duplicateCount > 0) {
                append(" • ").append(duplicateCount).append(" repeated instance")
                if (duplicateCount != 1) append('s')
            }
        }
        lanes += FlowLanePlacement(
            bounds = tree.bounds,
            sourceNodeIdByVisualId = tree.sourceNodeIdByVisualId,
            visualEdges = tree.visualEdges,
            width = tree.width,
            height = tree.height,
            label = label,
            sourceIds = tree.sourceIds,
        )
        return true
    }

    terminalSinks.forEach { sink ->
        if (lanes.size >= MAX_PROJECT_FLOW_LANES) return@forEach
        addLane(sink)
    }

    // Cycles and disconnected fragments may have no terminal node. Give each remaining fragment
    // a deterministic local lane instead of falling back to the old globally connected DAG.
    while (lanes.size < MAX_PROJECT_FLOW_LANES) {
        val remaining = flowIds.filter { it !in represented }.sortedWith(nodeComparator)
        if (remaining.isEmpty()) break
        val fallbackSink = remaining.first()
        if (!addLane(fallbackSink)) break
        // Safety for a malformed/empty projection that could otherwise spin forever.
        if (fallbackSink !in represented) represented += fallbackSink
    }

    return lanes
}


