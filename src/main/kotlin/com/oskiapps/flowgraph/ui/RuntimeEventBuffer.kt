package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.RuntimeTraceEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe bounded producer/consumer queue. No Swing or project-service dependency. */
internal class RuntimeEventBuffer(private val capacity: Int) {
    init { require(capacity > 0) }

    private val queue = ConcurrentLinkedQueue<RuntimeTraceEvent>()
    private val count = AtomicInteger()
    private val dropped = AtomicLong()
    val pending: Int get() = count.get()
    val droppedCount: Long get() = dropped.get()

    fun offer(event: RuntimeTraceEvent) {
        queue.add(event)
        count.incrementAndGet()
        while (count.get() > capacity) {
            if (poll() != null) dropped.incrementAndGet() else break
        }
    }

    fun poll(): RuntimeTraceEvent? {
        val event = queue.poll() ?: return null
        count.decrementAndGet()
        return event
    }

    fun resetDropped() { dropped.set(0) }

    fun clear() {
        while (poll() != null) {
            // Discard pending events.
        }
    }
}
