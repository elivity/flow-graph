package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComponentCluster
import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_W
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_OUTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP
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

internal fun GraphCanvas.layoutNodes(model: FlowGraph): NodeLayout {
    val hadFrozenLayout = frozenLayout != null
    val raw = layoutNodesUnoriented(model)
    if (hadFrozenLayout) return raw
    if (circuitViewEnabled) return layoutCircuitLayers(raw, model)
    val projectOverview = selectedId == null && isAllProjectFlows(model.rootLabel)
    return if (projectOverviewVertical && projectOverview) {
        verticalizeProjectOverview(raw)
    } else {
        raw
    }
}

/**
 * Circuit-only Sugiyama-style layout: condense directed cycles, rank the resulting DAG,
 * and reorder each layer with alternating barycentric sweeps. The original semantic
 * graph is untouched; backwards feedback edges remain visible in the renderer.
 */



internal fun GraphCanvas.verticalizeProjectOverview(layout: NodeLayout): NodeLayout {
    if (layout.bounds.isEmpty()) return layout

    val xScale = (
        (PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_HORIZONTAL_GAP).toDouble() /
            (PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_VERTICAL_GAP).toDouble()
        ).coerceAtLeast(2.2)
    val yScale = (
        (PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_STATE_MACHINE_VERTICAL_GAP + 18).toDouble() /
            (PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_COMPOSE_HORIZONTAL_GAP).toDouble()
        ).coerceIn(0.42, 0.72)

    fun boundsOf(rects: Collection<Rectangle>): Rectangle? {
        if (rects.isEmpty()) return null
        val left = rects.minOf { it.x }
        val top = rects.minOf { it.y }
        val right = rects.maxOf { it.x + it.width }
        val bottom = rects.maxOf { it.y + it.height }
        return Rectangle(left, top, right - left, bottom - top)
    }

    val transformed = linkedMapOf<String, Rectangle>()
    val transformedClusters = mutableListOf<ComponentCluster>()
    var nextClusterY = PROJECT_OVERVIEW_OUTER_PADDING

    layout.componentClusters.forEachIndexed { clusterIndex, cluster ->
        val memberIds = layout.clusterByNodeId.asSequence()
            .filter { (_, index) -> index == clusterIndex }
            .map { (visualId, _) -> visualId }
            .filter { it in layout.bounds }
            .toList()

        if (memberIds.isEmpty()) {
            val fallback = Rectangle(
                PROJECT_OVERVIEW_OUTER_PADDING,
                nextClusterY,
                cluster.bounds.width,
                cluster.bounds.height,
            )
            transformedClusters += ComponentCluster(fallback, cluster.label)
            nextClusterY += fallback.height + PROJECT_OVERVIEW_CLUSTER_GAP
            return@forEachIndexed
        }

        val memberRects = memberIds.mapNotNull { id -> layout.bounds[id]?.let { id to it } }
        val minCenterX = memberRects.minOf { (_, r) -> r.x + r.width / 2.0 }
        val minCenterY = memberRects.minOf { (_, r) -> r.y + r.height / 2.0 }

        // First transpose around a cluster-local origin. We then normalize that temporary
        // geometry into the standard cluster padding/header box before stacking the next one.
        val local = linkedMapOf<String, Rectangle>()
        memberRects.forEach { (id, r) ->
            val oldCenterX = r.x + r.width / 2.0
            val oldCenterY = r.y + r.height / 2.0
            val newCenterX = (oldCenterY - minCenterY) * xScale
            val newCenterY = (oldCenterX - minCenterX) * yScale
            local[id] = Rectangle(
                (newCenterX - r.width / 2.0).toInt(),
                (newCenterY - r.height / 2.0).toInt(),
                r.width,
                r.height,
            )
        }

        val localContent = boundsOf(local.values) ?: return@forEachIndexed
        val shiftX = PROJECT_OVERVIEW_OUTER_PADDING + PROJECT_OVERVIEW_CLUSTER_PADDING - localContent.x
        val shiftY = nextClusterY + PROJECT_OVERVIEW_CLUSTER_HEADER - localContent.y

        local.forEach { (id, r) ->
            transformed[id] = Rectangle(
                r.x + shiftX,
                r.y + shiftY,
                r.width,
                r.height,
            )
        }

        val content = boundsOf(memberIds.mapNotNull(transformed::get)) ?: return@forEachIndexed
        val clusterBounds = Rectangle(
            PROJECT_OVERVIEW_OUTER_PADDING,
            nextClusterY,
            content.width + PROJECT_OVERVIEW_CLUSTER_PADDING * 2,
            PROJECT_OVERVIEW_CLUSTER_HEADER + content.height + PROJECT_OVERVIEW_CLUSTER_PADDING,
        )
        transformedClusters += ComponentCluster(clusterBounds, cluster.label)
        nextClusterY += clusterBounds.height + PROJECT_OVERVIEW_CLUSTER_GAP
    }

    // Project overview nodes should all belong to a semantic cluster. Keep a defensive fallback
    // for synthetic/unclustered nodes so a future node kind never disappears after switching
    // orientation; append those below the final cluster rather than widening the overview.
    val unassigned = layout.bounds.keys.filterNot { it in transformed }
    if (unassigned.isNotEmpty()) {
        val unassignedRects = unassigned.mapNotNull { id -> layout.bounds[id]?.let { id to it } }
        val minX = unassignedRects.minOf { (_, r) -> r.x }
        val minY = unassignedRects.minOf { (_, r) -> r.y }
        unassignedRects.forEach { (id, r) ->
            transformed[id] = Rectangle(
                PROJECT_OVERVIEW_OUTER_PADDING + (r.y - minY),
                nextClusterY + (r.x - minX),
                r.width,
                r.height,
            )
        }
    }

    val right = max(
        transformed.values.maxOfOrNull { it.x + it.width } ?: 0,
        transformedClusters.maxOfOrNull { it.bounds.x + it.bounds.width } ?: 0,
    ) + PROJECT_OVERVIEW_OUTER_PADDING
    val bottom = max(
        transformed.values.maxOfOrNull { it.y + it.height } ?: 0,
        transformedClusters.maxOfOrNull { it.bounds.y + it.bounds.height } ?: 0,
    ) + PROJECT_OVERVIEW_OUTER_PADDING

    return layout.copy(
        bounds = transformed,
        // READ nodes are part of project overview clusters; preserve the optional legacy box
        // only when present rather than allowing it to influence cluster placement.
        readCluster = layout.readCluster,
        componentClusters = transformedClusters,
        preferredSize = Dimension(right, bottom),
    )
}



internal fun GraphCanvas.layoutNodesUnoriented(model: FlowGraph): NodeLayout {
    frozenLayout?.let { frozen ->
        val visibleIds = model.nodes.mapTo(linkedSetOf()) { it.id }
        val visibleVisualIds = frozen.bounds.keys.filterTo(linkedSetOf()) { visualId ->
            (frozen.sourceNodeIdByVisualId[visualId] ?: visualId) in visibleIds
        }
        val visibleBounds = frozen.bounds
            .filterKeys { it in visibleVisualIds }
            .mapValues { (_, r) -> Rectangle(r) }
        val visibleClusters = frozen.componentClusters.filter { cluster ->
            visibleBounds.values.any { cluster.bounds.intersects(it) }
        }.map { ComponentCluster(Rectangle(it.bounds), it.label) }
        return NodeLayout(
            bounds = visibleBounds,
            readCluster = frozen.readCluster?.let(::Rectangle),
            componentClusters = visibleClusters,
            preferredSize = Dimension(frozen.preferredSize),
            clusterByNodeId = frozen.clusterByNodeId.filterKeys { it in visibleVisualIds },
            sourceNodeIdByVisualId = frozen.sourceNodeIdByVisualId.filterKeys { it in visibleVisualIds },
            visualEdges = frozen.visualEdges.filter {
                it.fromVisualId in visibleVisualIds && it.toVisualId in visibleVisualIds
            },
        )
    }

    val horizontalGap = if (compactLayout) 86 else colGap
    val verticalGap = if (compactLayout) 18 else rowGap
    val clusterProjectWide = selectedId == null && isAllProjectFlows(model.rootLabel)

    // In the project-wide overview every visible endpoint participates in clustering.
    // Older versions pulled READ nodes and synthetic boundary nodes into separate islands,
    // then drew long cross-island edges across empty canvas. That was the main source of the
    // huge apparent gaps. A cluster now means exactly what the user expects: if two visible
    // nodes have any graph edge between them, they belong to the same packed component.
    val mainNodes = if (clusterProjectWide) model.nodes else model.nodes.filter { it.kind != NodeKind.READ }
    val mainIds = mainNodes.mapTo(linkedSetOf()) { it.id }
    val mainGraph = model.copy(
        nodes = mainNodes,
        edges = model.edges.filter { it.from in mainIds && it.to in mainIds },
    )
    val result = linkedMapOf<String, Rectangle>()
    val componentClusters = mutableListOf<ComponentCluster>()
    val clusterByNodeId = linkedMapOf<String, Int>()
    if (clusterProjectWide) {
        layoutSeparatedProjectOverview(mainGraph)?.let { return it }

        // The overview uses substantially smaller nodes and measured dense packing. A fixed
        // 2400px shelf left huge holes between components and forced panning on 200+ node graphs.
        val components = semanticOverviewClusters(mainGraph)
        val placements = components.mapIndexed { index, ids ->
            layoutOverviewComponent(
                model = mainGraph,
                ids = ids,
                index = index + 1,
            )
        }
        val packed = packProjectComponents(placements)
        packed.forEachIndexed { clusterIndex, packedComponent ->
            val placement = packedComponent.placement
            placement.bounds.forEach { (id, r) ->
                clusterByNodeId[id] = clusterIndex
                result[id] = Rectangle(
                    r.x + packedComponent.x,
                    r.y + packedComponent.y,
                    r.width,
                    r.height,
                )
            }
            componentClusters += ComponentCluster(
                bounds = Rectangle(
                    packedComponent.x,
                    packedComponent.y,
                    placement.width,
                    placement.height,
                ),
                label = placement.label,
            )
        }
    } else {
        val compositionBounds = layoutCompositionTreeBounds(
            model = mainGraph,
            ids = mainIds,
            layoutNodeW = nodeW,
            layoutNodeH = nodeH,
            horizontalGap = horizontalGap,
            verticalGap = verticalGap,
            left = 30,
            top = 38,
        )
        if (compositionBounds != null) {
            result.putAll(compositionBounds)
        } else {
            val columns = selectedId?.takeIf { mainGraph.node(it) != null }
                ?.let { computeFocusedColumns(mainGraph, it) }
                ?: computeColumns(mainGraph)

            val grouped = mainNodes.groupBy { columns[it.id] ?: 0 }.toSortedMap()
            val maxRows = grouped.values.maxOfOrNull { it.size } ?: 1
            val rowStride = nodeH + verticalGap
            val contentHeight = maxRows * rowStride

            grouped.forEach { (column, nodes) ->
                val sorted = nodes.sortedWith(compareBy<FlowNode>({ nodeSortKey(it.kind) }, { it.label }))
                val groupHeight = sorted.size * rowStride
                val startY = 38 + ((contentHeight - groupHeight) / 2).coerceAtLeast(0)
                sorted.forEachIndexed { row, node ->
                    result[node.id] = Rectangle(
                        30 + column * (nodeW + horizontalGap),
                        startY + row * rowStride,
                        nodeW,
                        nodeH,
                    )
                }
            }
        }
    }

    if (clusterProjectWide) {
        normalizeOverviewOrigin(result, componentClusters)
    }

    val mainRight = result.values.maxOfOrNull { it.x + it.width } ?: (30 + nodeW)
    val mainBottom = result.values.maxOfOrNull { it.y + it.height } ?: (30 + nodeH)
    val reads = if (clusterProjectWide) emptyList() else model.nodes.filter { it.kind == NodeKind.READ }.sortedBy { it.label }
    var readCluster: Rectangle? = null

    if (reads.isNotEmpty()) {
        val readNodeW = if (clusterProjectWide) PROJECT_OVERVIEW_NODE_W else nodeW
        val readNodeH = if (clusterProjectWide) PROJECT_OVERVIEW_NODE_H else nodeH
        val gap = if (clusterProjectWide) PROJECT_OVERVIEW_VERTICAL_GAP else 28
        val clusterTop = mainBottom + if (clusterProjectWide) 14 else 85
        val usableWidth = max(mainRight, 30 + readNodeW)
        val horizontalStride = readNodeW + gap
        val perRow = if (clusterProjectWide) {
            ((usableWidth - 26) / horizontalStride).coerceAtLeast(1)
        } else {
            ((usableWidth - 50) / horizontalStride).coerceIn(1, 6)
        }
        val left = if (clusterProjectWide) 18 else 45
        val header = if (clusterProjectWide) 24 else 32
        val readVerticalGap = if (clusterProjectWide) PROJECT_OVERVIEW_VERTICAL_GAP else verticalGap
        reads.forEachIndexed { index, node ->
            val col = index % perRow
            val row = index / perRow
            result[node.id] = Rectangle(
                left + col * horizontalStride,
                clusterTop + header + row * (readNodeH + readVerticalGap),
                readNodeW,
                readNodeH,
            )
        }
        val readRight = result.filterKeys { id -> model.node(id)?.kind == NodeKind.READ }
            .values.maxOf { it.x + it.width }
        val readBottom = result.filterKeys { id -> model.node(id)?.kind == NodeKind.READ }
            .values.maxOf { it.y + it.height }
        readCluster = Rectangle(
            if (clusterProjectWide) 8 else 20,
            clusterTop,
            max(usableWidth + 12, readRight + 12),
            readBottom - clusterTop + if (clusterProjectWide) 10 else 28,
        )
    }

    val right = max(mainRight + 50, readCluster?.let { it.x + it.width + 25 } ?: 0)
    val bottom = max(mainBottom + 50, readCluster?.let { it.y + it.height + 25 } ?: 0)
    return NodeLayout(result, readCluster, componentClusters, Dimension(right, bottom), clusterByNodeId)
}


/**
 * Project-wide overview built as horizontal consumer lanes rather than one shared DAG.
 *
 * Compose screens and Flow-only consumers both receive tree-shaped local projections. Shared
 * StateFlow/Flow/composable nodes may be repeated in different lanes to keep connections short
 * and preserve a simple left-to-right reading order. The canonical FlowGraph remains unchanged;
 * every visual instance maps back to its source node for navigation, runtime matching and detail.
 */


