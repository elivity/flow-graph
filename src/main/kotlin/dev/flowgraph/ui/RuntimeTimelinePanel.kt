package dev.flowgraph.ui

import dev.flowgraph.model.RuntimeTraceEvent
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
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
    private var profilerEventsFilteredToCurrentGraph: Boolean = false
    private var cursorMillis: Long? = null
    private var frozenWindowEndMillis: Long? = null
    private var recordingPaused: Boolean = false
    private var pendingScrubDispatchMillis: Long? = null
    private var lastDispatchedScrubMillis: Long? = null
    private var eventVersion: Long = 0L
    private var filterVersion: Long = 0L
    private var cachedActivityLayer: BufferedImage? = null
    private var cachedActivityWidth: Int = -1
    private var cachedActivityHeight: Int = -1
    private var cachedActivityRangeFirst: Long = Long.MIN_VALUE
    private var cachedActivityRangeLast: Long = Long.MIN_VALUE
    private var cachedActivityEventVersion: Long = -1L
    private var cachedActivityFilterVersion: Long = -1L
    private var lastDragStatusUpdateNanos: Long = 0L

    private val title = JLabel("Runtime history")
    private val status = JLabel("LIVE • last 60s")
    private val liveButton = JButton("LIVE").apply {
        isEnabled = false
        toolTipText = "Resume runtime recording and return the graph overlay to live activity"
        margin = Insets(2, 9, 2, 9)
    }
    private val timeline = TimelineCanvas()
    private val repaintTimer = Timer(LIVE_REPAINT_MS) {
        if (cursorMillis == null) {
            timeline.repaint()
            updateStatus()
        }
    }.apply {
        isRepeats = true
        start()
    }

    // Mouse-drag events can arrive hundreds of times per second. Rebuilding a historical graph for
    // every pixel movement starves the EDT. Keep the cursor visually immediate, but dispatch only
    // the newest scrub position at a bounded interactive rate. Mouse release always flushes final.
    private val scrubDispatchTimer = Timer(SCRUB_DISPATCH_MS) {
        dispatchPendingScrub(stopAfter = false)
    }.apply {
        isRepeats = true
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
                add(JLabel("● UI interaction • drag timeline to pause + inspect • profiler events = current graph"))
            }
            add(labels, BorderLayout.CENTER)
            add(liveButton, BorderLayout.EAST)
        }
        liveButton.addActionListener { resumeLive() }

        add(header, BorderLayout.NORTH)
        add(timeline, BorderLayout.CENTER)
        preferredSize = Dimension(400, 148)
        minimumSize = Dimension(200, 112)
    }

    fun setEvents(snapshot: List<RuntimeTraceEvent>) {
        events.clear()
        // LiveTraceService retains events in receipt order already; sorting tens of thousands of
        // samples at every pause/graph switch is wasted EDT work.
        snapshot.takeLast(MAX_EVENTS).forEach(events::addLast)
        prune()
        eventVersion++
        invalidateActivityCache()
        timeline.repaint()
        updateStatus()
    }

    fun appendEvents(batch: List<RuntimeTraceEvent>) {
        if (batch.isEmpty()) return
        batch.forEach { events.addLast(it) }
        prune()
        eventVersion++
        invalidateActivityCache()
        timeline.repaint()
        updateStatus()
    }

    fun setRelevantRuntimeKeys(keys: Set<String>) {
        if (relevantRuntimeKeys == keys) return
        relevantRuntimeKeys = keys
        filterVersion++
        invalidateActivityCache()
        timeline.repaint()
    }

    fun setProfilerEventsFilteredToCurrentGraph(filtered: Boolean) {
        if (profilerEventsFilteredToCurrentGraph == filtered) return
        profilerEventsFilteredToCurrentGraph = filtered
        filterVersion++
        invalidateActivityCache()
        timeline.repaint()
    }

    fun clearHistory() {
        events.clear()
        cursorMillis = null
        frozenWindowEndMillis = null
        pendingScrubDispatchMillis = null
        lastDispatchedScrubMillis = null
        scrubDispatchTimer.stop()
        eventVersion++
        invalidateActivityCache()
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
        pendingScrubDispatchMillis = null
        lastDispatchedScrubMillis = null
        scrubDispatchTimer.stop()
        lastDragStatusUpdateNanos = 0L
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
        val rawEnd = currentWindowEnd()
        // Quantize only the live moving window. This keeps the lane raster stable for one visual
        // frame while still moving smoothly; a paused/scrubbed window remains exact and immutable.
        val end = if (frozenWindowEndMillis != null) rawEnd else rawEnd - (rawEnd % LIVE_RENDER_QUANTUM_MS)
        return (end - WINDOW_MS)..end
    }

    private fun invalidateActivityCache() {
        cachedActivityLayer = null
        cachedActivityWidth = -1
        cachedActivityHeight = -1
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
        // Updating Swing label text on every raw mouse event causes needless layout churn. The
        // cursor still follows every pixel immediately; the textual timestamp is sampled instead.
        val nowNanos = System.nanoTime()
        if (nowNanos - lastDragStatusUpdateNanos >= DRAG_STATUS_UPDATE_NANOS) {
            lastDragStatusUpdateNanos = nowNanos
            updateStatus(includeSliceCount = false)
        }
        queueScrubDispatch(selected)
    }

    private fun queueScrubDispatch(selected: Long) {
        pendingScrubDispatchMillis = selected
        if (!scrubDispatchTimer.isRunning) scrubDispatchTimer.start()
    }

    private fun dispatchPendingScrub(stopAfter: Boolean) {
        val selected = pendingScrubDispatchMillis
        pendingScrubDispatchMillis = null
        if (selected != null && selected != lastDispatchedScrubMillis) {
            lastDispatchedScrubMillis = selected
            onScrubbed(selected)
        }
        if (stopAfter || pendingScrubDispatchMillis == null) scrubDispatchTimer.stop()
    }

    private fun updateStatus(includeSliceCount: Boolean = true) {
        val cursor = cursorMillis
        if (cursor == null && !recordingPaused) {
            val range = currentWindow()
            val count = countInRange(range.first, range.last)
            status.text = "LIVE • last ${WINDOW_MS / 1000}s • $count events"
        } else if (cursor == null) {
            status.text = "PAUSED • history frozen • press LIVE to resume"
        } else {
            if (includeSliceCount) {
                val count = eventsNear(cursor).sumOf { it.occurrences }
                status.text = "PAUSED • ${TIME_WITH_MILLIS.format(Date(cursor))} • ±${HISTORY_RADIUS_MS}ms • $count events"
            } else {
                status.text = "PAUSED • ${TIME_WITH_MILLIS.format(Date(cursor))} • dragging…"
            }
        }
    }

    private fun countInRange(start: Long, end: Long): Int {
        var count = 0
        val iterator = events.descendingIterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.receivedAtMillis > end) continue
            if (event.receivedAtMillis < start) break
            count += event.occurrences
        }
        return count
    }

    override fun removeNotify() {
        repaintTimer.stop()
        scrubDispatchTimer.stop()
        super.removeNotify()
    }

    override fun addNotify() {
        super.addNotify()
        if (!repaintTimer.isRunning) repaintTimer.start()
    }

    private inner class TimelineCanvas : JPanel() {
        init {
            preferredSize = Dimension(400, 106)
            minimumSize = Dimension(120, 84)
            background = UIManager.getColor("EditorPane.background") ?: UIManager.getColor("Panel.background")
            toolTipText = "Drag horizontally to PAUSE recording and inspect runtime activity; UI interaction bubbles mark touch/key/scroll input"

            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && plotBounds().contains(e.point)) scrubAtX(e.x)
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) scrubAtX(e.x)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        // Apply the exact final cursor location even if the last movement occurred
                        // between timer ticks, then compute the precise slice count once.
                        dispatchPendingScrub(stopAfter = true)
                        lastDragStatusUpdateNanos = 0L
                        updateStatus(includeSliceCount = true)
                    }
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
            drawCachedActivityLayer(g, plot, range)
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
            val laneH = plot.height / 4.0
            val muted = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val separator = UIManager.getColor("Separator.foreground") ?: Color.DARK_GRAY
            val labels = arrayOf("emit/write", "deliver", "read/collect", "compose")
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

        private fun drawCachedActivityLayer(g: Graphics2D, plot: Rectangle, range: LongRange) {
            if (plot.width <= 0 || plot.height <= 0) return
            val cacheValid = cachedActivityLayer != null &&
                cachedActivityWidth == plot.width &&
                cachedActivityHeight == plot.height &&
                cachedActivityRangeFirst == range.first &&
                cachedActivityRangeLast == range.last &&
                cachedActivityEventVersion == eventVersion &&
                cachedActivityFilterVersion == filterVersion

            if (!cacheValid) {
                val image = BufferedImage(plot.width, plot.height, BufferedImage.TYPE_INT_ARGB)
                val ig = image.createGraphics()
                try {
                    ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val localPlot = Rectangle(0, 0, plot.width, plot.height)
                    drawActivity(ig, localPlot, range)
                    drawUiInteractions(ig, localPlot, range)
                } finally {
                    ig.dispose()
                }
                cachedActivityLayer = image
                cachedActivityWidth = plot.width
                cachedActivityHeight = plot.height
                cachedActivityRangeFirst = range.first
                cachedActivityRangeLast = range.last
                cachedActivityEventVersion = eventVersion
                cachedActivityFilterVersion = filterVersion
            }
            cachedActivityLayer?.let { g.drawImage(it, plot.x, plot.y, null) }
        }

        private fun drawActivity(g: Graphics2D, plot: Rectangle, range: LongRange) {
            if (events.isEmpty() || plot.width <= 0) return
            val bins = plot.width
            val all = Array(4) { IntArray(bins) }
            val relevant = Array(4) { IntArray(bins) }
            val duration = (range.last - range.first).coerceAtLeast(1L)

            val iterator = events.descendingIterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                if (event.receivedAtMillis > range.last) continue
                if (event.receivedAtMillis < range.first) break
                if (isUiInteraction(event)) continue
                val lane = laneFor(event.kind)
                val ratio = (event.receivedAtMillis - range.first).toDouble() / duration.toDouble()
                val x = (ratio * (bins - 1)).toInt().coerceIn(0, bins - 1)
                all[lane][x] += event.occurrences
                if (profilerEventsFilteredToCurrentGraph || event.stateKey in relevantRuntimeKeys) {
                    relevant[lane][x] += event.occurrences
                }
            }

            val laneH = plot.height / 4.0
            val outsideColor = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            val colors = arrayOf(
                Color(80, 200, 155),
                Color(80, 170, 225),
                Color(175, 145, 95),
                Color(190, 105, 210),
            )

            for (lane in 0..3) {
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

        private fun drawUiInteractions(g: Graphics2D, plot: Rectangle, range: LongRange) {
            if (events.isEmpty() || plot.width <= 0) return
            val duration = (range.last - range.first).coerceAtLeast(1L)
            val counts = IntArray(plot.width)

            val iterator = events.descendingIterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                if (event.receivedAtMillis > range.last) continue
                if (event.receivedAtMillis < range.first) break
                if (!isUiInteraction(event)) continue
                val ratio = (event.receivedAtMillis - range.first).toDouble() / duration.toDouble()
                val x = (ratio * (plot.width - 1)).toInt().coerceIn(0, plot.width - 1)
                counts[x] += event.occurrences.coerceAtLeast(1)
            }

            val bubbleFill = Color(245, 176, 65, 235)
            val bubbleOutline = Color(90, 72, 38, 230)
            val textColor = Color(35, 35, 35)
            val centerY = plot.y + 8

            for (offsetX in counts.indices) {
                val count = counts[offsetX]
                if (count <= 0) continue
                val diameter = if (count > 1) 12 else 10
                val radius = diameter / 2
                val x = plot.x + offsetX
                val y = centerY - radius

                // Small speech-bubble marker. The tail makes user input visually distinct from the
                // vertical profiler bars while keeping the interaction on the exact timeline x.
                g.color = bubbleFill
                g.fillOval(x - radius, y, diameter, diameter)
                val tail = Polygon(
                    intArrayOf(x - 2, x + 2, x),
                    intArrayOf(y + diameter - 1, y + diameter - 1, y + diameter + 4),
                    3,
                )
                g.fillPolygon(tail)
                g.color = bubbleOutline
                g.stroke = BasicStroke(1f)
                g.drawOval(x - radius, y, diameter, diameter)
                g.drawLine(x - 2, y + diameter - 1, x, y + diameter + 4)
                g.drawLine(x + 2, y + diameter - 1, x, y + diameter + 4)

                if (count > 1) {
                    val label = if (count > 9) "+" else count.toString()
                    g.font = font.deriveFont(Font.BOLD, 8f)
                    val fm = g.fontMetrics
                    g.color = textColor
                    g.drawString(label, x - fm.stringWidth(label) / 2, y + (diameter + fm.ascent - fm.descent) / 2 - 1)
                }
            }
        }

        override fun getToolTipText(event: MouseEvent): String {
            val plot = plotBounds()
            if (!plot.contains(event.point) || events.isEmpty()) {
                return "Drag horizontally to PAUSE recording and inspect runtime activity; UI interaction bubbles mark touch/key/scroll input"
            }
            if (kotlin.math.abs(event.y - (plot.y + 8)) > UI_BUBBLE_VERTICAL_HIT_RADIUS) {
                return "Drag horizontally to PAUSE recording and inspect runtime activity; UI interaction bubbles mark touch/key/scroll input"
            }
            val range = currentWindow()
            val duration = (range.last - range.first).coerceAtLeast(1L)
            var best: RuntimeTraceEvent? = null
            var bestDistance = Int.MAX_VALUE
            val iterator = events.descendingIterator()
            while (iterator.hasNext()) {
                val runtimeEvent = iterator.next()
                if (runtimeEvent.receivedAtMillis > range.last) continue
                if (runtimeEvent.receivedAtMillis < range.first) break
                if (!isUiInteraction(runtimeEvent)) continue
                val ratio = (runtimeEvent.receivedAtMillis - range.first).toDouble() / duration.toDouble()
                val x = plot.x + (ratio * (plot.width - 1)).toInt().coerceIn(0, plot.width - 1)
                val distance = kotlin.math.abs(event.x - x)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = runtimeEvent
                }
            }
            if (best != null && bestDistance <= UI_BUBBLE_HIT_RADIUS) {
                val summary = best.valueSummary?.takeIf { it.isNotBlank() } ?: "interaction"
                val site = best.site?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
                return buildString {
                    append(TIME_WITH_MILLIS.format(Date(best.receivedAtMillis)))
                    append(" • UI • ")
                    append(summary)
                    if (site != null) append(" • ").append(site)
                }
            }
            return "Drag horizontally to PAUSE recording and inspect runtime activity; UI interaction bubbles mark touch/key/scroll input"
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
            "compose" -> 3
            else -> 2
        }
    }

    private fun isUiInteraction(event: RuntimeTraceEvent): Boolean =
        event.kind == "ui-interaction" || event.stateKey == "@ui-interaction"

    companion object {
        const val WINDOW_MS = 60_000L
        const val HISTORY_RADIUS_MS = 450L
        const val RETAIN_MS = 5 * 60_000L
        // The timeline is a visualization/scrubber, not the raw transport log. 12k accepted
        // samples is ample for the retained five-minute window and keeps paint/scrub scans bounded.
        const val MAX_EVENTS = 12_000
        // 60-ish Hz scrub dispatch. The expensive lane rendering is cached while paused, so the
        // graph overlay can now track the pointer without the old 22 Hz staircase.
        private const val SCRUB_DISPATCH_MS = 16
        private const val LIVE_REPAINT_MS = 33
        private const val LIVE_RENDER_QUANTUM_MS = 33L
        private const val DRAG_STATUS_UPDATE_NANOS = 80_000_000L
        private const val LEFT_LABEL_WIDTH = 74
        private const val RIGHT_PADDING = 8
        private const val RULER_HEIGHT = 16
        private const val BOTTOM_PADDING = 4
        private const val UI_BUBBLE_HIT_RADIUS = 8
        private const val UI_BUBBLE_VERTICAL_HIT_RADIUS = 12
        private val TIME_ONLY = SimpleDateFormat("HH:mm:ss")
        private val TIME_WITH_MILLIS = SimpleDateFormat("HH:mm:ss.SSS")
    }
}
