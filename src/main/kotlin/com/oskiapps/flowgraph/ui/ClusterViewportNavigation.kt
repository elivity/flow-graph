package com.oskiapps.flowgraph.ui


import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
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

internal fun GraphCanvas.publishClusterNavigation(layout: NodeLayout) {
    val overview = isAllProjectFlows(graph?.rootLabel) && selectedId == null
    if (!overview) {
        setActiveCluster(null)
        onClustersChanged(emptyList())
        return
    }
    onClustersChanged(
        layout.componentClusters.mapIndexed { index, cluster ->
            ClusterNavigationEntry(index = index, label = cluster.label)
        },
    )
    SwingUtilities.invokeLater { updateActiveClusterFromViewport() }
}

/**
 * Select the overview cluster that best represents the current camera position. Prefer the
 * cluster under the viewport centre; while crossing whitespace use the cluster with the
 * largest visible intersection. This makes scrolling/panning behave like a document outline.
 */



internal fun GraphCanvas.updateActiveClusterFromViewport() {
    val model = graph ?: run { setActiveCluster(null); return }
    if (!isAllProjectFlows(model.rootLabel) || selectedId != null) {
        setActiveCluster(null)
        return
    }
    val viewport = viewport() ?: return
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    if (layout.componentClusters.isEmpty()) {
        setActiveCluster(null)
        return
    }

    val view = viewport.viewRect
    val center = Point(view.x + view.width / 2, view.y + view.height / 2)
    data class Candidate(val index: Int, val bounds: Rectangle, val overlap: Long, val centerDistance2: Long)

    val candidates = layout.componentClusters.mapIndexed { index, cluster ->
        val scaled = Rectangle(
            canvasPaddingX + kotlin.math.round(cluster.bounds.x * zoom).toInt(),
            canvasPaddingY + kotlin.math.round(cluster.bounds.y * zoom).toInt(),
            kotlin.math.ceil(cluster.bounds.width * zoom).toInt().coerceAtLeast(1),
            kotlin.math.ceil(cluster.bounds.height * zoom).toInt().coerceAtLeast(1),
        )
        val intersection = scaled.intersection(view)
        val overlap = if (intersection.isEmpty) {
            0L
        } else {
            intersection.width.toLong() * intersection.height.toLong()
        }
        val dx = (scaled.centerX() - center.x).toLong()
        val dy = (scaled.centerY() - center.y).toLong()
        Candidate(index, scaled, overlap, dx * dx + dy * dy)
    }

    val centered = candidates.filter { it.bounds.contains(center) }
    val visible = candidates.filter { it.overlap > 0L }
    val best = when {
        centered.isNotEmpty() -> centered.minWithOrNull(
            compareBy<Candidate> { it.bounds.width.toLong() * it.bounds.height.toLong() }
                .thenBy { it.centerDistance2 },
        )
        visible.isNotEmpty() -> visible.maxWithOrNull(
            compareBy<Candidate> { it.overlap }.thenBy { -it.centerDistance2 },
        )
        else -> candidates.minByOrNull { it.centerDistance2 }
    }
    setActiveCluster(best?.index)
}



internal fun GraphCanvas.setActiveCluster(index: Int?) {
    if (activeClusterIndex == index) return
    activeClusterIndex = index
    onActiveClusterChanged(index)
    repaint()
}



internal fun GraphCanvas.focusCluster(clusterIndex: Int) {
    cancelPendingWheelZoom()
    val model = graph ?: return
    val viewport = viewport() ?: return
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val cluster = layout.componentClusters.getOrNull(clusterIndex) ?: return
    setActiveCluster(clusterIndex)
    val rect = cluster.bounds

    val availableW = (viewport.extentSize.width - CLUSTER_NAV_FIT_PADDING * 2).coerceAtLeast(1)
    val availableH = (viewport.extentSize.height - CLUSTER_NAV_FIT_PADDING * 2).coerceAtLeast(1)
    val targetZoom = minOf(
        availableW.toDouble() / rect.width.coerceAtLeast(1).toDouble(),
        availableH.toDouble() / rect.height.coerceAtLeast(1).toDouble(),
        1.0,
    ).coerceIn(currentMinZoom(), currentMaxZoom())
    val modelCenterX = rect.x + rect.width / 2.0
    val modelCenterY = rect.y + rect.height / 2.0
    val viewportCenter = Point(viewport.extentSize.width / 2, viewport.extentSize.height / 2)
    val zoomWillChange = kotlin.math.abs(targetZoom - zoom) >= 0.0001
    if (zoomWillChange) {
        // Anchor the chosen cluster center to the viewport center. The delayed post-layout
        // correction inside applyZoomAroundModelPoint therefore reinforces this jump instead
        // of restoring the previous viewport position.
        applyZoomAroundModelPoint(targetZoom, modelCenterX, modelCenterY, viewportCenter)
    } else {
        val centerX = canvasPaddingX + (modelCenterX * zoom).toInt()
        val centerY = canvasPaddingY + (modelCenterY * zoom).toInt()
        viewport.viewPosition = clampViewPosition(
            viewport,
            centerX - viewport.extentSize.width / 2,
            centerY - viewport.extentSize.height / 2,
        )
        repaint()
    }
}


