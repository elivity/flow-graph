package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.EdgeDirection
import com.oskiapps.flowgraph.ui.GraphCanvas.EdgePathStyle
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.DRAW_CULL_MARGIN
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

internal fun GraphCanvas.drawGraphScene(g: Graphics2D, model: FlowGraph, updateBounds: Boolean) {
    if (circuitViewEnabled) drawCircuitBackdrop(g)

    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val circuitRouting = if (circuitViewEnabled) buildCircuitRouting(model, layout.bounds) else null
    circuitRoutingCache = circuitRouting
    if (updateBounds) {
        boundsById.clear()
        boundsById.putAll(layout.bounds)
    }

    // Graphics2D reports the clip in the current user/model coordinate space even after the
    // canvas translate/scale. Cull off-screen graph primitives before doing any expensive text,
    // stroke, path, glow, or image work. This matters a lot now that the overview intentionally
    // duplicates shared Flow/Compose nodes into many readable lanes.
    val visibleClip = g.clipBounds?.let { Rectangle(it).apply { grow(DRAW_CULL_MARGIN, DRAW_CULL_MARGIN) } }
    fun isVisible(bounds: Rectangle): Boolean = visibleClip == null || visibleClip.intersects(bounds)

    layout.componentClusters.forEachIndexed { clusterIndex, cluster ->
        if (!isVisible(cluster.bounds)) return@forEachIndexed
        val oldStroke = g.stroke
        val active = clusterIndex == activeClusterIndex && isAllProjectFlows(model.rootLabel) && selectedId == null
        val border = if (active) {
            UIManager.getColor("List.selectionBackground")
                ?: UIManager.getColor("Focus.color")
                ?: UIManager.getColor("Separator.foreground")
                ?: Color.GRAY
        } else {
            UIManager.getColor("Separator.foreground") ?: Color.GRAY
        }
        if (active) {
            g.color = withAlpha(border, 24)
            g.fillRoundRect(cluster.bounds.x, cluster.bounds.y, cluster.bounds.width, cluster.bounds.height, 18, 18)
        }
        g.color = withAlpha(border, if (active) 235 else 145)
        g.stroke = BasicStroke(if (active) 2.8f else 1.25f)
        g.drawRoundRect(cluster.bounds.x, cluster.bounds.y, cluster.bounds.width, cluster.bounds.height, 18, 18)
        g.stroke = oldStroke
        g.font = font.deriveFont(Font.BOLD, if (active) 12f else 11f)
        g.color = if (active) border else (UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY)
        g.drawString(cluster.label.take(110), cluster.bounds.x + 14, cluster.bounds.y + 20)
    }

    layout.readCluster?.let { cluster ->
        if (!isVisible(cluster)) return@let
        val oldStroke = g.stroke
        g.color = UIManager.getColor("Separator.foreground") ?: Color.GRAY
        g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
        g.drawRoundRect(cluster.x, cluster.y, cluster.width, cluster.height, 18, 18)
        g.stroke = oldStroke
        g.font = font.deriveFont(Font.BOLD, 11f)
        g.drawString("READS / OBSERVERS — no tracked state write discovered", cluster.x + 14, cluster.y + 20)
    }

    val sceneEdges = if (layout.visualEdges.isNotEmpty()) {
        layout.visualEdges
    } else {
        model.edges.map { edge -> VisualEdge(edge.from, edge.to, edge) }
    }

    val edgeFocus = focusedVisualEdge
        ?.takeIf { focused -> sceneEdges.any { it == focused } }
        ?.let { computeEdgePathFocus(model, layout, sceneEdges, it) }
    val selected = if (edgeFocus == null) selectedId else null
    val forwardDepths = selected?.let {
        reachableEdgeDepths(model, it, forward = true, includePossible = includePossibleTraversal)
    }.orEmpty()
    val backwardDepths = selected?.let {
        reachableEdgeDepths(model, it, forward = false, includePossible = includePossibleTraversal)
    }.orEmpty()
    val directForwardEdges = selected?.let { id ->
        model.edges.filterTo(linkedSetOf()) {
            it.from == id && it.kind != EdgeKind.READS && !isPossibleEdge(it)
        }
    }.orEmpty()
    val directBackwardEdges = selected?.let { id ->
        model.edges.filterTo(linkedSetOf()) {
            it.to == id && it.kind != EdgeKind.READS && !isPossibleEdge(it)
        }
    }.orEmpty()

    if (selected != null) {
        drawDirectionLegend(g)
    }

    sceneEdges.forEach { visualEdge ->
        val edge = visualEdge.source
        val a = layout.bounds[visualEdge.fromVisualId] ?: return@forEach
        val b = layout.bounds[visualEdge.toVisualId] ?: return@forEach
        if (visibleClip != null) {
            // Use the union of both node rectangles as a conservative path bound. It keeps a
            // long edge whose line crosses the viewport even when both endpoint nodes are just
            // outside it, while avoiding all drawArrow work for distant lanes.
            val edgePaintBounds = a.union(b).apply { grow(DRAW_CULL_MARGIN, DRAW_CULL_MARGIN) }
            if (!visibleClip.intersects(edgePaintBounds)) return@forEach
        }
        val possibleContext = selected != null && isPossibleEdge(edge) &&
            (edge.from == selected || edge.to == selected ||
                forwardDepths.keys.any { it.from == edge.from || it.to == edge.from || it.from == edge.to || it.to == edge.to } ||
                backwardDepths.keys.any { it.from == edge.from || it.to == edge.from || it.from == edge.to || it.to == edge.to })
        val direction = when {
            possibleContext && !includePossibleTraversal -> EdgeDirection.POSSIBLE
            edge in forwardDepths && edge in backwardDepths -> EdgeDirection.BOTH
            edge in forwardDepths -> EdgeDirection.FORWARD
            edge in backwardDepths -> EdgeDirection.BACKWARD
            else -> EdgeDirection.NONE
        }
        val direct = edge in directForwardEdges || edge in directBackwardEdges
        val directionDistance = minOf(
            forwardDepths[edge] ?: Int.MAX_VALUE,
            backwardDepths[edge] ?: Int.MAX_VALUE,
        ).takeUnless { it == Int.MAX_VALUE }
        val runtimeCount = runtimeOverlay.edgeCounts[edge.from to edge.to] ?: 0
        val fromCluster = layout.clusterByNodeId[visualEdge.fromVisualId]
        val toCluster = layout.clusterByNodeId[visualEdge.toVisualId]
        val interCluster = fromCluster != null && toCluster != null && fromCluster != toCluster
        val pathStyle = when {
            edgeFocus == null -> EdgePathStyle.NONE
            visualEdge == edgeFocus.seed -> EdgePathStyle.SEED
            visualEdge in edgeFocus.edges -> EdgePathStyle.PATH
            else -> EdgePathStyle.DIMMED
        }
        drawArrow(
            g, a, b, edge, direction, direct, directionDistance, selected != null,
            runtimeCount, interCluster, pathStyle,
        )
    }
    val searchActive = searchQuery.isNotBlank()
    layout.bounds.forEach { (visualId, bounds) ->
        if (!isVisible(bounds)) return@forEach
        val sourceId = sourceNodeId(layout, visualId)
        val node = model.node(sourceId) ?: return@forEach
        val hasLiveActivity = hasRuntimeActivity(sourceId)
        val searchMatch = !searchActive || nodeMatchesSearch(node)
        val edgeDimmed = edgeFocus != null && visualId !in edgeFocus.nodes
        val searchDimmed = searchActive && !searchMatch
        val recentDimmed = recentFocusEnabled && sourceId !in recentFocusIds
        // Edge focus is visual-instance specific and must win over canonical runtime activity.
        // Otherwise another copy of the same hot StateFlow in a different lane stays bright and
        // looks as though it belongs to the clicked flow. Search keeps the old live-activity
        // exception, but an explicit edge selection dims every node outside that visual path.
        val shouldDim = recentDimmed || edgeDimmed || (searchDimmed && !hasLiveActivity)
        if (shouldDim) {
            val nodeGraphics = g.create() as Graphics2D
            try {
                // Match the selected-node causal-focus treatment: non-matching search results
                // remain spatially visible, but look inactive rather than competing with hits.
                nodeGraphics.composite = AlphaComposite.SrcOver.derive(0.22f)
                drawNode(nodeGraphics, node, bounds)
            } finally {
                nodeGraphics.dispose()
            }
        } else {
            drawNode(g, node, bounds)
            if (edgeFocus != null && visualId in edgeFocus.nodes) {
                val oldStroke = g.stroke
                val isSeedEndpoint = visualId == edgeFocus.seed.fromVisualId || visualId == edgeFocus.seed.toVisualId
                val accent = if (isSeedEndpoint) {
                    Color(245, 194, 78)
                } else {
                    Color(72, 220, 190)
                }
                g.color = accent
                g.stroke = BasicStroke(if (isSeedEndpoint) 2.6f else 1.6f)
                g.drawRoundRect(bounds.x - 4, bounds.y - 4, bounds.width + 8, bounds.height + 8, 20, 20)
                g.stroke = oldStroke
            }
        }
    }
}



internal fun GraphCanvas.hasRuntimeActivity(nodeId: String): Boolean =
    (runtimeOverlay.nodeCounts[nodeId] ?: 0) > 0 ||
        (runtimeOverlay.changeCounts[nodeId] ?: 0) > 0 ||
        (runtimeOverlay.deliveryCounts[nodeId] ?: 0) > 0 ||
        (runtimeOverlay.collectCounts[nodeId] ?: 0) > 0 ||
        (runtimeOverlay.emitRequestCounts[nodeId] ?: 0) > 0


