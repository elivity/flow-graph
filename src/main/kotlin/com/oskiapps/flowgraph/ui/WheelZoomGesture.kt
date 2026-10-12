package com.oskiapps.flowgraph.ui

import java.awt.Point
import kotlin.math.abs
import kotlin.math.roundToInt

/** The model point captured at the start of one continuous wheel/trackpad gesture. */
internal data class WheelZoomAnchor(
    val modelX: Double,
    val modelY: Double,
    val viewportPoint: Point,
    val screenPoint: Point?,
)

/**
 * Preserve one cursor anchor through a wheel burst, even when Swing resizes the viewport between
 * events. Deliver all accumulated wheel input over bounded animation frames instead of dropping
 * fast events or switching the focal point to the latest (potentially shifted) canvas coordinate.
 */
internal class WheelZoomGesture {
    private companion object {
        const val IDLE_GAP_MS = 240L
        const val MAX_FRAME_EXPONENT = 0.55
        const val MAX_PENDING_EXPONENT = 24.0 // Beyond either supported zoom limit.
    }

    var anchor: WheelZoomAnchor? = null
        private set
    var pendingExponent = 0.0
        private set
    private var lastEventMillis: Long? = null

    fun continuesAt(eventMillis: Long): Boolean {
        val previous = lastEventMillis ?: return false
        return eventMillis >= previous && eventMillis - previous <= IDLE_GAP_MS
    }

    fun accept(eventMillis: Long, exponent: Double, proposedAnchor: WheelZoomAnchor) {
        if (!continuesAt(eventMillis)) {
            anchor = proposedAnchor
            pendingExponent = 0.0
        }
        lastEventMillis = eventMillis
        pendingExponent = (pendingExponent + exponent)
            .coerceIn(-MAX_PENDING_EXPONENT, MAX_PENDING_EXPONENT)
    }

    fun takeFrameExponent(): Double {
        val frame = pendingExponent.coerceIn(-MAX_FRAME_EXPONENT, MAX_FRAME_EXPONENT)
        pendingExponent -= frame
        if (abs(pendingExponent) < 0.000001) pendingExponent = 0.0
        return frame
    }

    fun takeRemainingExponent(): Double = pendingExponent.also { pendingExponent = 0.0 }

    fun hasPending(): Boolean = abs(pendingExponent) >= 0.000001

    fun discardPending() { pendingExponent = 0.0 }

    fun clear() {
        anchor = null
        pendingExponent = 0.0
        lastEventMillis = null
    }
}

/** Camera translation that keeps the captured model point under its original cursor position. */
internal fun anchoredViewOffset(padding: Int, modelPosition: Double, zoom: Double, cursor: Int): Int =
    (padding + modelPosition * zoom - cursor).roundToInt()
