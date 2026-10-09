package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.FlowEdge
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle

/** Connection direction is semantic: each FlowEdge travels from its producer to its consumer. */
internal fun GraphCanvas.drawConnectionPorts(
    graphics: Graphics2D,
    edge: FlowEdge,
    producer: Rectangle,
    consumer: Rectangle,
    dimmed: Boolean,
) {
    val hasRepeatedInstances = cachedLayout?.sourceNodeIdByVisualId?.any { (visual, source) ->
        visual != source
    } == true
    val route = when {
        circuitViewEnabled && hasRepeatedInstances -> buildSingleCircuitRoute(edge, producer, consumer, 0)
        circuitViewEnabled -> circuitRoutingCache?.routes?.get(edge)
            ?: buildSingleCircuitRoute(edge, producer, consumer, 0)
        else -> edgeLineFromBounds(edge, producer, consumer).let {
            listOf(Point(it.x1, it.y1), Point(it.x2, it.y2))
        }
    }
    if (route.size < 2) return
    val oldColor = graphics.color
    val oldStroke = graphics.stroke
    val oldFont = graphics.font
    val out = route.first()
    val input = route.last()
    val alpha = if (dimmed) 75 else 255

    // Filled teal socket: values/events LEAVE this node.
    graphics.color = Color(33, 202, 169, alpha)
    graphics.fillOval(out.x - 5, out.y - 5, 10, 10)
    graphics.color = Color(15, 45, 43, alpha)
    graphics.stroke = BasicStroke(1.5f)
    graphics.drawOval(out.x - 5, out.y - 5, 10, 10)

    // Orange diamond socket: values/events ENTER this node.
    graphics.color = Color(255, 175, 62, alpha)
    val xs = intArrayOf(input.x, input.x + 6, input.x, input.x - 6)
    val ys = intArrayOf(input.y - 6, input.y, input.y + 6, input.y)
    graphics.fillPolygon(xs, ys, 4)
    graphics.color = Color(65, 38, 12, alpha)
    graphics.drawPolygon(xs, ys, 4)

    if (zoom >= 0.85 && !dimmed) {
        graphics.font = font.deriveFont(java.awt.Font.BOLD, 9f)
        graphics.color = Color(33, 202, 169)
        graphics.drawString("OUT", out.x + 8, out.y - 9)
        graphics.color = Color(255, 175, 62)
        graphics.drawString("IN", input.x + 8, input.y - 9)
    }
    graphics.font = oldFont
    graphics.stroke = oldStroke
    graphics.color = oldColor
}
