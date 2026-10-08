package com.oskiapps.flowgraph.ui

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

internal fun GraphCanvas.magnifierBounds(visible: Rectangle, topLeft: Boolean): Rectangle {
    val lensW = MAGNIFIER_W.coerceAtMost((visible.width - MAGNIFIER_MARGIN * 2).coerceAtLeast(120))
    val lensH = MAGNIFIER_H.coerceAtMost((visible.height - MAGNIFIER_MARGIN * 2).coerceAtLeast(100))
    val x = if (topLeft) {
        visible.x + MAGNIFIER_MARGIN
    } else {
        visible.x + visible.width - lensW - MAGNIFIER_MARGIN
    }
    val y = if (topLeft) {
        visible.y + MAGNIFIER_MARGIN
    } else {
        visible.y + visible.height - lensH - MAGNIFIER_MARGIN - 38
    }
    return Rectangle(x, y, lensW, lensH)
}



internal fun GraphCanvas.drawMouseMagnifier(g: Graphics2D, model: FlowGraph) {
    val visible = visibleRect.takeIf { it.width > 0 && it.height > 0 } ?: Rectangle(0, 0, width, height)

    // Bottom-right is the magnifier's stable/home region. If the pointer enters that region,
    // move the painted lens to the opposite corner so the lens never obscures the graph under
    // inspection. Deliberately test against the *home* bounds rather than the currently painted
    // bounds: otherwise the lens would oscillate bottom-right -> top-left -> bottom-right while
    // the pointer stays still. As soon as the pointer leaves the original bottom-right region,
    // the magnifier returns home.
    val homeLens = magnifierBounds(visible, topLeft = false)
    val mouse = magnifierMousePoint
    val lens = if (mouse != null && homeLens.contains(mouse)) {
        magnifierBounds(visible, topLeft = true)
    } else {
        homeLens
    }

    val dark = ((background ?: Color(45, 45, 45)).red +
        (background ?: Color(45, 45, 45)).green +
        (background ?: Color(45, 45, 45)).blue) / 3 < 140
    val frame = if (dark) Color(88, 218, 194) else Color(30, 125, 112)
    val panel = if (dark) Color(18, 24, 29, 244) else Color(248, 250, 250, 246)

    g.color = Color(0, 0, 0, if (dark) 90 else 35)
    g.fillRoundRect(lens.x + 6, lens.y + 7, lens.width, lens.height, 18, 18)
    g.color = panel
    g.fillRoundRect(lens.x, lens.y, lens.width, lens.height, 18, 18)

    val inner = Rectangle(lens.x + 8, lens.y + 28, lens.width - 16, lens.height - 36)
    if (mouse != null && inner.width > 0 && inner.height > 0) {
        val modelX = (mouse.x - canvasPaddingX) / zoom
        val modelY = (mouse.y - canvasPaddingY) / zoom
        val scene = g.create() as Graphics2D
        try {
            scene.clip = inner
            scene.color = background ?: UIManager.getColor("Panel.background") ?: Color(45, 45, 45)
            scene.fillRect(inner.x, inner.y, inner.width, inner.height)
            val twoNodeLogicalWidth = (nodeW * 2 + (if (compactLayout) 86 else colGap)).toDouble()
            val twoNodeScale = inner.width.toDouble() / twoNodeLogicalWidth
            val lensScale = maxOf(zoom, twoNodeScale) * 2.0
            scene.translate(inner.centerX, inner.centerY)
            scene.scale(lensScale, lensScale)
            scene.translate(-modelX, -modelY)
            scene.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            drawGraphScene(scene, model, updateBounds = false)
        } finally {
            scene.dispose()
        }

        g.color = withAlpha(frame, 190)
        g.stroke = BasicStroke(1.2f)
        val cx = inner.x + inner.width / 2
        val cy = inner.y + inner.height / 2
        g.drawLine(cx - 10, cy, cx + 10, cy)
        g.drawLine(cx, cy - 10, cx, cy + 10)
        g.drawOval(cx - 3, cy - 3, 6, 6)
    } else {
        g.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        g.font = font.deriveFont(Font.PLAIN, 11f)
        g.drawString("Move the mouse over the graph", inner.x + 16, inner.y + 26)
    }

    g.color = frame
    g.stroke = BasicStroke(1.8f)
    g.drawRoundRect(lens.x, lens.y, lens.width, lens.height, 18, 18)
    g.font = font.deriveFont(Font.BOLD, 11f)
    g.drawString("MAGNIFIER  •  2× stronger", lens.x + 12, lens.y + 18)
}


