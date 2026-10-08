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

internal class ComposePreviewCanvas : JPanel() {
    var snapshot: ComposeRenderSnapshot? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(400, 520)
        minimumSize = Dimension(280, 220)
        background = Color(28, 28, 32)
        border = BorderFactory.createLineBorder(Color(75, 75, 82), 1, true)
        toolTipText = "Selected Compose render"
    }

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val snap = snapshot
            if (snap == null) {
                g.color = Color(170, 170, 178)
                g.font = font.deriveFont(Font.PLAIN, 13f)
                val text = "No UI render available"
                val fm = g.fontMetrics
                g.drawString(text, ((width - fm.stringWidth(text)) / 2).coerceAtLeast(12), height / 2)
                return
            }

            val image = snap.image
            val pad = 18
            val availableW = (width - pad * 2).coerceAtLeast(1)
            val availableH = (height - pad * 2).coerceAtLeast(1)
            val scale = minOf(
                availableW.toDouble() / image.width.toDouble(),
                availableH.toDouble() / image.height.toDouble(),
            )
            val drawW = (image.width * scale).toInt().coerceAtLeast(1)
            val drawH = (image.height * scale).toInt().coerceAtLeast(1)
            val x = (width - drawW) / 2
            val y = (height - drawH) / 2

            g.color = Color(12, 12, 15)
            g.fillRoundRect(x - 5, y - 5, drawW + 10, drawH + 10, 12, 12)
            g.drawImage(image, x, y, drawW, drawH, null)
            g.color = Color(105, 105, 115)
            g.drawRoundRect(x - 5, y - 5, drawW + 10, drawH + 10, 12, 12)
        } finally {
            g.dispose()
        }
    }
}

