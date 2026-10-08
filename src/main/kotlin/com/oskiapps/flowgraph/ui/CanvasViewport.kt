package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComponentCluster
import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
import com.oskiapps.flowgraph.ui.GraphCanvas.ViewAnchor
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.FIT_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_ZOOM
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MIN_VIRTUAL_CANVAS_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MIN_ZOOM
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_MIN_ZOOM
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.WHEEL_ZOOM_SENSITIVITY
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.ZOOM_STEP
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

internal fun GraphCanvas.freezeCurrentLayout() {
    val model = graph ?: return
    val source = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    frozenLayout = copyLayout(source)
}



internal fun GraphCanvas.clearFrozenLayout() {
    frozenLayout = null
    cachedLayout = null
}



internal fun GraphCanvas.captureViewportAnchor(nodeId: String): ViewAnchor? {
    val model = graph ?: return null
    val viewport = viewport() ?: return null
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val rect = firstBoundsForSource(layout, nodeId) ?: return null
    val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
    val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
    return ViewAnchor(
        nodeId = nodeId,
        viewportOffsetX = centerX - viewport.viewPosition.x,
        viewportOffsetY = centerY - viewport.viewPosition.y,
    )
}



internal fun GraphCanvas.restoreViewportAnchor(anchor: ViewAnchor) {
    val model = graph ?: return
    val viewport = viewport() ?: return
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val rect = firstBoundsForSource(layout, anchor.nodeId) ?: return
    val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
    val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
    viewport.viewPosition = clampViewPosition(
        viewport,
        centerX - anchor.viewportOffsetX,
        centerY - anchor.viewportOffsetY,
    )
    repaint()
}



internal fun GraphCanvas.copyLayout(source: NodeLayout): NodeLayout = NodeLayout(
    bounds = source.bounds.mapValues { (_, r) -> Rectangle(r) },
    readCluster = source.readCluster?.let(::Rectangle),
    componentClusters = source.componentClusters.map { ComponentCluster(Rectangle(it.bounds), it.label) },
    preferredSize = Dimension(source.preferredSize),
    clusterByNodeId = source.clusterByNodeId.toMap(),
    sourceNodeIdByVisualId = source.sourceNodeIdByVisualId.toMap(),
    visualEdges = source.visualEdges.toList(),
)



internal fun GraphCanvas.relayout() {
    val model = graph ?: return
    val layout = layoutNodes(model)
    cachedLayout = layout
    unscaledPreferredSize = layout.preferredSize
    updateScaledPreferredSize()
    publishClusterNavigation(layout)
    revalidate()
    repaint()
}



internal fun GraphCanvas.centerNode(nodeId: String) {
    val model = graph ?: return
    val viewport = viewport() ?: return
    val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
    val rect = firstBoundsForSource(layout, nodeId) ?: return
    val centerX = canvasPaddingX + ((rect.x + rect.width / 2.0) * zoom).toInt()
    val centerY = canvasPaddingY + ((rect.y + rect.height / 2.0) * zoom).toInt()
    viewport.viewPosition = clampViewPosition(
        viewport,
        centerX - viewport.extentSize.width / 2,
        centerY - viewport.extentSize.height / 2,
    )
    repaint()
}

/**
 * Cluster navigation is a bookmark-style jump: fit the chosen cluster to the viewport (never
 * enlarge beyond 100%) and center it. This makes a cluster readable even when All Flows was
 * previously fitted at a very small overview zoom.
 */



internal fun GraphCanvas.zoomIn() {
    cancelPendingWheelZoom()
    setZoom(zoom * ZOOM_STEP, viewportCenterInCanvas())
}



internal fun GraphCanvas.zoomOut() {
    cancelPendingWheelZoom()
    setZoom(zoom / ZOOM_STEP, viewportCenterInCanvas())
}



internal fun GraphCanvas.resetZoom() {
    cancelPendingWheelZoom()
    setZoom(1.0, viewportCenterInCanvas())
}



internal fun GraphCanvas.fitToViewport() {
    cancelPendingWheelZoom()
    val viewport = viewport() ?: return
    if (unscaledPreferredSize.width <= 0 || unscaledPreferredSize.height <= 0) return
    val availableW = (viewport.extentSize.width - FIT_PADDING * 2).coerceAtLeast(1)
    val availableH = (viewport.extentSize.height - FIT_PADDING * 2).coerceAtLeast(1)
    val fitted = minOf(
        availableW.toDouble() / unscaledPreferredSize.width.toDouble(),
        availableH.toDouble() / unscaledPreferredSize.height.toDouble(),
        1.0,
    ).coerceIn(currentMinZoom(), currentMaxZoom())

    // Applying a zoom can invalidate the JScrollPane. Settle the new canvas size
    // before centering; otherwise the first click uses old viewport dimensions.
    zoom = fitted
    ++zoomApplyGeneration
    updateScaledPreferredSize()
    revalidate()
    viewport.parent?.doLayout()
    viewport.doLayout()
    onZoomChanged(zoom)
    val contentW = unscaledPreferredSize.width * zoom
    val contentH = unscaledPreferredSize.height * zoom
    viewport.viewPosition = clampViewPosition(
        viewport,
        kotlin.math.round(canvasPaddingX + contentW / 2.0 - viewport.extentSize.width / 2.0).toInt(),
        kotlin.math.round(canvasPaddingY + contentH / 2.0 - viewport.extentSize.height / 2.0).toInt(),
    )
    repaint()
}



internal fun GraphCanvas.queueWheelZoom(wheelRotation: Double, anchorInCanvas: Point) {
    val viewport = viewport()
    if (viewport == null) {
        val factor = Math.exp(-wheelRotation * WHEEL_ZOOM_SENSITIVITY)
        setZoom(zoom * factor, anchorInCanvas)
        return
    }

    // Capture the logical point under the *latest* pointer position. Wheel deltas are additive
    // in log-space, so several raw wheel/trackpad events collapse into one smooth scale change.
    pendingWheelZoomExponent = (pendingWheelZoomExponent -
        wheelRotation * WHEEL_ZOOM_SENSITIVITY).coerceIn(-0.8, 0.8)
    pendingWheelModelX = (anchorInCanvas.x.toDouble() - canvasPaddingX) / zoom
    pendingWheelModelY = (anchorInCanvas.y.toDouble() - canvasPaddingY) / zoom
    pendingWheelAnchorInViewport = SwingUtilities.convertPoint(this, anchorInCanvas, viewport)

    if (!wheelZoomTimer.isRunning) wheelZoomTimer.start()
}



internal fun GraphCanvas.flushPendingWheelZoom() {
    val exponent = pendingWheelZoomExponent
    val anchorInViewport = pendingWheelAnchorInViewport
    val modelX = pendingWheelModelX
    val modelY = pendingWheelModelY
    pendingWheelZoomExponent = 0.0
    pendingWheelAnchorInViewport = null

    if (kotlin.math.abs(exponent) < 0.000001) return
    val targetZoom = (zoom * Math.exp(exponent))
        .coerceIn(currentMinZoom(), currentMaxZoom())
    applyZoomAroundModelPoint(targetZoom, modelX, modelY, anchorInViewport)
}



internal fun GraphCanvas.cancelPendingWheelZoom() {
    if (wheelZoomTimer.isRunning) wheelZoomTimer.stop()
    pendingWheelZoomExponent = 0.0
    pendingWheelAnchorInViewport = null
    // Invalidate any delayed anchor correction from an older zoom frame.
    zoomApplyGeneration++
}



internal fun GraphCanvas.setZoom(requestedZoom: Double, anchorInCanvas: Point?) {
    val oldZoom = zoom
    val viewport = viewport()
    val anchor = anchorInCanvas ?: viewportCenterInCanvas() ?: Point(0, 0)
    val modelX = (anchor.x.toDouble() - canvasPaddingX) / oldZoom
    val modelY = (anchor.y.toDouble() - canvasPaddingY) / oldZoom
    val anchorInViewport = viewport?.let { SwingUtilities.convertPoint(this, anchor, it) }
    applyZoomAroundModelPoint(requestedZoom, modelX, modelY, anchorInViewport)
}



internal fun GraphCanvas.applyZoomAroundModelPoint(
    requestedZoom: Double,
    modelX: Double,
    modelY: Double,
    anchorInViewport: Point?,
) {
    val newZoom = requestedZoom.coerceIn(currentMinZoom(), currentMaxZoom())
    if (kotlin.math.abs(newZoom - zoom) < 0.0001) return

    val viewport = viewport()
    val generation = ++zoomApplyGeneration
    zoom = newZoom
    updateScaledPreferredSize()
    revalidate()
    onZoomChanged(zoom)

    fun restoreMouseAnchor() {
        if (viewport == null || anchorInViewport == null) return
        val targetX = kotlin.math.round(canvasPaddingX + modelX * zoom - anchorInViewport.x).toInt()
        val targetY = kotlin.math.round(canvasPaddingY + modelY * zoom - anchorInViewport.y).toInt()
        viewport.viewPosition = clampViewPosition(viewport, targetX, targetY)
    }

    // Do one immediate correction and at most one post-layout correction for this visual frame.
    // A newer wheel frame invalidates the older callback, preventing stale invokeLater work from
    // pulling the viewport back and forth while a fast trackpad gesture is still in progress.
    restoreMouseAnchor()
    SwingUtilities.invokeLater {
        if (generation != zoomApplyGeneration) return@invokeLater
        restoreMouseAnchor()
        repaint()
    }
    repaint()
}



internal fun GraphCanvas.currentMinZoom(): Double =
    if (isAllProjectFlows(graph?.rootLabel) && selectedId == null) PROJECT_OVERVIEW_MIN_ZOOM else MIN_ZOOM

/**
 * Keep enough spatial context at maximum zoom to see roughly two neighboring nodes at once.
 * This prevents the + button / mouse wheel from turning the graph into a single-node close-up.
 */



internal fun GraphCanvas.currentMaxZoom(): Double = MAX_ZOOM



internal fun GraphCanvas.updateScaledPreferredSize() {
    val viewport = viewport()
    val desiredPaddingX = (viewport?.extentSize?.width ?: 0).coerceAtLeast(MIN_VIRTUAL_CANVAS_PADDING)
    val desiredPaddingY = (viewport?.extentSize?.height ?: 0).coerceAtLeast(MIN_VIRTUAL_CANVAS_PADDING)
    val deltaPaddingX = desiredPaddingX - canvasPaddingX
    val deltaPaddingY = desiredPaddingY - canvasPaddingY
    val oldViewPosition = viewport?.viewPosition?.let(::Point)

    canvasPaddingX = desiredPaddingX
    canvasPaddingY = desiredPaddingY
    preferredSize = Dimension(
        (unscaledPreferredSize.width * zoom).toInt().coerceAtLeast(1) + canvasPaddingX * 2,
        (unscaledPreferredSize.height * zoom).toInt().coerceAtLeast(1) + canvasPaddingY * 2,
    )

    // Padding is camera space, not graph space. If a resize changes its size, move the viewport
    // by exactly the same delta so the graph does not visually jump. The first layout uses the
    // same rule, placing the old (0,0) graph view at the new padded content origin.
    if (viewport != null && oldViewPosition != null && (deltaPaddingX != 0 || deltaPaddingY != 0)) {
        val target = clampViewPosition(
            viewport,
            oldViewPosition.x + deltaPaddingX,
            oldViewPosition.y + deltaPaddingY,
        )
        viewport.viewPosition = target
        SwingUtilities.invokeLater {
            viewport.viewPosition = clampViewPosition(viewport, target.x, target.y)
        }
    }
}



internal fun GraphCanvas.viewport(): JViewport? =
    SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport



internal fun GraphCanvas.viewportCenterInCanvas(): Point? {
    val viewport = viewport() ?: return null
    return Point(
        viewport.viewPosition.x + viewport.extentSize.width / 2,
        viewport.viewPosition.y + viewport.extentSize.height / 2,
    )
}



internal fun GraphCanvas.clampViewPosition(viewport: JViewport, x: Int, y: Int): Point {
    val maxX = (preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
    val maxY = (preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
    return Point(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}



internal fun GraphCanvas.toModelPoint(point: Point): Point = Point(
    ((point.x - canvasPaddingX) / zoom).toInt(),
    ((point.y - canvasPaddingY) / zoom).toInt(),
)


