package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComponentPlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS
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

internal fun GraphCanvas.layoutComponent(
    model: FlowGraph,
    ids: Set<String>,
    horizontalGap: Int,
    verticalGap: Int,
    index: Int,
    layoutNodeW: Int = nodeW,
    layoutNodeH: Int = nodeH,
    padding: Int = PROJECT_CLUSTER_PADDING,
    headerHeight: Int = PROJECT_CLUSTER_HEADER,
): ComponentPlacement {
    val nodes = model.nodes.filter { it.id in ids }
    val subGraph = model.copy(
        nodes = nodes,
        edges = model.edges.filter { it.from in ids && it.to in ids },
    )
    val rawColumns = computeColumns(subGraph)
    val minColumn = rawColumns.values.minOrNull() ?: 0
    val columns = rawColumns.mapValues { it.value - minColumn }
    val grouped = nodes.groupBy { columns[it.id] ?: 0 }.toSortedMap()
    val rowStride = layoutNodeH + verticalGap
    val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
    val contentHeight = maxRows * rowStride
    val bounds = linkedMapOf<String, Rectangle>()

    grouped.forEach { (column, columnNodes) ->
        val sorted = columnNodes.sortedWith(compareBy<FlowNode>({ nodeSortKey(it.kind) }, { it.label }))
        val groupHeight = sorted.size * rowStride
        val startY = headerHeight + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
        sorted.forEachIndexed { row, node ->
            bounds[node.id] = Rectangle(
                padding + column * (layoutNodeW + horizontalGap),
                startY + row * rowStride,
                layoutNodeW,
                layoutNodeH,
            )
        }
    }

    val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: layoutNodeW) + padding
    val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: layoutNodeH) + padding
    val states = nodes.filter { it.kind == NodeKind.STATE }
    val names = states.map { it.label }.distinct().sorted()
    val preview = names.take(3).joinToString(", ") + if (names.size > 3) ", …" else ""
    val label = buildString {
        if (states.isEmpty() && nodes.all { it.kind == NodeKind.CLUSTER }) {
            append("Boundary ").append(index)
            val boundaryPreview = nodes.map { it.label }.distinct().sorted().take(2).joinToString(", ")
            if (boundaryPreview.isNotBlank()) append(" • ").append(boundaryPreview)
        } else {
            append("Cluster ").append(index).append(" • ").append(states.size).append(" flows")
            if (preview.isNotBlank()) append(" • ").append(preview)
        }
    }
    return ComponentPlacement(bounds, width, height, label)
}

/**
 * Layout for graphs that contain real Compose parent -> child edges.
 *
 * Generic Flow layout is deliberately causal, which is perfect for StateFlow propagation but
 * poor for a composition tree: siblings end up alphabetically stacked and a shared child can
 * pull an entire UI surface into an unrelated column. Here COMPOSES edges define a left-to-right
 * hierarchy while the Flow/state plumbing is kept to the left of the first composable column.
 *
 * A child with several callers gets one deterministic primary parent for vertical placement;
 * every real COMPOSES edge is still drawn, so shared/reused composables remain visible without
 * sacrificing the readable tree shape.
 */



internal fun GraphCanvas.layoutCompositionTreeBounds(
    model: FlowGraph,
    ids: Set<String>,
    layoutNodeW: Int,
    layoutNodeH: Int,
    horizontalGap: Int,
    verticalGap: Int,
    left: Int,
    top: Int,
): Map<String, Rectangle>? {
    val byId = model.nodes.asSequence().filter { it.id in ids }.associateBy { it.id }
    val composeIds = byId.values.asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.id }
        .toCollection(linkedSetOf())
    if (composeIds.size < 2) return null

    val composeEdges = model.edges.filter {
        it.kind == EdgeKind.COMPOSES && it.from in composeIds && it.to in composeIds
    }
    if (composeEdges.isEmpty()) return null

    val outgoing = composeIds.associateWithTo(mutableMapOf()) { mutableListOf<FlowEdge>() }
    val incoming = composeIds.associateWithTo(mutableMapOf()) { mutableListOf<FlowEdge>() }
    composeEdges.forEach { edge ->
        outgoing.getValue(edge.from) += edge
        incoming.getValue(edge.to) += edge
    }

    val composeNodeComparator = compareBy<String> { byId[it]?.source?.file?.path.orEmpty() }
        .thenBy { byId[it]?.source?.offset ?: Int.MAX_VALUE }
        .thenBy { byId[it]?.label.orEmpty() }
    val composeEdgeComparator = compareBy<FlowEdge> { it.source?.file?.path.orEmpty() }
        .thenBy { it.source?.offset ?: Int.MAX_VALUE }
        .thenBy { byId[it.to]?.label.orEmpty() }

    outgoing.values.forEach { list -> list.sortWith(composeEdgeComparator) }

    // Longest-path depth on the acyclic part of the composition graph. Compose recursion is
    // legal, so any residual cycle is handled below as a separate deterministic root instead of
    // letting repeated relaxation push nodes indefinitely to the right.
    val indegree = composeIds.associateWithTo(mutableMapOf()) { incoming[it].orEmpty().size }
    val depth = composeIds.associateWithTo(mutableMapOf()) { 0 }
    val queue = ArrayDeque<String>()
    composeIds.filter { indegree.getValue(it) == 0 }.sortedWith(composeNodeComparator).forEach(queue::addLast)
    val processed = linkedSetOf<String>()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!processed.add(current)) continue
        outgoing[current].orEmpty().forEach { edge ->
            depth[edge.to] = max(depth.getValue(edge.to), depth.getValue(current) + 1)
            val nextIn = indegree.getValue(edge.to) - 1
            indegree[edge.to] = nextIn
            if (nextIn == 0) queue.addLast(edge.to)
        }
    }

    // Pick one parent solely for placement. Prefer the deepest parent that is still to the left
    // of the child, then preserve source call order. Secondary parents retain their drawn edges.
    val primaryParent = mutableMapOf<String, String>()
    composeIds.forEach { child ->
        val childDepth = depth.getValue(child)
        val candidate = incoming[child].orEmpty()
            .asSequence()
            .filter { depth.getValue(it.from) < childDepth }
            .sortedWith(
                compareByDescending<FlowEdge> { depth.getValue(it.from) }
                    .thenBy { it.source?.file?.path.orEmpty() }
                    .thenBy { it.source?.offset ?: Int.MAX_VALUE }
                    .thenBy { byId[it.from]?.label.orEmpty() },
            )
            .firstOrNull()
        if (candidate != null) primaryParent[child] = candidate.from
    }

    val primaryChildren = composeIds.associateWithTo(mutableMapOf()) { mutableListOf<String>() }
    primaryParent.forEach { (child, parent) -> primaryChildren.getValue(parent) += child }
    primaryChildren.values.forEach { children ->
        children.sortWith(compareBy<String> { child ->
            composeEdges.firstOrNull { it.from == primaryParent[child] && it.to == child }
                ?.source?.file?.path.orEmpty()
        }.thenBy { child ->
            composeEdges.firstOrNull { it.from == primaryParent[child] && it.to == child }
                ?.source?.offset ?: Int.MAX_VALUE
        }.thenBy { byId[it]?.label.orEmpty() })
    }

    val rowStride = layoutNodeH + verticalGap
    var nextLeafRow = 0
    val composeTop = mutableMapOf<String, Int>()
    val placing = mutableSetOf<String>()

    fun placeCompose(id: String): Int {
        composeTop[id]?.let { return it }
        if (!placing.add(id)) {
            val fallback = top + nextLeafRow++ * rowStride
            composeTop[id] = fallback
            return fallback
        }
        val children = primaryChildren[id].orEmpty().filter { it !in placing }
        val y = if (children.isEmpty()) {
            top + nextLeafRow++ * rowStride
        } else {
            val childCenters = children.map { child -> placeCompose(child) + layoutNodeH / 2 }
            ((childCenters.first() + childCenters.last()) / 2) - layoutNodeH / 2
        }
        placing.remove(id)
        composeTop[id] = y
        return y
    }

    val roots = composeIds.filter { it !in primaryParent }.sortedWith(composeNodeComparator)
    roots.forEachIndexed { index, root ->
        if (index > 0 && nextLeafRow > 0) nextLeafRow++ // visual gap between independent screen trees
        placeCompose(root)
    }
    // Any node left only in a recursive/cyclic fragment still receives a stable position.
    composeIds.filter { it !in composeTop }.sortedWith(composeNodeComparator).forEach { id ->
        if (nextLeafRow > 0) nextLeafRow++
        placeCompose(id)
    }

    val nonComposeNodes = byId.values.filter { it.id !in composeIds }
    val nonComposeIds = nonComposeNodes.mapTo(linkedSetOf()) { it.id }
    val nonComposeGraph = model.copy(
        nodes = nonComposeNodes,
        edges = model.edges.filter { it.from in nonComposeIds && it.to in nonComposeIds },
    )
    val rawNonComposeColumns = if (nonComposeNodes.isEmpty()) emptyMap() else computeColumns(nonComposeGraph)
    val minNonComposeColumn = rawNonComposeColumns.values.minOrNull() ?: 0
    val nonComposeColumns = rawNonComposeColumns.mapValues { it.value - minNonComposeColumn }
    val composeBaseColumn = (nonComposeColumns.values.maxOrNull()?.plus(1)) ?: 0
    val columnStride = layoutNodeW + horizontalGap
    val result = linkedMapOf<String, Rectangle>()

    composeIds.forEach { id ->
        result[id] = Rectangle(
            left + (composeBaseColumn + depth.getValue(id)) * columnStride,
            composeTop.getValue(id),
            layoutNodeW,
            layoutNodeH,
        )
    }

    if (nonComposeNodes.isNotEmpty()) {
        val forward = model.edges
            .asSequence()
            .filter { it.from in ids && it.to in ids && it.kind != EdgeKind.READS }
            .groupBy { it.from }
        val undirected = ids.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
        model.edges.forEach { edge ->
            if (edge.from !in ids || edge.to !in ids || edge.kind == EdgeKind.READS) return@forEach
            undirected.getValue(edge.from) += edge.to
            undirected.getValue(edge.to) += edge.from
        }

        fun nearestComposeY(start: String, directed: Boolean): Int? {
            val seen = mutableSetOf(start)
            val bfs = ArrayDeque<String>()
            bfs.addLast(start)
            while (bfs.isNotEmpty()) {
                val current = bfs.removeFirst()
                if (current != start && current in composeIds) return composeTop[current]
                val nextIds = if (directed) {
                    forward[current].orEmpty().asSequence().map { it.to }
                } else {
                    undirected[current].orEmpty().asSequence()
                }
                nextIds.forEach { next -> if (seen.add(next)) bfs.addLast(next) }
            }
            return null
        }

        nonComposeNodes.groupBy { nonComposeColumns[it.id] ?: 0 }.toSortedMap().forEach { (column, columnNodes) ->
            val desired = columnNodes.associateWith { node ->
                nearestComposeY(node.id, directed = true)
                    ?: nearestComposeY(node.id, directed = false)
                    ?: top
            }
            val sorted = columnNodes.sortedWith(
                compareBy<FlowNode> { desired.getValue(it) }
                    .thenBy { nodeSortKey(it.kind) }
                    .thenBy { it.label },
            )
            var cursorY = top
            sorted.forEach { node ->
                val y = max(cursorY, desired.getValue(node))
                result[node.id] = Rectangle(
                    left + column * columnStride,
                    y,
                    layoutNodeW,
                    layoutNodeH,
                )
                cursorY = y + rowStride
            }
        }
    }

    return result
}

/**
 * Dense component layout used only by the project-wide overview.
 *
 * The focused graph deliberately preserves causal columns, but that makes a large connected
 * component thousands of pixels wide when its longest dependency chain is deep. In the overview
 * we instead preserve only the *ordering* of those causal columns, then fold the ordered nodes
 * into a bounded near-square grid. This keeps upstream-ish nodes toward the left and downstream-ish
 * nodes toward the right without letting graph depth dictate physical canvas width.
 */



internal fun GraphCanvas.layoutOverviewComponent(
    model: FlowGraph,
    ids: Set<String>,
    index: Int,
): ComponentPlacement {
    val nodes = model.nodes.filter { it.id in ids }
    val subGraph = model.copy(
        nodes = nodes,
        edges = model.edges.filter { it.from in ids && it.to in ids },
    )
    val bounds = layoutCompositionTreeBounds(
        model = subGraph,
        ids = ids,
        layoutNodeW = PROJECT_OVERVIEW_NODE_W,
        layoutNodeH = PROJECT_OVERVIEW_NODE_H,
        horizontalGap = PROJECT_OVERVIEW_HORIZONTAL_GAP,
        verticalGap = PROJECT_OVERVIEW_VERTICAL_GAP,
        left = PROJECT_OVERVIEW_CLUSTER_PADDING,
        top = PROJECT_OVERVIEW_CLUSTER_HEADER,
    )?.toMutableMap() ?: run {
        val causalColumns = computeColumns(subGraph)
        val distinctColumns = causalColumns.values.distinct().sorted()
        val rankByColumn = distinctColumns.withIndex().associate { (rank, value) -> value to rank }
        val physicalColumnCount = minOf(PROJECT_OVERVIEW_MAX_SEMANTIC_COLUMNS, distinctColumns.size.coerceAtLeast(1))

        fun physicalColumn(node: FlowNode): Int {
            val rank = rankByColumn[causalColumns[node.id] ?: 0] ?: 0
            if (distinctColumns.size <= physicalColumnCount || physicalColumnCount <= 1) return rank
            return kotlin.math.round(
                rank.toDouble() * (physicalColumnCount - 1).toDouble() /
                    (distinctColumns.size - 1).coerceAtLeast(1).toDouble(),
            ).toInt().coerceIn(0, physicalColumnCount - 1)
        }

        val grouped = nodes.groupBy(::physicalColumn).toSortedMap()
        val fallbackBounds = linkedMapOf<String, Rectangle>()
        val rowStride = PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_VERTICAL_GAP
        val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
        val contentHeight = maxRows * rowStride
        grouped.forEach { (column, columnNodes) ->
            val sorted = columnNodes.sortedWith(
                compareBy<FlowNode>({ causalColumns[it.id] ?: 0 }, { nodeSortKey(it.kind) }, { it.label }),
            )
            val groupHeight = sorted.size * rowStride
            val startY = PROJECT_OVERVIEW_CLUSTER_HEADER + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
            sorted.forEachIndexed { row, node ->
                fallbackBounds[node.id] = Rectangle(
                    PROJECT_OVERVIEW_CLUSTER_PADDING + column * (PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_HORIZONTAL_GAP),
                    startY + row * rowStride,
                    PROJECT_OVERVIEW_NODE_W,
                    PROJECT_OVERVIEW_NODE_H,
                )
            }
        }
        fallbackBounds
    }

    val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val states = nodes.filter { it.kind == NodeKind.STATE }
    val names = states.map { it.label }.distinct().sorted()
    val preview = names.take(2).joinToString(", ") + if (names.size > 2) ", …" else ""
    val viewModels = nodes.asSequence()
        .filter { it.groupKind == NodeGroupKind.VIEW_MODEL }
        .mapNotNull { it.groupLabel }
        .distinct()
        .sorted()
        .toList()
    val composeSurfaces = nodes.asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.label }
        .distinct()
        .sorted()
        .toList()
    val label = buildString {
        when {
            viewModels.size == 1 -> append("ViewModel • ").append(viewModels.first())
            viewModels.isNotEmpty() -> append("ViewModels • ").append(viewModels.take(2).joinToString(" + "))
            composeSurfaces.isNotEmpty() -> append("Compose • ").append(composeSurfaces.first())
            else -> append("Cluster ").append(index)
        }
        append(" • ").append(states.size).append(" flows")
        if (composeSurfaces.isNotEmpty()) {
            append(" • UI: ").append(composeSurfaces.take(2).joinToString(", "))
            if (composeSurfaces.size > 2) append(", …")
        } else if (preview.isNotBlank()) {
            append(" • ").append(preview)
        }
    }
    return ComponentPlacement(bounds, width, height, label)
}


