package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.RuntimeLensTarget
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

internal fun GraphCanvas.drawRuntimeEventLens(g: Graphics2D, model: FlowGraph) {
    val now = runtimeReferenceMillis ?: System.currentTimeMillis()
    val candidate = runtimeOverlay.lastActivityAtMillis.asSequence()
        .mapNotNull { (nodeId, atMillis) ->
            val age = (now - atMillis).coerceAtLeast(0L)
            if (age > RUNTIME_ACTIVITY_COOLDOWN_MS) return@mapNotNull null
            val node = model.node(nodeId) ?: return@mapNotNull null
            val layout = cachedLayout ?: layoutNodes(model).also { cachedLayout = it }
            val bounds = firstBoundsForSource(layout, nodeId) ?: return@mapNotNull null
            RuntimeLensTarget(node = node, bounds = bounds, ageMs = age)
        }
        .minByOrNull { it.ageMs }
        ?: return

    val node = candidate.node
    val modelBounds = candidate.bounds
    val ageMs = candidate.ageMs
    val screenBounds = Rectangle(
        canvasPaddingX + (modelBounds.x * zoom).toInt(),
        canvasPaddingY + (modelBounds.y * zoom).toInt(),
        (modelBounds.width * zoom).toInt().coerceAtLeast(1),
        (modelBounds.height * zoom).toInt().coerceAtLeast(1),
    )
    if (screenBounds.width >= EVENT_LENS_MIN_NODE_WIDTH_PX && screenBounds.height >= EVENT_LENS_MIN_NODE_HEIGHT_PX) return
    val visible = visibleRect.takeIf { it.width > 0 && it.height > 0 } ?: Rectangle(0, 0, width, height)
    if (!visible.intersects(screenBounds)) return

    val gap = 18
    var lensX = screenBounds.x + screenBounds.width + gap
    if (lensX + EVENT_LENS_W > visible.x + visible.width - 8) {
        lensX = screenBounds.x - EVENT_LENS_W - gap
    }
    lensX = lensX.coerceIn(visible.x + 8, (visible.x + visible.width - EVENT_LENS_W - 8).coerceAtLeast(visible.x + 8))
    var lensY = screenBounds.y + screenBounds.height / 2 - EVENT_LENS_H / 2
    lensY = lensY.coerceIn(visible.y + 8, (visible.y + visible.height - EVENT_LENS_H - 8).coerceAtLeast(visible.y + 8))
    val lens = Rectangle(lensX, lensY, EVENT_LENS_W, EVENT_LENS_H)

    val activityStrength = (1f - ageMs.toFloat() / RUNTIME_ACTIVITY_COOLDOWN_MS.toFloat()).coerceIn(0f, 1f)
    val accent = Color(72, 220, 205, (150 + 105 * activityStrength).toInt().coerceIn(0, 255))
    val nodeColor = when {
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
    val dark = ((background ?: Color(45, 45, 45)).let { it.red + it.green + it.blue } / 3) < 140
    val cardBackground = if (dark) Color(24, 28, 34, 244) else Color(247, 249, 250, 247)
    val textColor = if (dark) Color(240, 245, 248) else Color(32, 38, 42)
    val secondary = if (dark) Color(176, 188, 194) else Color(83, 94, 101)

    val oldStroke = g.stroke
    // Callout trace from the tiny live node to the magnified card.
    val nodeCx = screenBounds.x + screenBounds.width / 2
    val nodeCy = screenBounds.y + screenBounds.height / 2
    val targetX = if (lens.centerX >= nodeCx) lens.x else lens.x + lens.width
    val targetY = lens.y + lens.height / 2
    val elbowX = (nodeCx + targetX) / 2
    g.color = Color(accent.red, accent.green, accent.blue, (80 + 120 * activityStrength).toInt().coerceIn(0, 255))
    g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.drawLine(nodeCx, nodeCy, elbowX, nodeCy)
    g.drawLine(elbowX, nodeCy, elbowX, targetY)
    g.drawLine(elbowX, targetY, targetX, targetY)
    g.color = accent
    g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.drawLine(nodeCx, nodeCy, elbowX, nodeCy)
    g.drawLine(elbowX, nodeCy, elbowX, targetY)
    g.drawLine(elbowX, targetY, targetX, targetY)

    // Professional retro HUD card: restrained glow, node-color rail and live status lamp.
    g.color = Color(0, 0, 0, if (dark) 80 else 38)
    g.fillRoundRect(lens.x + 5, lens.y + 6, lens.width, lens.height, 16, 16)
    g.color = cardBackground
    g.fillRoundRect(lens.x, lens.y, lens.width, lens.height, 16, 16)
    g.color = Color(accent.red, accent.green, accent.blue, 48)
    g.stroke = BasicStroke(5f)
    g.drawRoundRect(lens.x - 2, lens.y - 2, lens.width + 4, lens.height + 4, 18, 18)
    g.color = accent
    g.stroke = BasicStroke(1.6f)
    g.drawRoundRect(lens.x, lens.y, lens.width, lens.height, 16, 16)
    g.color = nodeColor
    g.fillRoundRect(lens.x + 8, lens.y + 10, 6, lens.height - 20, 3, 3)

    val event = runtimeOverlay.lastEventByNode[node.id]
    g.color = accent
    g.fillOval(lens.x + 24, lens.y + 16, 8, 8)
    g.font = font.deriveFont(Font.BOLD, 9f)
    g.drawString("LIVE", lens.x + 38, lens.y + 24)
    val ageText = if (ageMs < 1_000) "${ageMs} ms" else String.format("%.1f s", ageMs / 1000.0)
    val ageWidth = g.fontMetrics.stringWidth(ageText)
    g.color = secondary
    g.drawString(ageText, lens.x + lens.width - ageWidth - 14, lens.y + 24)

    g.color = textColor
    g.font = font.deriveFont(Font.BOLD, 15f)
    g.drawString(node.label.take(34), lens.x + 24, lens.y + 49)
    g.color = secondary
    g.font = font.deriveFont(Font.PLAIN, 10f)
    val detail = node.detail.take(52)
    g.drawString(detail, lens.x + 24, lens.y + 67)

    val eventText = buildString {
        event?.kind?.takeIf { it.isNotBlank() }?.let { append(it.lowercase().replace('_', ' ')) }
        event?.instanceId?.let {
            if (isNotEmpty()) append("  •  ")
            append("#$it")
        }
        event?.valueSummary?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append("  •  ")
            append(it)
        }
    }.take(55)
    if (eventText.isNotBlank()) {
        g.color = accent
        g.font = font.deriveFont(Font.PLAIN, 9f)
        g.drawString(eventText, lens.x + 24, lens.y + 83)
    }
    g.stroke = oldStroke
}


