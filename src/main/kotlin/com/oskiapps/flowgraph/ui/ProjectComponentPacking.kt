package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComponentPlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.PackedComponent
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_CONTEXT_NODES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_MAX_CLUSTER_NODES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_OUTER_PADDING
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

internal fun GraphCanvas.packProjectComponents(placements: List<ComponentPlacement>): List<PackedComponent> {
    if (placements.isEmpty()) return emptyList()

    val packed = mutableListOf<PackedComponent>()
    var y = PROJECT_OVERVIEW_OUTER_PADDING
    placements.forEach { placement ->
        packed += PackedComponent(
            placement = placement,
            x = PROJECT_OVERVIEW_OUTER_PADDING,
            y = y,
        )
        y += placement.height + PROJECT_OVERVIEW_CLUSTER_GAP
    }
    return packed
}

/**
 * Builds overview communities from strong causal proximity rather than plain connectivity.
 * A single weak/read bridge must not turn half of the app into one giant grid. Large strong
 * components are greedily partitioned into bounded neighborhoods; cross-neighborhood edges
 * remain visible on the map.
 */



internal fun GraphCanvas.semanticOverviewClusters(model: FlowGraph): List<Set<String>> {
    val byId = model.nodes.associateBy { it.id }
    val primaryIds = model.nodes.asSequence()
        .filter { it.kind != NodeKind.READ }
        .map { it.id }
        .toMutableSet()
    if (primaryIds.isEmpty()) return emptyList()

    fun strong(edge: FlowEdge): Boolean =
        edge.kind != EdgeKind.READS &&
            edge.kind != EdgeKind.POSSIBLY_TRIGGERS_WRITE &&
            edge.confidence != CausalConfidence.POSSIBLE

    val adjacency = primaryIds.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
    model.edges.asSequence().filter(::strong).forEach { edge ->
        if (edge.from in primaryIds && edge.to in primaryIds) {
            adjacency.getValue(edge.from) += edge.to
            adjacency.getValue(edge.to) += edge.from
        }
    }

    fun owner(id: String): String {
        val node = byId[id] ?: return ""
        node.groupKey?.takeIf { it.isNotBlank() }?.let { return it }
        node.runtimeKey?.takeIf { it.isNotBlank() && !isSourceComposeRuntimeKey(it) }?.let { key ->
            return key.substringBeforeLast('.', key)
        }
        return node.source?.file?.name?.substringBeforeLast('.').orEmpty()
    }

    val unassigned = primaryIds.toMutableSet()
    val clusters = mutableListOf<LinkedHashSet<String>>()

    // A Compose hierarchy is one semantic surface even when it is larger than the generic
    // overview cluster target. Splitting it every 24 nodes destroys the parent -> child tree
    // the user is trying to read. Reserve complete COMPOSES-connected components first, then
    // attach a small amount of immediately-related Flow plumbing around each surface.
    val composeIds = primaryIds.filterTo(linkedSetOf()) { byId[it]?.kind == NodeKind.COMPOSABLE }
    val composeAdjacency = composeIds.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
    model.edges.asSequence()
        .filter { it.kind == EdgeKind.COMPOSES && it.from in composeIds && it.to in composeIds }
        .forEach { edge ->
            composeAdjacency.getValue(edge.from) += edge.to
            composeAdjacency.getValue(edge.to) += edge.from
        }
    val composeRemaining = composeIds.toMutableSet()
    while (composeRemaining.isNotEmpty()) {
        val start = composeRemaining.first()
        val component = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(start)
        composeRemaining.remove(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            component += current
            composeAdjacency[current].orEmpty().forEach { next ->
                if (composeRemaining.remove(next)) queue.addLast(next)
            }
        }
        if (component.size < 2) continue

        val cluster = LinkedHashSet(component)
        unassigned.removeAll(component)
        val targetSize = max(PROJECT_OVERVIEW_MAX_CLUSTER_NODES, component.size + PROJECT_OVERVIEW_COMPOSE_CONTEXT_NODES)
        while (cluster.size < targetSize) {
            val candidates = linkedSetOf<String>()
            cluster.forEach { id ->
                adjacency[id].orEmpty().filterTo(candidates) { candidate ->
                    candidate in unassigned && byId[candidate]?.kind != NodeKind.COMPOSABLE
                }
            }
            if (candidates.isEmpty()) break
            val chosen = candidates.maxWithOrNull(
                compareBy<String> { candidate ->
                    adjacency[candidate].orEmpty().count { it in cluster } * 100 +
                        (if (byId[candidate]?.kind == NodeKind.STATE) 8 else 0) +
                        adjacency[candidate].orEmpty().count { it in unassigned }
                }.thenBy { it },
            ) ?: break
            cluster += chosen
            unassigned.remove(chosen)
        }
        clusters += cluster
    }

    while (unassigned.isNotEmpty()) {
        val seed = unassigned.maxWithOrNull(
            compareBy<String> { adjacency[it].orEmpty().count { n -> n in unassigned } }
                .thenBy { if (byId[it]?.kind == NodeKind.STATE) 1 else 0 },
        ) ?: unassigned.first()
        val cluster = linkedSetOf(seed)
        unassigned.remove(seed)
        val seedOwner = owner(seed)

        while (cluster.size < PROJECT_OVERVIEW_MAX_CLUSTER_NODES) {
            val candidates = linkedSetOf<String>()
            cluster.forEach { id ->
                adjacency[id].orEmpty().filterTo(candidates) { it in unassigned }
            }
            if (candidates.isEmpty()) break
            val chosen = candidates.maxWithOrNull(
                compareBy<String> { candidate ->
                    adjacency[candidate].orEmpty().count { it in cluster } * 100 +
                        (if (owner(candidate).isNotBlank() && owner(candidate) == seedOwner) 160 else 0) +
                        adjacency[candidate].orEmpty().count { it in unassigned } * 2 +
                        (if (byId[candidate]?.kind == NodeKind.STATE) 4 else 0)
                }.thenBy { it },
            ) ?: break
            cluster += chosen
            unassigned.remove(chosen)
        }
        clusters += cluster
    }

    // Reads are context, not community glue. Attach them to a neighboring causal community
    // where possible; overflow reads are chunked separately so observer-heavy screens don't
    // blow up an otherwise useful Flow neighborhood.
    val clusterIndexById = mutableMapOf<String, Int>()
    clusters.forEachIndexed { index, ids -> ids.forEach { clusterIndexById[it] = index } }
    val readOverflow = mutableListOf<String>()
    model.nodes.filter { it.kind == NodeKind.READ }.forEach { read ->
        val neighborCluster = model.edges.asSequence()
            .filter { it.from == read.id || it.to == read.id }
            .mapNotNull { edge -> clusterIndexById[if (edge.from == read.id) edge.to else edge.from] }
            .firstOrNull()
        if (neighborCluster != null && clusters[neighborCluster].size < PROJECT_OVERVIEW_MAX_CLUSTER_NODES + 6) {
            clusters[neighborCluster] += read.id
        } else {
            readOverflow += read.id
        }
    }
    readOverflow.chunked(PROJECT_OVERVIEW_MAX_CLUSTER_NODES).forEach { chunk ->
        clusters += LinkedHashSet(chunk)
    }

    return clusters.sortedWith(
        compareByDescending<Set<String>> { ids -> ids.count { byId[it]?.kind == NodeKind.STATE } }
            .thenByDescending { it.size },
    )
}



internal fun GraphCanvas.connectedComponents(model: FlowGraph): List<Set<String>> {
    val ids = model.nodes.mapTo(linkedSetOf()) { it.id }
    if (ids.isEmpty()) return emptyList()
    val adjacency = ids.associateWithTo(mutableMapOf()) { linkedSetOf<String>() }
    model.edges.forEach { edge ->
        if (edge.from !in ids || edge.to !in ids) return@forEach
        // All visible edges participate here, including READS and synthetic boundary nodes.
        // If an edge is drawn between two nodes they must be packed into the same component;
        // otherwise the edge itself creates a giant empty span between separately packed boxes.
        adjacency.getValue(edge.from) += edge.to
        adjacency.getValue(edge.to) += edge.from
    }

    val remaining = ids.toMutableSet()
    val components = mutableListOf<Set<String>>()
    while (remaining.isNotEmpty()) {
        val start = remaining.first()
        val component = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue += start
        remaining -= start
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            component += current
            adjacency[current].orEmpty().forEach { next ->
                if (remaining.remove(next)) queue += next
            }
        }
        components += component
    }
    return components.sortedWith(
        compareByDescending<Set<String>> { component ->
            component.count { id -> model.node(id)?.kind == NodeKind.STATE }
        }.thenByDescending { it.size }
    )
}


