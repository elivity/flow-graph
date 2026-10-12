package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.FlowGraph
import com.oskiapps.flowgraph.model.node
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle

/** Names are screen-space annotations, so they remain legible when nodes become subpixel.
 * When names cannot fit on screen, skip overlaps rather than covering the entire graph.
 * Hovered and selected nodes take precedence and are also readable in the magnifier. */
internal fun GraphCanvas.drawLowZoomNodeNames(g: Graphics2D, model: FlowGraph) {
    val layout = cachedLayout ?: return
    // Anchor to the viewport, not the Swing repaint's dirty clip: otherwise names
    // would shift when a small Live update repaints only part of the canvas.
    val visible = visibleRect
    val dirty = g.clipBounds
    if (visible.isEmpty) return

    g.font = font.deriveFont(Font.BOLD, 11f)
    val fm = g.fontMetrics
    val occupied = NameLabelOccupancy()
    val dark = (background ?: Color(45, 45, 45)).let { it.red + it.green + it.blue } / 3 < 140
    val labelBackground = if (dark) Color(20, 30, 35, 232) else Color(30, 45, 53, 226)

    fun drawName(visualId: String, nodeBounds: Rectangle) {
        val onScreen = projectedNodeBounds(nodeBounds, zoom, canvasPaddingX, canvasPaddingY)
        if (!visible.intersects(onScreen)) return
        val node = model.node(sourceNodeId(layout, visualId)) ?: return
        // Full names at overview scale can easily occupy several hundred pixels.
        // Prefer a readable, compact tag without doing expensive text measurement for offscreen nodes.
        val text = node.label.take(46).let { if (node.label.length > 46) "$it…" else it }
        if (text.isEmpty()) return
        val tag = readableNameBounds(onScreen, fm.stringWidth(text) + 12, fm.height + 4, visible) ?: return
        if (!occupied.reserve(tag)) return
        if (dirty != null && !dirty.intersects(tag)) return
        g.color = labelBackground
        g.fillRoundRect(tag.x, tag.y, tag.width, tag.height, 8, 8)
        g.color = Color.WHITE
        g.drawString(text, tag.x + 6, tag.y + 2 + fm.ascent)
    }

    hoveredNameVisualId?.let { id -> layout.bounds[id]?.let { drawName(id, it) } }
    if (selectedId != null) {
        layout.bounds.entries.firstOrNull {
            sourceNodeId(layout, it.key) == selectedId && it.key != hoveredNameVisualId
        }?.let { drawName(it.key, it.value) }
    }
    layout.bounds.forEach { (id, bounds) ->
        if (id != hoveredNameVisualId) drawName(id, bounds)
    }
}
