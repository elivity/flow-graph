package dev.flowgraph.ui

import dev.flowgraph.model.RuntimeTraceEvent
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import javax.swing.*
import kotlin.math.ln
import kotlin.math.max

/**
 * Stable profiler-style runtime history strip.
 *
 * The graph map never owns timeline coordinates. Scrubbing only changes the runtime overlay shown
 * on top of the existing map; node positions remain untouched. Starting a scrub pauses the global
 * runtime recorder, freezes the timeline range/history, and keeps it frozen until LIVE is pressed.
 */
class RuntimeTimelinePanel(
    private val onScrubStarted: () -> Unit,
    private val onScrubbed: (Long?) -> Unit,
) : JPanel(BorderLayout()) {
    private val events = ArrayDeque<RuntimeTraceEvent>()
    private var relevantRuntimeKeys: Set<String> = emptySet()
    private var cursorMillis: Long? = null
    private var frozenWindowEndMillis: Long? = null
    private var recordingPaused: Boolean = false

    private val title = JLabel("Runtime history")
    private val status = JLabel("LIVE • last 60s")
    private val liveButton = JButton("LIVE").apply {
        isEnabled = false
        toolTipText = "Resume runtime recording and return the graph overlay to live activity"
        margin = Insets(2, 9, 2, 9)
    }
    private val timeline = TimelineCanvas()
    private val repaintTimer = Timer(250) {
        if (cursorMillis == null) {
            timeline.repaint()
            updateStatus()
        }
    }.apply {
        isRepeats = true
        start()
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground") ?: Color.DARK_GRAY),
            BorderFactory.createEmptyBorder(4, 6, 4, 6),
        )
        background = UIManager.getColor("Panel.background")

        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            val labels = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(title)
                add(status)
                add(JLabel("drag timeline to pause + inspect • bright = current graph • gray = outside graph"))
            }
            add(labels, BorderLayout.CENTER)
            add(liveButton, BorderLayout.EAST)
        }
        liveButton.addActionListener { resumeLive() }

        add(header, BorderLayout.NORTH)
        add(timeline, BorderLayout.CENTER)
        preferredSize = Dimension(400, 126)
        minimumSize = Dimension(200, 96)
    }

    fun setEvents(snapshot: List<RuntimeTraceEvent>) {
        events.clear()
        snapshot.sortedBy { it.receivedAtMillis }.takeLast(MAX_EVENTS).forEach(events::addLast)
        prune()
        timeline.repaint()
        updateStatus()
    }

    fun appendEvents(batch: List<RuntimeTraceEvent>) {
        if (batch.isEmpty()) return
        batch.forEach { events.addLast(it) }
        prune()
        timeline.repaint()
        updateStatus()
    }

    fun setRelevantRuntimeKeys(keys: Set<String>) {
        relevantRuntimeKeys = keys
        timeline.repaint()
    }

    fun clearHistory() {
        events.clear()
        cursorMillis = null
        frozenWindowEndMillis = null
        // Clearing captured data must never resume the recorder. LIVE is deliberately the only
        // control that can leave paused profiler mode.
        liveButton.isEnabled = recordingPaused
        timeline.repaint()
        updateStatus()
    }

    fun resumeLive() {
        if (!recordingPaused && cursorMillis == null) return
        recordingPaused = false
        cursorMillis = null
        frozenWindowEndMillis = null
        liveButton.isEnabled = false
        timeline.repaint()
        updateStatus()
        onScrubbed(null)
    }

    fun cursorTimeMillis(): Long? = cursorMillis

    /** Events in the historical inspection slice around [centerMillis], ordered oldest -> newest. */
    fun eventsNear(centerMillis: Long, radiusMillis: Long = HISTORY_RADIUS_MS): List<RuntimeTraceEvent> {
        if (events.isEmpty()) return emptyList()
        val start = centerMillis - radiusMillis
        val end = centerMillis + radiusMillis
        val out = ArrayDeque<RuntimeTraceEvent>()
        val iterator = events.descendingIterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.receivedAtMillis > end) continue
            if (event.receivedAtMillis < start) break
            out.addFirst(event)
        }
        return out.toList()
    }

    private fun prune() {
        val newest = events.peekLast()?.receivedAtMillis ?: return
        val cutoff = newest - RETAIN_MS
        while (events.isNotEmpty() && (events.size > MAX_EVENTS || events.peekFirst().receivedAtMillis < cutoff)) {
            events.removeFirst()
        }
    }

    private fun currentWindowEnd(): Long {
        frozenWindowEndMillis?.let { return it }
        val newest = events.peekLast()?.receivedAtMillis ?: 0L
        return max(System.currentTimeMillis(), newest)
    }

    private fun currentWindow(): LongRange {
        val end = currentWindowEnd()
        return (end - WINDOW_MS)..end
    }

    private fun scrubAtX(x: Int) {
        val plot = timeline.plotBounds()
        if (plot.width <= 1) return
        if (!recordingPaused) {
            // Pause the global recorder before freezing the profiler window. The owner drains any
            // already-accepted pre-pause UI events into this timeline synchronously, so the frozen
            // history includes everything received up to the start of the drag.
            recordingPaused = true
            onScrubStarted()
            frozenWindowEndMillis = currentWindowEnd()
        }
        val range = currentWindow()
        val clampedX = x.coerceIn(plot.x, plot.x + plot.width)
        val ratio = (clampedX - plot.x).toDouble() / plot.width.toDouble()
        val selected = (range.first + ratio * (range.last - range.first).toDouble()).toLong()
        cursorMillis = selected
        liveButton.isEnabled = true
        timeline.repaint()
        updateStatus()
        onScrubbed(selected)
    }

    private fun updateStatus() {
        val cursor = cursorMillis
        if (cursor == null && !recordingPaused) {
            val range = currentWindow()
            val count = countInRange(range.first, range.last)
            status.text = "LIVE • last ${WINDOW_MS / 1000}s • $count events"
        } else if (cursor == null) {
            status.text = "PAUSED • history frozen • press LIVE to resume"
        } else {
            val count = eventsNear(cursor).size
            status.text = "PAUSED • ${TIME_WITH_MILLIS.format(Date(cursor))} • ±${HISTORY_RADIUS_MS}ms • $count events"
        }
    }

    private fun countInRange(start: Long, end: Long): Int {
        var count = 0
        val iterator = events.descendingIterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.receivedAtMillis > end) continue
            if (event.receivedAtMillis < start) break
            count++
        }
        return count
    }

    override fun removeNotify() {
        repaintTimer.stop()
        super.removeNotify()
    }

    override fun addNotify() {
        super.addNotify()
        if (!repaintTimer.isRunning) repaintTimer.start()
    }

    private inner class TimelineCanvas : JPanel() {
        init {
            preferredSize = Dimension(400, 86)
            minimumSize = Dimension(120, 68)
            background = UIManager.getColor("EditorPane.background") ?: UIManager.getColor("Panel.background")
            toolTipText = "Drag horizontally to PAUSE recording and inspect runtime activity at a point in time"

            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && plotBounds().contains(e.point)) scrubAtX(e.x)
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) scrubAtX(e.x)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        fun plotBounds(): Rectangle = Rectangle(
            LEFT_LABEL_WIDTH,
            RULER_HEIGHT,
            (width - LEFT_LABEL_WIDTH - RIGHT_PADDING).coerceAtLeast(1),
            (height - RULER_HEIGHT - BOTTOM_PADDING).coerceAtLeast(1),
        )

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val plot = plotBounds()
            val range = currentWindow()
            drawRuler(g, plot, range)
            drawLanes(g, plot)
            drawActivity(g, plot, range)
            drawCursor(g, plot, range)
            g.dispose()
        }

        private fun drawRuler(g: Graphics2D, plot: Rectangle, range: LongRange) {
            g.font = font.deriveFont(9f)
            val muted = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val separator = UIManager.getColor("Separator.foreground") ?: Color.DARK_GRAY
            g.color = separator
            g.drawLine(plot.x, RULER_HEIGHT - 1, plot.x + plot.width, RULER_HEIGHT - 1)

            for (i in 0..4) {
                val ratio = i / 4.0
                val x = plot.x + (plot.width * ratio).toInt()
                val t = (range.first + (range.last - range.first) * ratio).toLong()
                g.color = separator
                g.drawLine(x, RULER_HEIGHT - 4, x, RULER_HEIGHT + 2)
                g.color = muted
                val text = TIME_ONLY.format(Date(t))
                val textW = g.fontMetrics.stringWidth(text)
                val tx = (x - textW / 2).coerceIn(plot.x, (plot.x + plot.width - textW).coerceAtLeast(plot.x))
                g.drawString(text, tx, 10)
            }
        }

        private fun drawLanes(g: Graphics2D, plot: Rectangle) {
            val laneH = plot.height / 3.0
            val muted = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val separator = UIManager.getColor("Separator.foreground") ?: Color.DARK_GRAY
            val labels = arrayOf("emit/write", "deliver", "read/collect")
            g.font = font.deriveFont(9f)
            labels.forEachIndexed { index, label ->
                val y0 = (plot.y + laneH * index).toInt()
                if (index > 0) {
                    g.color = Color(separator.red, separator.green, separator.blue, 85)
                    g.drawLine(plot.x, y0, plot.x + plot.width, y0)
                }
                g.color = muted
                g.drawString(label, 4, (y0 + laneH / 2 + 3).toInt())
            }
        }

        private fun drawActivity(g: Graphics2D, plot: Rectangle, range: LongRange) {
            if (events.isEmpty() || plot.width <= 0) return
            val bins = plot.width
            val all = Array(3) { IntArray(bins) }
            val relevant = Array(3) { IntArray(bins) }
            val duration = (range.last - range.first).coerceAtLeast(1L)

            val iterator = events.descendingIterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                if (event.receivedAtMillis > range.last) continue
                if (event.receivedAtMillis < range.first) break
                val lane = laneFor(event.kind)
                val ratio = (event.receivedAtMillis - range.first).toDouble() / duration.toDouble()
                val x = (ratio * (bins - 1)).toInt().coerceIn(0, bins - 1)
                all[lane][x]++
                if (event.stateKey in relevantRuntimeKeys) relevant[lane][x]++
            }

            val laneH = plot.height / 3.0
            val outsideColor = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val colors = arrayOf(
                Color(80, 200, 155),
                Color(80, 170, 225),
                Color(175, 145, 95),
            )

            for (lane in 0..2) {
                val laneBottom = (plot.y + laneH * (lane + 1) - 2).toInt()
                val maxCount = all[lane].maxOrNull()?.coerceAtLeast(1) ?: 1
                for (x in 0 until bins) {
                    val count = all[lane][x]
                    if (count <= 0) continue
                    val height = activityHeight(count, maxCount, laneH.toInt().coerceAtLeast(4))
                    val px = plot.x + x
                    g.color = Color(outsideColor.red, outsideColor.green, outsideColor.blue, 95)
                    g.drawLine(px, laneBottom, px, laneBottom - height)

                    val rel = relevant[lane][x]
                    if (rel > 0) {
                        val relHeight = activityHeight(rel, maxCount, laneH.toInt().coerceAtLeast(4))
                        val c = colors[lane]
                        g.color = Color(c.red, c.green, c.blue, 220)
                        g.drawLine(px, laneBottom, px, laneBottom - relHeight)
                    }
                }
            }
        }

        private fun drawCursor(g: Graphics2D, plot: Rectangle, range: LongRange) {
            val cursor = cursorMillis ?: return
            val duration = (range.last - range.first).coerceAtLeast(1L)
            val ratio = ((cursor - range.first).toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
            val x = plot.x + (ratio * plot.width).toInt()

            val halfBand = ((HISTORY_RADIUS_MS.toDouble() / duration.toDouble()) * plot.width).toInt().coerceAtLeast(2)
            g.color = Color(245, 195, 70, 30)
            g.fillRect(x - halfBand, plot.y, halfBand * 2, plot.height)
            g.color = Color(245, 195, 70)
            g.stroke = BasicStroke(2f)
            g.drawLine(x, 0, x, height)
            val triangle = Polygon(intArrayOf(x - 5, x + 5, x), intArrayOf(0, 0, 7), 3)
            g.fillPolygon(triangle)
        }

        private fun activityHeight(count: Int, maxCount: Int, laneHeight: Int): Int {
            if (count <= 0) return 0
            val normalized = ln(1.0 + count.toDouble()) / ln(1.0 + maxCount.toDouble())
            return (2 + normalized * (laneHeight - 4).coerceAtLeast(2)).toInt().coerceAtLeast(2)
        }

        private fun laneFor(kind: String): Int = when (kind) {
            "emit", "initial", "emit-request", "write", "tryEmit" -> 0
            "deliver" -> 1
            else -> 2
        }
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val HISTORY_RADIUS_MS = 450L
        const val RETAIN_MS = 5 * 60_000L
        const val MAX_EVENTS = 30_000
        private const val LEFT_LABEL_WIDTH = 74
        private const val RIGHT_PADDING = 8
        private const val RULER_HEIGHT = 16
        private const val BOTTOM_PADDING = 4
        private val TIME_ONLY = SimpleDateFormat("HH:mm:ss")
        private val TIME_WITH_MILLIS = SimpleDateFormat("HH:mm:ss.SSS")
    }
}
