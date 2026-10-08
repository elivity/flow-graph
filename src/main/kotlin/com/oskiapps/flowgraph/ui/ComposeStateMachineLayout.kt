package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.StateMachinePlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_STATE_MACHINE_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_STATE_MACHINE_NODES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_W
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_STATE_MACHINE_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP
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

internal fun GraphCanvas.buildComposeStateMachinePlacement(
    model: FlowGraph,
    observedStateIds: Set<String>,
    visualPrefix: String,
): StateMachinePlacement? {
    if (observedStateIds.isEmpty()) return null

    val byId = model.nodes.associateBy { it.id }
    val graphKinds = setOf(
        NodeKind.STATE,
        NodeKind.OPERATOR,
        NodeKind.COLLECTOR,
        NodeKind.BEHAVIOR,
        NodeKind.EXPOSURE,
        NodeKind.WRITER,
    )
    val graphIds = model.nodes.asSequence()
        .filter { it.kind in graphKinds }
        .map { it.id }
        .toSet()
    val observed = observedStateIds.filterTo(linkedSetOf()) { it in graphIds }
    if (observed.isEmpty()) return null

    val causalEdges = model.edges.filter { edge ->
        edge.from in graphIds &&
            edge.to in graphIds &&
            edge.kind !in setOf(EdgeKind.READS, EdgeKind.COMPOSES, EdgeKind.UPDATES_COMPOSE)
    }
    val incoming = causalEdges.groupBy { it.to }

    data class ReverseStep(val nodeId: String, val distanceToObserved: Int)
    val distanceByNode = linkedMapOf<String, Int>()
    val queue = ArrayDeque<ReverseStep>()
    observed.forEach { nodeId ->
        distanceByNode[nodeId] = 0
        queue.addLast(ReverseStep(nodeId, 0))
    }

    while (queue.isNotEmpty() && distanceByNode.size < MAX_PROJECT_STATE_MACHINE_NODES) {
        val step = queue.removeFirst()
        if (step.distanceToObserved >= MAX_PROJECT_STATE_MACHINE_DEPTH) continue
        incoming[step.nodeId].orEmpty()
            .sortedWith(
                compareBy<FlowEdge> { byId[it.from]?.groupLabel.orEmpty() }
                    .thenBy { byId[it.from]?.source?.file?.path.orEmpty() }
                    .thenBy { byId[it.from]?.source?.offset ?: Int.MAX_VALUE }
                    .thenBy { byId[it.from]?.label.orEmpty() },
            )
            .forEach { edge ->
                if (distanceByNode.size >= MAX_PROJECT_STATE_MACHINE_NODES) return@forEach
                val nextDistance = step.distanceToObserved + 1
                val previous = distanceByNode[edge.from]
                // Shortest distance makes all Compose-driving endpoints land on the final graph
                // layer and keeps cycles bounded while still drawing their back/cross edges.
                if (previous == null || nextDistance < previous) {
                    distanceByNode[edge.from] = nextDistance
                    queue.addLast(ReverseStep(edge.from, nextDistance))
                }
            }
    }

    val relevantIds = distanceByNode.keys
    if (relevantIds.isEmpty()) return null
    val relevantEdges = causalEdges.filter { it.from in relevantIds && it.to in relevantIds }
    val maxDistance = distanceByNode.values.maxOrNull() ?: 0

    fun kindOrder(nodeId: String): Int = when (byId[nodeId]?.kind) {
        NodeKind.STATE -> 0
        NodeKind.OPERATOR -> 1
        NodeKind.BEHAVIOR -> 2
        NodeKind.COLLECTOR -> 3
        NodeKind.EXPOSURE -> 4
        NodeKind.WRITER -> 5
        else -> 9
    }

    // One visual identity per causal node is the key readability rule. The graph can branch,
    // merge and cycle without multiplying a shared StateFlow/operator into several tree copies.
    val orderedNodes = relevantIds.sortedWith(
        compareByDescending<String> { distanceByNode[it] ?: 0 }
            .thenBy(::kindOrder)
            .thenBy { byId[it]?.groupLabel.orEmpty() }
            .thenBy { byId[it]?.label.orEmpty() }
            .thenBy { byId[it]?.source?.file?.path.orEmpty() }
            .thenBy { byId[it]?.source?.offset ?: Int.MAX_VALUE },
    )
    val visualBySource = linkedMapOf<String, String>()
    val sourceByVisual = linkedMapOf<String, String>()
    orderedNodes.forEachIndexed { index, sourceId ->
        val visualId = "$visualPrefix$index"
        visualBySource[sourceId] = visualId
        sourceByVisual[visualId] = sourceId
    }

    val nodesByColumn = orderedNodes.groupBy { sourceId ->
        maxDistance - (distanceByNode[sourceId] ?: 0)
    }
    val bounds = linkedMapOf<String, Rectangle>()
    val columnStride = PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_STATE_MACHINE_HORIZONTAL_GAP
    var maxRows = 1
    nodesByColumn.toSortedMap().forEach { (column, ids) ->
        val sorted = ids.sortedWith(
            compareBy<String>(::kindOrder)
                .thenBy { byId[it]?.groupLabel.orEmpty() }
                .thenBy { byId[it]?.label.orEmpty() }
                .thenBy { byId[it]?.source?.offset ?: Int.MAX_VALUE },
        )
        maxRows = max(maxRows, sorted.size)
        sorted.forEachIndexed { row, sourceId ->
            val visualId = visualBySource.getValue(sourceId)
            bounds[visualId] = Rectangle(
                PROJECT_OVERVIEW_CLUSTER_PADDING + column * columnStride,
                PROJECT_OVERVIEW_CLUSTER_HEADER + row * (PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP),
                PROJECT_OVERVIEW_NODE_W,
                PROJECT_OVERVIEW_NODE_H,
            )
        }
    }

    // Centre sparse causal layers against the tallest one. In horizontal mode this makes
    // branches/fan-in visually obvious; after the vertical transpose it produces the equivalent
    // top-down branching graph without a second independent layout algorithm.
    val totalRowsHeight = maxRows * PROJECT_OVERVIEW_NODE_H +
        (maxRows - 1).coerceAtLeast(0) * PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP
    nodesByColumn.values.forEach { ids ->
        if (ids.size >= maxRows) return@forEach
        val columnHeight = ids.size * PROJECT_OVERVIEW_NODE_H +
            (ids.size - 1).coerceAtLeast(0) * PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP
        val dy = (totalRowsHeight - columnHeight) / 2
        ids.forEach { sourceId ->
            bounds[visualBySource.getValue(sourceId)]?.translate(0, dy)
        }
    }

    val visualEdges = relevantEdges.mapNotNull { edge ->
        val from = visualBySource[edge.from] ?: return@mapNotNull null
        val to = visualBySource[edge.to] ?: return@mapNotNull null
        VisualEdge(from, to, edge)
    }

    val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    return StateMachinePlacement(
        bounds = bounds,
        sourceNodeIdByVisualId = sourceByVisual,
        visualEdges = visualEdges,
        visualIdBySourceNodeId = visualBySource,
        sourceIds = relevantIds.toSet(),
        width = width,
        height = height,
    )
}

/**
 * Builds a tree-shaped visual projection of one Flow consumer and everything that can feed it.
 *
 * This deliberately does not memoize canonical node ids. If one StateFlow reaches a consumer
 * through two branches, it receives two visual instances. That is the same readability tradeoff
 * used by the Compose lanes: the overview is a reading aid, not a graph-theory proof of identity.
 */


