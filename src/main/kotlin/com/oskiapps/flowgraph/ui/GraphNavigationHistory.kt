package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.FlowGraph
import java.util.ArrayDeque

/** Owns graph navigation snapshots without referencing Swing or the hosting panel. */
internal class GraphNavigationHistory(private val capacity: Int = 100) {
    init { require(capacity > 0) }

    private val entries = ArrayDeque<GraphViewSnapshot>()
    val canGoBack: Boolean get() = entries.isNotEmpty()
    val size: Int get() = entries.size

    fun push(snapshot: GraphViewSnapshot) {
        entries.addLast(snapshot)
        if (entries.size > capacity) entries.removeFirst()
    }

    fun back(): GraphViewSnapshot? = if (entries.isEmpty()) null else entries.removeLast()
    fun clear() = entries.clear()
}

internal data class GraphViewSnapshot(
    val activeGraph: FlowGraph?,
    val nodeId: String?,
    val focused: Boolean,
    val upstreamDepth: Int?,
    val downstreamDepth: Int?,
    val field: String?,
    val focusNodeId: String?,
)
