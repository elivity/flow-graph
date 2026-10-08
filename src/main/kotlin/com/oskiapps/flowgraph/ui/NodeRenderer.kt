package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
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

internal fun GraphCanvas.nodeMatchesSearch(node: FlowNode): Boolean {
    val query = searchQuery
    if (query.isBlank()) return false
    val q = query.lowercase()
    return sequenceOf(
        node.label,
        node.detail,
        node.id,
        node.runtimeKey,
        node.groupLabel,
        node.groupKey,
        node.source?.file?.name,
        node.source?.file?.path,
    ).filterNotNull().any { it.lowercase().contains(q) }
}



internal fun GraphCanvas.isComposeStateNode(node: FlowNode): Boolean =
    node.kind == NodeKind.STATE && node.detail.contains("Compose state", ignoreCase = true)



internal fun GraphCanvas.composeStateShape(r: Rectangle, inset: Int = 0): Polygon {
    val x = r.x - inset
    val y = r.y - inset
    val w = r.width + inset * 2
    val h = r.height + inset * 2
    val cut = (11 + inset / 2).coerceAtMost((h / 3).coerceAtLeast(6))
    return Polygon(
        intArrayOf(x + cut, x + w - cut, x + w, x + w, x + w - cut, x + cut, x, x),
        intArrayOf(y, y, y + cut, y + h - cut, y + h, y + h, y + h - cut, y + cut),
        8,
    )
}



internal fun GraphCanvas.fillNodeShape(g: Graphics2D, node: FlowNode, r: Rectangle) {
    if (isComposeStateNode(node)) g.fillPolygon(composeStateShape(r))
    else g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16)
}



internal fun GraphCanvas.drawNodeShapeOutline(g: Graphics2D, node: FlowNode, r: Rectangle, inset: Int = 0) {
    if (isComposeStateNode(node)) {
        g.drawPolygon(composeStateShape(r, inset))
    } else {
        g.drawRoundRect(r.x - inset, r.y - inset, r.width + inset * 2, r.height + inset * 2, 16 + inset * 2, 16 + inset * 2)
    }
}



internal fun GraphCanvas.drawComposeStateBadge(g: Graphics2D, r: Rectangle, overviewCompact: Boolean, hasSource: Boolean) {
    if (overviewCompact || r.width < 130 || r.height < 54) return
    val badgeW = 24
    val badgeH = 14
    val x = r.x + r.width - badgeW - 12 - if (hasSource) sourceIconRect(r).width + 4 else 0
    val y = r.y + 8
    val dark = ((background ?: Color(45, 45, 45)).let { it.red + it.green + it.blue } / 3) < 140
    g.color = if (dark) Color(18, 52, 60, 205) else Color(236, 250, 248, 225)
    g.fillRoundRect(x, y, badgeW, badgeH, 7, 7)
    g.color = if (dark) Color(155, 245, 225) else Color(20, 105, 95)
    g.font = font.deriveFont(Font.BOLD, 8f)
    val fm = g.fontMetrics
    val label = "CS"
    g.drawString(label, x + (badgeW - fm.stringWidth(label)) / 2, y + 10)
}



internal fun GraphCanvas.sourceIconRect(r: Rectangle): Rectangle {
    val side = (r.height / 3).coerceIn(12, 19)
    return Rectangle(r.x + r.width - side - 5, r.y + 5, side, side)
}



internal fun GraphCanvas.drawSourceIcon(g: Graphics2D, r: Rectangle) {
    val icon = sourceIconRect(r)
    val oldColor = g.color
    val oldStroke = g.stroke
    val oldHint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(255, 255, 255, 235)
    g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    // The square and arrow share the same center, with uniform insets.
    val side = icon.width - 5
    val x = icon.x + (icon.width - side) / 2
    val y = icon.y + (icon.height - side) / 2
    g.drawRect(x, y, side, side)
    val left = x + 3
    val top = y + 3
    val right = x + side - 3
    val bottom = y + side - 3
    g.drawLine(left, bottom, right, top)
    g.drawLine(right - 4, top, right, top)
    g.drawLine(right, top, right, top + 4)

    g.color = oldColor
    g.stroke = oldStroke
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT)
}



internal fun GraphCanvas.drawNode(g: Graphics2D, node: FlowNode, r: Rectangle) {
    val overviewCompact = r.height <= PROJECT_OVERVIEW_NODE_H
    val color = when {
        isComposeStateNode(node) -> Color(55, 138, 145)
        else -> when (node.kind) {
        NodeKind.STATE -> Color(75, 115, 185)
        NodeKind.FIELD -> Color(68, 135, 160)
        NodeKind.WRITER -> Color(185, 95, 85)
        NodeKind.EXPOSURE -> Color(80, 145, 110)
        NodeKind.OPERATOR -> Color(120, 105, 175)
        NodeKind.COLLECTOR -> Color(185, 140, 65)
        NodeKind.BEHAVIOR -> Color(170, 105, 70)
        NodeKind.READ -> Color(105, 110, 120)
        NodeKind.COMPOSABLE -> Color(145, 82, 170)
        NodeKind.CYCLE -> Color(135, 85, 155)
        NodeKind.CLUSTER -> Color(85, 95, 110)
        }
    }
    g.color = color
    fillNodeShape(g, node, r)
    if (isComposeStateNode(node)) drawComposeStateBadge(g, r, overviewCompact, node.source != null)

    // Compose nodes show only compact live-inspection status on the graph itself. The actual
    // pixels live in the dedicated UI Preview tab so the render is not squeezed into the node.
    if (node.kind == NodeKind.COMPOSABLE && !overviewCompact && r.width >= 190) {
        drawComposeSurface(g, node, r)
    }

    if (focusedVisualEdge == null && node.id == selectedId) {
        val oldStroke = g.stroke
        g.color = UIManager.getColor("Focus.color") ?: Color.WHITE
        g.stroke = BasicStroke(3f)
        drawNodeShapeOutline(g, node, r, inset = 2)
        g.stroke = oldStroke
    }

    if (nodeMatchesSearch(node)) {
        val oldStroke = g.stroke
        val dark = ((background ?: Color(45, 45, 45)).red +
            (background ?: Color(45, 45, 45)).green +
            (background ?: Color(45, 45, 45)).blue) / 3 < 140
        val searchColor = if (dark) Color(255, 205, 82) else Color(195, 125, 18)
        g.color = withAlpha(searchColor, 58)
        g.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        drawNodeShapeOutline(g, node, r, inset = 8)
        g.color = searchColor
        g.stroke = BasicStroke(if (overviewCompact) 2.4f else 3.2f)
        drawNodeShapeOutline(g, node, r, inset = 5)
        g.stroke = oldStroke
    }

    val runtimeCount = runtimeOverlay.nodeCounts[node.id] ?: 0
    val runtimeChanges = runtimeOverlay.changeCounts[node.id] ?: 0
    val runtimeDeliveries = runtimeOverlay.deliveryCounts[node.id] ?: 0
    val runtimeCollections = runtimeOverlay.collectCounts[node.id] ?: 0
    val runtimeRequests = runtimeOverlay.emitRequestCounts[node.id] ?: 0
    val runtimeReads = runtimeOverlay.readCounts[node.id] ?: 0
    val hasPersistentRuntimeActivity = runtimeCount > 0 || runtimeChanges > 0 || runtimeDeliveries > 0 || runtimeCollections > 0 || runtimeRequests > 0
    val hasAnyRuntimeCounter = hasPersistentRuntimeActivity || runtimeReads > 0
    if (hasAnyRuntimeCounter) {
        val oldStroke = g.stroke

        // Reads are shown as counters only. Persistent live highlighting is reserved for actual
        // mutations/emissions/recompositions/deliveries so a hot Compose read loop cannot look
        // like thousands of state changes.
        val alpha = 235
        if (hasPersistentRuntimeActivity) {
            g.color = Color(70, 205, 205, alpha)
            g.stroke = BasicStroke(3.0f)
            drawNodeShapeOutline(g, node, r, inset = 7)
        }
        if (node.kind == NodeKind.COMPOSABLE && composeRenderService.snapshot(node.id) != null && !overviewCompact) {
            val surface = composeSurfaceRect(r)
            g.color = Color(70, 225, 225, 95)
            g.fillRoundRect(surface.x, surface.y, surface.width, surface.height, 9, 9)
            g.color = Color(120, 255, 255, alpha)
            g.stroke = BasicStroke(2.5f)
            g.drawRoundRect(surface.x, surface.y, surface.width, surface.height, 9, 9)
        }

        // Historical activity remains available as a compact badge and persistent outline.
        g.color = Color(70, 145, 170)
        if (overviewCompact) {
            val activity = runtimeCount + runtimeChanges + runtimeDeliveries + runtimeCollections + runtimeRequests + runtimeReads
            val badgeText = "×$activity"
            g.font = font.deriveFont(Font.BOLD, 8f)
            val badgeWidth = (g.fontMetrics.stringWidth(badgeText) + 10).coerceAtLeast(28)
            val sourceSpace = if (node.source != null) sourceIconRect(r).width + 5 else 0
            val badgeX = r.x + r.width - badgeWidth - 5 - sourceSpace
            if (badgeX >= r.x + 3) {
                g.fillRoundRect(badgeX, r.y + 4, badgeWidth, 14, 7, 7)
                g.color = Color.WHITE
                g.drawString(badgeText, badgeX + 5, r.y + 14)
            }
        } else {
            val badgeText = buildString {
                if (runtimeCount > 0) append(if (node.kind == NodeKind.COMPOSABLE) "recompose ×$runtimeCount" else "emit ×$runtimeCount")
                if (runtimeChanges > 0) { if (isNotEmpty()) append(" • "); append("change ×$runtimeChanges") }
                if (runtimeDeliveries > 0) { if (isNotEmpty()) append(" • "); append("deliver ×$runtimeDeliveries") }
                if (runtimeCollections > 0) { if (isNotEmpty()) append(" • "); append("collect ×$runtimeCollections") }
                if (runtimeRequests > 0) { if (isNotEmpty()) append(" • "); append("request ×$runtimeRequests") }
                if (runtimeReads > 0) { if (isNotEmpty()) append(" • "); append("read ×$runtimeReads") }
            }
            val badgeWidth = (badgeText.length * 6 + 12).coerceAtLeast(58)
            g.fillRoundRect(r.x + 8, r.y + r.height - 18, badgeWidth, 16, 8, 8)
            g.color = Color.WHITE
            g.font = font.deriveFont(Font.BOLD, 9f)
            g.drawString(badgeText, r.x + 13, r.y + r.height - 7)
        }
        g.stroke = oldStroke
    }

    g.color = Color.WHITE
    if (overviewCompact) {
        // Overview geometry can be internally compacted to guarantee a complete fit at the
        // hard 20% zoom. Scale text with the actual node box and never paint a second line
        // outside the rectangle (older builds did exactly that for the 34px overview nodes).
        val labelSize = (r.height * 0.34f).coerceIn(4.5f, 10f)
        val inset = (r.height / 6).coerceAtLeast(2)
        g.font = font.deriveFont(Font.BOLD, labelSize)
        val maxChars = ((r.width - inset * 2) / (labelSize * 0.58f)).toInt().coerceAtLeast(3)
        g.drawString(node.label.take(maxChars), r.x + inset, r.y + (r.height * 0.62).toInt())
        if (r.height >= 42 && r.width >= 110) {
            g.font = font.deriveFont(Font.PLAIN, (labelSize * 0.72f).coerceAtLeast(4f))
            g.drawString(node.detail.take(maxChars), r.x + inset, r.y + r.height - 5)
        }
    } else {
        g.font = font.deriveFont(Font.BOLD, 13f)
        val labelLimit = when {
            node.kind == NodeKind.COMPOSABLE -> 18
            isComposeStateNode(node) -> 24
            else -> 33
        }
        val detailLimit = if (node.kind == NodeKind.COMPOSABLE) 24 else 54
        g.drawString(node.label.take(labelLimit), r.x + 12, r.y + 26)
        g.font = font.deriveFont(Font.PLAIN, 10f)
        g.drawString(node.detail.take(detailLimit), r.x + 12, r.y + 51)
    }
    if (node.source != null) drawSourceIcon(g, r)
}



internal fun GraphCanvas.composeSurfaceRect(nodeRect: Rectangle): Rectangle =
    Rectangle(
        nodeRect.x + nodeRect.width - 78,
        nodeRect.y + 9,
        66,
        22,
    )



internal fun GraphCanvas.drawComposeSurface(g: Graphics2D, node: FlowNode, nodeRect: Rectangle) {
    val surface = composeSurfaceRect(nodeRect)
    val state = composeRenderService.state(node.id)
    val ready = state as? ComposeRenderState.Ready
    val text = when {
        ready != null -> "LIVE UI"
        state is ComposeRenderState.Loading -> "UI …"
        state is ComposeRenderState.Failed -> "UI !"
        else -> "UI"
    }

    g.color = when {
        ready != null -> Color(38, 108, 78, 225)
        state is ComposeRenderState.Loading -> Color(78, 82, 126, 225)
        state is ComposeRenderState.Failed -> Color(126, 62, 62, 225)
        else -> Color(70, 67, 82, 215)
    }
    g.fillRoundRect(surface.x, surface.y, surface.width, surface.height, 11, 11)
    g.color = Color(255, 255, 255, 210)
    g.drawRoundRect(surface.x, surface.y, surface.width, surface.height, 11, 11)
    g.font = font.deriveFont(Font.BOLD, if (text.length > 7) 7f else 8f)
    val fm = g.fontMetrics
    val tx = surface.x + ((surface.width - fm.stringWidth(text)) / 2).coerceAtLeast(4)
    val ty = surface.y + ((surface.height - fm.height) / 2) + fm.ascent
    g.drawString(text, tx, ty)
}



internal fun GraphCanvas.nodeSortKey(kind: NodeKind): Int = when (kind) {
    NodeKind.WRITER -> 0
    NodeKind.FIELD -> 1
    NodeKind.STATE -> 2
    NodeKind.EXPOSURE -> 3
    NodeKind.OPERATOR -> 4
    NodeKind.COLLECTOR -> 5
    NodeKind.BEHAVIOR -> 6
    NodeKind.COMPOSABLE -> 7
    NodeKind.CYCLE -> 8
    NodeKind.CLUSTER -> 9
    NodeKind.READ -> 10
}


