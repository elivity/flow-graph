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

internal fun GraphCanvas.drawCircuitBackdrop(g: Graphics2D) {
    val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
    val dark = ((background ?: Color(45,45,45)).red + (background ?: Color(45,45,45)).green + (background ?: Color(45,45,45)).blue) / 3 < 140
    val major = if (dark) Color(0, 255, 190, 28) else Color(0, 110, 90, 24)
    val minor = if (dark) Color(0, 255, 190, 12) else Color(0, 110, 90, 10)
    val dot = if (dark) Color(255, 205, 90, 42) else Color(160, 120, 20, 32)
    // The unclamped model-space grid explodes at very low zoom: thousands of lines and
    // millions of dots may otherwise be submitted for one 800x600 viewport.
    val spacing = adaptiveCircuitGridStep(
        CIRCUIT_GRID_SPACING, zoom, maxOf(clip.width, clip.height)
    )
    val oldStroke = g.stroke
    val majorStroke = BasicStroke(1.15f)
    val minorStroke = BasicStroke(1f)
    for (x in circuitGridPositions(clip.x, clip.width, spacing)) {
        val isMajor = Math.floorDiv(x, spacing) % 4 == 0
        g.color = if (isMajor) major else minor
        g.stroke = if (isMajor) majorStroke else minorStroke
        g.drawLine(x, clip.y, x, clip.y + clip.height)
    }
    for (y in circuitGridPositions(clip.y, clip.height, spacing)) {
        val isMajor = Math.floorDiv(y, spacing) % 4 == 0
        g.color = if (isMajor) major else minor
        g.stroke = if (isMajor) majorStroke else minorStroke
        g.drawLine(clip.x, y, clip.x + clip.width, y)
    }
    g.stroke = oldStroke

    // The 3-unit dots are subpixel at overview scale. Skip their rasterization until
    // they are visible instead of filling the entire virtual canvas with invisible work.
    if (zoom >= 0.5) {
        val dotsStep = adaptiveCircuitGridStep(
            CIRCUIT_GRID_SPACING * 2, zoom, maxOf(clip.width, clip.height),
            minScreenPixels = 32.0, maxLines = 80,
        )
        g.color = dot
        for (x in circuitGridPositions(clip.x, clip.width, dotsStep)) {
            for (y in circuitGridPositions(clip.y, clip.height, dotsStep)) {
                g.fillOval(x - 1, y - 1, 3, 3)
            }
        }
    }
}



internal fun GraphCanvas.drawDirectionLegend(g: Graphics2D) {
    val oldStroke = g.stroke
    val oldFont = g.font
    g.font = font.deriveFont(Font.BOLD, 10f)
    g.stroke = BasicStroke(3f)

    var x = 30
    val y = 17
    g.color = directionalColor(forward = false)
    g.drawLine(x, y - 3, x + 24, y - 3)
    g.drawString("upstream / causes", x + 30, y)

    x += 145
    g.color = directionalColor(forward = true)
    g.drawLine(x, y - 3, x + 24, y - 3)
    g.drawString("downstream / affected", x + 30, y)

    x += 170
    g.color = cycleColor()
    g.drawLine(x, y - 3, x + 24, y - 3)
    g.drawString("cycle", x + 30, y)

    x += 92
    g.color = possibleColor()
    g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
    g.drawLine(x, y - 3, x + 24, y - 3)
    g.drawString("possible (does not expand)", x + 30, y)

    g.stroke = oldStroke
    g.font = oldFont
}



internal fun GraphCanvas.directionalColor(forward: Boolean): Color {
    val bg = background ?: Color(45, 45, 45)
    val dark = (bg.red + bg.green + bg.blue) / 3 < 140
    return if (forward) {
        if (dark) Color(70, 215, 120) else Color(25, 145, 70)
    } else {
        if (dark) Color(240, 95, 95) else Color(195, 50, 50)
    }
}



internal fun GraphCanvas.cycleColor(): Color {
    val bg = background ?: Color(45, 45, 45)
    val dark = (bg.red + bg.green + bg.blue) / 3 < 140
    return if (dark) Color(200, 125, 235) else Color(145, 75, 175)
}



internal fun GraphCanvas.possibleColor(): Color {
    val bg = background ?: Color(45, 45, 45)
    val dark = (bg.red + bg.green + bg.blue) / 3 < 140
    return if (dark) Color(230, 170, 70) else Color(180, 115, 25)
}



internal fun GraphCanvas.blend(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
    )
}



internal fun GraphCanvas.withAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))


