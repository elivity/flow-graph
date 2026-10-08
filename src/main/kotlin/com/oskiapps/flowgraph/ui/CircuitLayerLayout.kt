package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.NodeLayout
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

internal fun GraphCanvas.layoutCircuitLayers(base: NodeLayout, model: FlowGraph): NodeLayout {
    val ids = base.bounds.keys.sorted()
    if (ids.isEmpty()) return base
    val links = (if (base.visualEdges.isNotEmpty()) {
        base.visualEdges.map { it.fromVisualId to it.toVisualId }
    } else model.edges.map { it.from to it.to })
        .filter { (from, to) -> from in base.bounds && to in base.bounds && from != to }
        .distinct()
    val outgoing = ids.associateWith { mutableListOf<String>() }
    val incoming = ids.associateWith { mutableListOf<String>() }
    links.forEach { (a, b) ->
        outgoing.getValue(a).add(b)
        incoming.getValue(b).add(a)
    }
    // Kosaraju: SCC condensation prevents feedback loops from producing infinite ranks.
    val visited = mutableSetOf<String>()
    val finish = mutableListOf<String>()
    fun visit(start: String) {
        val stack = java.util.ArrayDeque<Pair<String, Boolean>>()
        stack.addLast(start to false)
        while (stack.isNotEmpty()) {
            val (id, completed) = stack.removeLast()
            if (completed) { finish.add(id); continue }
            if (!visited.add(id)) continue
            stack.addLast(id to true)
            outgoing.getValue(id).asReversed().forEach { next ->
                if (next !in visited) stack.addLast(next to false)
            }
        }
    }
    ids.forEach(::visit)
    val components = mutableListOf<List<String>>()
    val componentOf = mutableMapOf<String, Int>()
    finish.asReversed().forEach { root ->
        if (root in componentOf) return@forEach
        val members = mutableListOf<String>()
        val stack = java.util.ArrayDeque<String>()
        stack.add(root)
        componentOf[root] = components.size
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            members += id
            incoming.getValue(id).forEach { prev ->
                if (prev !in componentOf) {
                    componentOf[prev] = components.size
                    stack.add(prev)
                }
            }
        }
        components += members.sorted()
    }
    val successors = components.indices.associateWith { mutableSetOf<Int>() }
    val indegree = IntArray(components.size)
    links.forEach { (a, b) ->
        val from = componentOf.getValue(a)
        val to = componentOf.getValue(b)
        if (from != to && successors.getValue(from).add(to)) indegree[to]++
    }
    val ranks = IntArray(components.size)
    val queue = java.util.PriorityQueue<Int>()
    indegree.indices.filter { indegree[it] == 0 }.forEach(queue::add)
    while (queue.isNotEmpty()) {
        val current = queue.remove()
        successors.getValue(current).sorted().forEach { next ->
            ranks[next] = maxOf(ranks[next], ranks[current] + 1)
            if (--indegree[next] == 0) queue.add(next)
        }
    }
    val layers = mutableMapOf<Int, MutableList<String>>()
    ids.forEach { id -> layers.getOrPut(ranks[componentOf.getValue(id)]) {
        mutableListOf()
    }.add(id) }
    val maxRank = layers.keys.maxOrNull() ?: 0
    // Initial order comes from existing semantic layout, keeping the result stable.
    layers.values.forEach { layer ->
        layer.sortWith(compareBy<String> { base.bounds.getValue(it).centerY }.thenBy { it })
    }
    repeat(8) { iteration ->
        val ranksToVisit = if (iteration % 2 == 0) (1..maxRank).toList()
            else (0 until maxRank).reversed().toList()
        ranksToVisit.forEach { rank ->
            val referenceRank = if (iteration % 2 == 0) rank - 1 else rank + 1
            val reference = layers[referenceRank].orEmpty().withIndex()
                .associate { (position, id) -> id to position.toDouble() }
            val layer = layers[rank] ?: return@forEach
            val oldOrder = layer.withIndex().associate { (i, id) -> id to i }
            fun barycenter(id: String): Double {
                val neighbors = if (iteration % 2 == 0) incoming.getValue(id)
                    else outgoing.getValue(id)
                val positions = neighbors.mapNotNull(reference::get)
                return if (positions.isEmpty()) oldOrder.getValue(id).toDouble()
                    else positions.average()
            }
            layer.sortWith(compareBy<String> { barycenter(it) }
                .thenBy { oldOrder.getValue(it) })
        }
    }
    val width = base.bounds.values.maxOf { it.width }
    val widestLayer = layers.values.maxOfOrNull { it.size } ?: 0
    val spacingY = (base.bounds.values.maxOf { it.height } + 92)
    val transformed = linkedMapOf<String, Rectangle>()
    layers.toSortedMap().forEach { (rank, nodes) ->
        val shift = (widestLayer - nodes.size) * spacingY / 2
        nodes.forEachIndexed { index, id ->
            val old = base.bounds.getValue(id)
            transformed[id] = Rectangle(
                96 + rank * (width + 230), 88 + shift + index * spacingY,
                old.width, old.height,
            )
        }
    }
    val right = transformed.values.maxOf { it.x + it.width } + 100
    val bottom = transformed.values.maxOf { it.y + it.height } + 100
    return base.copy(
        bounds = transformed,
        readCluster = null,
        componentClusters = emptyList(),
        clusterByNodeId = emptyMap(),
        preferredSize = Dimension(right, bottom),
    )
}

/**
 * Change the reading direction *inside* every project-overview cluster while keeping the
 * clusters themselves in the same vertical stack.
 *
 * Horizontal overview: cluster 1, cluster 2, cluster 3 stay one below another and each cluster
 * reads left-to-right. Vertical overview keeps that exact cluster order/stack, but transposes
 * the nodes inside each cluster so its causal chain reads top-to-bottom. This prevents changing
 * orientation from turning the whole overview into a wide row of clusters.
 */


