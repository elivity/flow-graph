package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComponentCluster
import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_LANE_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_FLOW_LANE_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_FLOW_SECTION_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_W
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_OUTER_PADDING
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

internal fun GraphCanvas.layoutSeparatedProjectOverview(model: FlowGraph): NodeLayout? {
    val composeIds = model.nodes.asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.id }
        .toCollection(linkedSetOf())
    val composeEdges = model.edges.filter {
        it.kind == EdgeKind.COMPOSES && it.from in composeIds && it.to in composeIds
    }

    val result = linkedMapOf<String, Rectangle>()
    val sourceByVisual = linkedMapOf<String, String>()
    val visualEdges = mutableListOf<VisualEdge>()
    val componentClusters = mutableListOf<ComponentCluster>()
    val clusterByVisualId = linkedMapOf<String, Int>()

    /*
     * The project overview is intentionally NOT one canonical DAG. A globally de-duplicated
     * Flow graph looks correct mathematically but becomes a spider web as soon as a few shared
     * sources feed many collectors/screens. The overview therefore uses the same readability
     * rule as Compose: build independent horizontal consumer lanes and repeat shared nodes in
     * each lane. Canonical identity is preserved only in sourceByVisual for navigation/runtime.
     */
    val composeLanes = if (composeIds.isEmpty()) {
        emptyList()
    } else {
        buildComposeLanePlacements(model, composeIds, composeEdges)
    }

    var laneTop = PROJECT_OVERVIEW_OUTER_PADDING
    var laneClusterIndex = 0
    val composeCoveredFlowIds = linkedSetOf<String>()

    // Screen-oriented lanes come first. Each lane already contains its own hierarchical
    // upstream Flow/State forest immediately to the left of the Compose tree.
    composeLanes.forEach { lane ->
        val laneLeft = PROJECT_OVERVIEW_OUTER_PADDING
        lane.bounds.forEach { (visualId, r) ->
            result[visualId] = Rectangle(
                r.x + laneLeft,
                r.y + laneTop,
                r.width,
                r.height,
            )
            sourceByVisual[visualId] = lane.sourceNodeIdByVisualId.getValue(visualId)
            clusterByVisualId[visualId] = laneClusterIndex
        }
        visualEdges += lane.visualEdges
        componentClusters += ComponentCluster(
            bounds = Rectangle(laneLeft, laneTop, lane.width, lane.height),
            label = lane.label,
        )
        composeCoveredFlowIds += lane.localFlowSourceIds
        laneTop += lane.height + PROJECT_OVERVIEW_COMPOSE_LANE_GAP
        laneClusterIndex++
    }

    // Keep Flow-only sinks visible too, but render them with the exact same visual grammar:
    // source -> transforms -> state/collector/read sink, duplicated per consumer when needed.
    // Nodes already represented beside Compose screens are not given a redundant standalone
    // lane unless they are needed as upstream context for a separate uncovered sink.
    val standaloneFlowLanes = buildStandaloneFlowLanePlacements(
        model = model,
        alreadyRepresented = composeCoveredFlowIds,
    )
    if (standaloneFlowLanes.isNotEmpty() && composeLanes.isNotEmpty()) {
        laneTop += PROJECT_OVERVIEW_FLOW_SECTION_GAP - PROJECT_OVERVIEW_COMPOSE_LANE_GAP
    }
    standaloneFlowLanes.forEach { lane ->
        val laneLeft = PROJECT_OVERVIEW_OUTER_PADDING
        lane.bounds.forEach { (visualId, r) ->
            result[visualId] = Rectangle(
                r.x + laneLeft,
                r.y + laneTop,
                r.width,
                r.height,
            )
            sourceByVisual[visualId] = lane.sourceNodeIdByVisualId.getValue(visualId)
            clusterByVisualId[visualId] = laneClusterIndex
        }
        visualEdges += lane.visualEdges
        componentClusters += ComponentCluster(
            bounds = Rectangle(laneLeft, laneTop, lane.width, lane.height),
            label = lane.label,
        )
        laneTop += lane.height + PROJECT_OVERVIEW_FLOW_LANE_GAP
        laneClusterIndex++
    }

    if (result.isEmpty()) return null

    normalizeOverviewOrigin(result, componentClusters)
    val right = max(
        result.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W,
        componentClusters.maxOfOrNull { it.bounds.x + it.bounds.width } ?: 0,
    ) + PROJECT_OVERVIEW_OUTER_PADDING
    val bottom = max(
        result.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H,
        componentClusters.maxOfOrNull { it.bounds.y + it.bounds.height } ?: 0,
    ) + PROJECT_OVERVIEW_OUTER_PADDING

    return NodeLayout(
        bounds = result,
        readCluster = null,
        componentClusters = componentClusters,
        preferredSize = Dimension(right, bottom),
        clusterByNodeId = clusterByVisualId,
        sourceNodeIdByVisualId = sourceByVisual,
        visualEdges = visualEdges,
    )
}



internal fun GraphCanvas.normalizeOverviewOrigin(
    nodes: MutableMap<String, Rectangle>,
    clusters: MutableList<ComponentCluster>,
) {
    if (nodes.isEmpty()) return
    val allRects = nodes.values + clusters.map { it.bounds }
    val minX = allRects.minOf { it.x }
    val minY = allRects.minOf { it.y }
    val dx = PROJECT_OVERVIEW_OUTER_PADDING - minX
    val dy = PROJECT_OVERVIEW_OUTER_PADDING - minY
    nodes.values.forEach { it.translate(dx, dy) }
    clusters.forEach { it.bounds.translate(dx, dy) }
}


