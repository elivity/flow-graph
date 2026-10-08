package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.focusRecentActivity(
    activeNodeIds: Set<String>,
    maxConnectorHops: Int = 6,
): FlowGraph {
    val active = activeNodeIds.filterTo(linkedSetOf()) { it in nodesById }
    val recentLabel = if (rootLabel.startsWith("All project flows")) {
        "All project flows • recently active"
    } else {
        "Recently active"
    }
    if (active.isEmpty()) {
        return copy(
            rootLabel = "$recentLabel • waiting",
            nodes = emptyList(),
            edges = emptyList(),
            fieldDependencies = emptyList(),
        )
    }
    if (active.size == 1) return filtered(recentLabel, active)

    val adjacency = mutableMapOf<String, MutableList<String>>()
    edges.asSequence()
        .filter { it.kind != EdgeKind.READS }
        .forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            adjacency.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        }

    val keep = linkedSetOf<String>().apply { addAll(active) }

    // For every active node, retain the shortest path to its nearest other active node.
    // This is intentionally bounded: the recent filter must stay compact even in spaghetti.
    active.forEach { start ->
        val queue = ArrayDeque<String>()
        val depth = mutableMapOf(start to 0)
        val parent = mutableMapOf<String, String>()
        queue.addLast(start)
        var found: String? = null

        while (queue.isNotEmpty() && found == null) {
            val current = queue.removeFirst()
            val currentDepth = depth.getValue(current)
            if (currentDepth >= maxConnectorHops) continue
            adjacency[current].orEmpty().forEach neighborLoop@ { next ->
                if (next in depth) return@neighborLoop
                depth[next] = currentDepth + 1
                parent[next] = current
                if (next in active && next != start) {
                    found = next
                    return@neighborLoop
                }
                queue.addLast(next)
            }
        }

        var cursor = found
        while (cursor != null && cursor != start) {
            keep += cursor
            cursor = parent[cursor]
        }
        keep += start
    }

    return filtered(recentLabel, keep)
}

/**
 * Runtime-observed branch projection for the project overview.
 *
 * Unlike [focusRecentActivity], this is session-persistent: callers normally pass every node
 * that has produced runtime evidence since the last Clear runtime. Active nodes are retained,
 * short static connector paths keep related state/Flow activity intelligible, and Compose
 * ancestors are retained so an active leaf still appears in its screen/tree context. Inactive
 * sibling branches are deliberately omitted.
 *
 * [observedEdges] lets the runtime pin endpoints of statically-known transitions that were
 * actually observed even when one endpoint has no standalone counter in the current overlay.
 */



internal fun FlowGraph.focusRuntimeActivityBranches(
    activeNodeIds: Set<String>,
    observedEdges: Set<Pair<String, String>> = emptySet(),
    maxConnectorHops: Int = 8,
): FlowGraph {
    val active = activeNodeIds.filterTo(linkedSetOf()) { it in nodesById }
    val observedEndpoints = observedEdges.asSequence()
        .flatMap { sequenceOf(it.first, it.second) }
        .filterTo(linkedSetOf()) { it in nodesById }
    val seeds = linkedSetOf<String>().apply {
        addAll(active)
        addAll(observedEndpoints)
    }
    val architectureSuffix = if ("flow/state graph" in rootLabel) " • flow/state graph" else ""
    val label = if (rootLabel.startsWith("All project flows")) {
        "All project flows • runtime branches$architectureSuffix"
    } else {
        rootLabel.replace(" • runtime branches", "") + " • runtime branches$architectureSuffix"
    }

    if (seeds.isEmpty()) {
        return copy(
            rootLabel = "$label • waiting",
            nodes = emptyList(),
            edges = emptyList(),
            fieldDependencies = emptyList(),
        )
    }

    // Reuse the compact shortest-path connector logic for state/Flow/runtime helper evidence.
    // This keeps activity islands causally readable without resurrecting every static sibling.
    val keep = linkedSetOf<String>()
    keep += focusRecentActivity(seeds, maxConnectorHops).nodes.map { it.id }
    keep += seeds

    // Keep the Compose endpoints driven by a retained runtime Flow/state node even when the
    // composable itself has not produced a runtime counter yet (for example because equality
    // suppressed a recomposition). This preserves the architectural answer to "what UI can this
    // active branch influence?" without bringing back inactive Flow siblings.
    val influencedComposeTargets = edges.asSequence()
        .filter { it.kind == EdgeKind.UPDATES_COMPOSE && it.from in keep }
        .map { it.to }
        .filterTo(linkedSetOf()) { nodesById[it]?.kind == NodeKind.COMPOSABLE }
    keep += influencedComposeTargets

    // A composable can execute while its parent does not recompose. Keep only its ancestry,
    // never its inactive children/siblings, so the branch remains recognizable in the UI tree.
    val incomingCompose = edges.asSequence()
        .filter { it.kind == EdgeKind.COMPOSES }
        .groupBy { it.to }
    val composeQueue = ArrayDeque<String>()
    seeds.asSequence()
        .filter { nodesById[it]?.kind == NodeKind.COMPOSABLE }
        .forEach(composeQueue::addLast)
    influencedComposeTargets.forEach(composeQueue::addLast)
    val visitedCompose = linkedSetOf<String>()
    while (composeQueue.isNotEmpty()) {
        val child = composeQueue.removeFirst()
        if (!visitedCompose.add(child)) continue
        incomingCompose[child].orEmpty().forEach { edge ->
            if (edge.from !in nodesById) return@forEach
            if (keep.add(edge.from)) {
                composeQueue.addLast(edge.from)
            } else if (edge.from !in visitedCompose) {
                composeQueue.addLast(edge.from)
            }
        }
    }

    return filtered(label, keep)
}


