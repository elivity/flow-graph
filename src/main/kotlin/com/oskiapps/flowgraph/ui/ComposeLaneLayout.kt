package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.GraphCanvas.ComposeLanePlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.FlowTreePlacement
import com.oskiapps.flowgraph.ui.GraphCanvas.VisualEdge
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_DESCENDANT_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_INFLUENCE_BRIDGE_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_INFLUENCE_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_INFLUENCE_NODES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_INSTANCES_PER_LANE
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.MAX_PROJECT_COMPOSE_LANES
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_HEADER
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_CLUSTER_PADDING
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_HORIZONTAL_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_INFLUENCE_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_COMPOSE_PREFIX_DEPTH
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_FLOW_FOREST_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_FLOW_TREE_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_H
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_NODE_W
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_STATE_MACHINE_TO_COMPOSE_GAP
import com.oskiapps.flowgraph.ui.GraphCanvas.Companion.PROJECT_OVERVIEW_VERTICAL_GAP
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

internal fun GraphCanvas.buildComposeLanePlacements(
    model: FlowGraph,
    composeIds: Set<String>,
    composeEdges: List<FlowEdge>,
): List<ComposeLanePlacement> {
    val byId = model.nodes.asSequence().filter { it.id in composeIds }.associateBy { it.id }
    val outgoing = composeIds.associateWithTo(mutableMapOf()) { mutableListOf<FlowEdge>() }
    val incoming = composeIds.associateWithTo(mutableMapOf()) { mutableListOf<FlowEdge>() }
    composeEdges.forEach { edge ->
        outgoing.getValue(edge.from) += edge
        incoming.getValue(edge.to) += edge
    }
    val edgeComparator = compareBy<FlowEdge> { it.source?.file?.path.orEmpty() }
        .thenBy { it.source?.offset ?: Int.MAX_VALUE }
        .thenBy { byId[it.to]?.label.orEmpty() }
    outgoing.values.forEach { it.sortWith(edgeComparator) }
    incoming.values.forEach { it.sortWith(edgeComparator) }

    fun isSourceComposable(id: String): Boolean =
        isSourceComposeRuntimeKey(byId[id]?.runtimeKey)

    fun isInfrastructure(id: String): Boolean {
        val node = byId[id] ?: return false
        val label = node.label.removeSuffix("()")
        val frameworkCall = node.runtimeKey == null && node.detail.startsWith("Compose UI call")
        if (frameworkCall) return true
        if (label == "setContent" || label == "CompositionLocalProvider") return true
        if (label == "Surface" || label == "NavHost" || label == "Scaffold") return true
        if (label.endsWith("Theme") || label.endsWith("Scaffold") || label.endsWith("NavHost")) return true
        if (label.startsWith("Provide")) return true
        if (label.endsWith("App") && outgoing[id].orEmpty().size > 1) return true
        return false
    }

    fun firstReadableDescendants(start: String): List<String> {
        if (start !in composeIds) return emptyList()
        if (isSourceComposable(start) && !isInfrastructure(start)) {
            // Route functions often only collect state and immediately delegate to the visual
            // Screen. Prefer the Screen as the lane's reading root when one is directly below
            // the Route; the Route still remains available in the canonical graph/details.
            if (byId[start]?.label.orEmpty().endsWith("Route")) {
                val screenDescendants = linkedSetOf<String>()
                val seen = linkedSetOf(start)
                val queue = ArrayDeque<String>()
                queue.addLast(start)
                while (queue.isNotEmpty() && screenDescendants.size < 4) {
                    val current = queue.removeFirst()
                    outgoing[current].orEmpty().forEach { edge ->
                        val child = edge.to
                        if (!seen.add(child)) return@forEach
                        val childNode = byId[child]
                        if (childNode != null &&
                            isSourceComposeRuntimeKey(childNode.runtimeKey) &&
                            childNode.label.endsWith("Screen")
                        ) {
                            screenDescendants += child
                        } else if (isInfrastructure(child) || isSourceComposeRuntimeKey(childNode?.runtimeKey)) {
                            queue.addLast(child)
                        }
                    }
                }
                if (screenDescendants.isNotEmpty()) return screenDescendants.toList()
            }
            return listOf(start)
        }
        val found = linkedSetOf<String>()
        val seen = linkedSetOf(start)
        val queue = ArrayDeque<String>()
        queue.addLast(start)
        while (queue.isNotEmpty() && found.size < MAX_PROJECT_COMPOSE_LANES) {
            val current = queue.removeFirst()
            outgoing[current].orEmpty().forEach { edge ->
                val child = edge.to
                if (!seen.add(child)) return@forEach
                if (isSourceComposable(child) && !isInfrastructure(child)) {
                    found += child
                } else {
                    queue.addLast(child)
                }
            }
        }
        return found.toList()
    }

    // State-connected Compose owners are the most useful definition of a "main" lane. They
    // typically correspond to Screen/Route composables and, crucially, are below global app
    // wrappers. If the direct owner is only setContent/Theme/Surface infrastructure, peel it
    // until the first readable source composables.
    val anchored = model.edges.asSequence()
        .filter { it.kind == EdgeKind.UPDATES_COMPOSE && it.to in composeIds }
        .map { it.to }
        .distinct()
        .toList()
    val laneRoots = linkedSetOf<String>()
    anchored.forEach { anchor ->
        val readable = firstReadableDescendants(anchor)
        if (readable.isEmpty() && anchor in composeIds) laneRoots += anchor else laneRoots += readable
    }

    // Fallback for Compose-only or very static surfaces that have no Flow state anchor.
    if (laneRoots.isEmpty()) {
        val screenLike = composeIds.asSequence()
            .filter(::isSourceComposable)
            .filter { id ->
                val label = byId[id]?.label.orEmpty()
                label.endsWith("Screen") || label.endsWith("Route") || label.endsWith("Page") ||
                    label.endsWith("Dialog") || label.endsWith("Sheet")
            }
            .toList()
        if (screenLike.isNotEmpty()) {
            laneRoots += screenLike
        } else {
            val structuralRoots = composeIds.filter { incoming[it].isNullOrEmpty() }
            structuralRoots.forEach { root ->
                val readable = firstReadableDescendants(root)
                if (readable.isEmpty()) laneRoots += root else laneRoots += readable
            }
        }
    }
    if (laneRoots.isEmpty()) laneRoots += composeIds.first()

    val sortedRoots = laneRoots
        .sortedWith(
            compareBy<String> { byId[it]?.source?.file?.path.orEmpty() }
                .thenBy { byId[it]?.source?.offset ?: Int.MAX_VALUE }
                .thenBy { byId[it]?.label.orEmpty() },
        )
        .take(MAX_PROJECT_COMPOSE_LANES)

    return sortedRoots.mapIndexedNotNull { laneIndex, rootId ->
        buildComposeLanePlacement(
            laneIndex = laneIndex,
            rootId = rootId,
            model = model,
            byId = byId,
            outgoing = outgoing,
            incoming = incoming,
            isInfrastructure = ::isInfrastructure,
        )
    }
}



internal fun GraphCanvas.buildComposeLanePlacement(
    laneIndex: Int,
    rootId: String,
    model: FlowGraph,
    byId: Map<String, FlowNode>,
    outgoing: Map<String, List<FlowEdge>>,
    incoming: Map<String, List<FlowEdge>>,
    isInfrastructure: (String) -> Boolean,
): ComposeLanePlacement? {
    val rootNode = byId[rootId] ?: return null
    var visualSequence = 0
    fun newVisualId(): String = "compose-lane:$laneIndex:${visualSequence++}"

    val sourceByVisual = linkedMapOf<String, String>()
    val depthByVisual = mutableMapOf<String, Int>()
    val childrenByVisual = mutableMapOf<String, MutableList<String>>()
    val visualEdges = mutableListOf<VisualEdge>()
    var instanceCount = 0

    // Only repeat a short wrapper prefix. This gives useful local context such as
    // Theme -> Surface -> Screen without resurrecting one enormous global root.
    val prefixEdgesReversed = mutableListOf<FlowEdge>()
    var ancestorCursor = rootId
    val ancestorSeen = linkedSetOf(rootId)
    for (ignored in 0 until PROJECT_OVERVIEW_COMPOSE_PREFIX_DEPTH) {
        val parentEdge = incoming[ancestorCursor].orEmpty()
            .asSequence()
            .filter { it.from !in ancestorSeen }
            .filter { isInfrastructure(it.from) }
            .sortedWith(
                compareBy<FlowEdge> { it.source?.file?.path.orEmpty() }
                    .thenBy { it.source?.offset ?: Int.MAX_VALUE }
                    .thenBy { byId[it.from]?.label.orEmpty() },
            )
            .firstOrNull()
            ?: break
        prefixEdgesReversed += parentEdge
        ancestorCursor = parentEdge.from
        ancestorSeen += ancestorCursor
    }
    val prefixEdges = prefixEdgesReversed.asReversed()
    val prefixSourceIds = buildList {
        if (prefixEdges.isNotEmpty()) {
            add(prefixEdges.first().from)
            prefixEdges.forEach { add(it.to) }
        } else {
            add(rootId)
        }
    }
    val prefixCount = (prefixSourceIds.size - 1).coerceAtLeast(0)

    fun expand(sourceId: String, depth: Int, path: LinkedHashSet<String>): String? {
        if (instanceCount >= MAX_PROJECT_COMPOSE_INSTANCES_PER_LANE) return null
        val visualId = newVisualId()
        instanceCount++
        sourceByVisual[visualId] = sourceId
        depthByVisual[visualId] = depth
        childrenByVisual[visualId] = mutableListOf()

        if (depth - prefixCount >= MAX_PROJECT_COMPOSE_DESCENDANT_DEPTH) return visualId
        val nextPath = LinkedHashSet(path)
        nextPath += sourceId
        outgoing[sourceId].orEmpty().forEach { edge ->
            if (instanceCount >= MAX_PROJECT_COMPOSE_INSTANCES_PER_LANE) return@forEach
            // Recursion can be real in Compose helpers; show the recursive child once but do
            // not keep expanding the same canonical node along this path.
            val child = edge.to
            val childVisual = if (child in nextPath) {
                if (instanceCount >= MAX_PROJECT_COMPOSE_INSTANCES_PER_LANE) null else {
                    val id = newVisualId()
                    instanceCount++
                    sourceByVisual[id] = child
                    depthByVisual[id] = depth + 1
                    childrenByVisual[id] = mutableListOf()
                    id
                }
            } else {
                expand(child, depth + 1, nextPath)
            }
            if (childVisual != null) {
                childrenByVisual.getValue(visualId) += childVisual
                visualEdges += VisualEdge(visualId, childVisual, edge)
            }
        }
        return visualId
    }

    val mainVisualId = expand(rootId, prefixCount, linkedSetOf()) ?: return null

    // Materialize the wrapper prefix as lane-local copies and connect it to the main tree.
    var laneVisualRoot = mainVisualId
    if (prefixEdges.isNotEmpty()) {
        var nextVisual = mainVisualId
        for (index in prefixEdges.indices.reversed()) {
            val edge = prefixEdges[index]
            val parentSource = edge.from
            val parentVisual = newVisualId()
            sourceByVisual[parentVisual] = parentSource
            depthByVisual[parentVisual] = index
            childrenByVisual[parentVisual] = mutableListOf(nextVisual)
            visualEdges += VisualEdge(parentVisual, nextVisual, edge)
            nextVisual = parentVisual
        }
        laneVisualRoot = nextVisual
    }

    val rowStride = PROJECT_OVERVIEW_NODE_H + PROJECT_OVERVIEW_VERTICAL_GAP
    var nextLeafRow = 0
    val topByVisual = mutableMapOf<String, Int>()
    val placing = mutableSetOf<String>()

    fun place(visualId: String): Int {
        topByVisual[visualId]?.let { return it }
        if (!placing.add(visualId)) {
            val fallback = PROJECT_OVERVIEW_CLUSTER_HEADER + nextLeafRow++ * rowStride
            topByVisual[visualId] = fallback
            return fallback
        }
        val children = childrenByVisual[visualId].orEmpty().filter { it !in placing }
        val y = if (children.isEmpty()) {
            PROJECT_OVERVIEW_CLUSTER_HEADER + nextLeafRow++ * rowStride
        } else {
            val childCenters = children.map { child -> place(child) + PROJECT_OVERVIEW_NODE_H / 2 }
            ((childCenters.first() + childCenters.last()) / 2) - PROJECT_OVERVIEW_NODE_H / 2
        }
        placing.remove(visualId)
        topByVisual[visualId] = y
        return y
    }
    place(laneVisualRoot)
    sourceByVisual.keys.filter { it !in topByVisual }.forEach(::place)

    val bounds = linkedMapOf<String, Rectangle>()
    val columnStride = PROJECT_OVERVIEW_NODE_W + PROJECT_OVERVIEW_COMPOSE_HORIZONTAL_GAP
    sourceByVisual.keys.forEach { visualId ->
        bounds[visualId] = Rectangle(
            PROJECT_OVERVIEW_CLUSTER_PADDING + depthByVisual.getValue(visualId) * columnStride,
            topByVisual.getValue(visualId),
            PROJECT_OVERVIEW_NODE_W,
            PROJECT_OVERVIEW_NODE_H,
        )
    }

    // Mirror the focused/detail view locally inside every overview lane: the Flow/State nodes
    // that can invalidate this composition are repeated immediately to the left of the Compose
    // tree. They are visual instances only; the canonical FlowGraph stays de-duplicated. This
    // deliberately favors readability over global graph identity: a StateFlow that feeds three
    // screens can be drawn three times instead of creating three long cross-canvas edges.
    val composeVisualsBySource = sourceByVisual.entries
        .groupBy({ it.value }, { it.key })

    // A Route often owns collectAsState* but is intentionally omitted as the visual lane root
    // in favor of its Screen. Project the update onto the nearest visible descendant in this
    // lane instead of losing the Flow -> Compose context. This same bridge also handles a local
    // Theme/Surface wrapper without turning that wrapper back into a global hub.
    val projectedTargetsCache = mutableMapOf<String, List<String>>()
    fun projectedLaneTargets(composeSourceId: String): List<String> =
        projectedTargetsCache.getOrPut(composeSourceId) {
            composeVisualsBySource[composeSourceId]?.let { return@getOrPut it }
            data class ComposeBridgeStep(val id: String, val depth: Int)
            val seen = linkedSetOf(composeSourceId)
            val bfs = ArrayDeque<ComposeBridgeStep>()
            bfs.addLast(ComposeBridgeStep(composeSourceId, 0))
            var foundDepth: Int? = null
            val found = mutableListOf<String>()
            while (bfs.isNotEmpty()) {
                val step = bfs.removeFirst()
                if (step.depth >= MAX_PROJECT_COMPOSE_INFLUENCE_BRIDGE_DEPTH) continue
                val knownDepth = foundDepth
                if (knownDepth != null && step.depth >= knownDepth) continue
                outgoing[step.id].orEmpty().forEach { edge ->
                    val nextDepth = step.depth + 1
                    val visible = composeVisualsBySource[edge.to].orEmpty()
                    if (visible.isNotEmpty()) {
                        if (foundDepth == null || nextDepth < foundDepth) {
                            foundDepth = nextDepth
                            found.clear()
                        }
                        if (nextDepth == foundDepth) found += visible
                    } else if (foundDepth == null && seen.add(edge.to)) {
                        bfs.addLast(ComposeBridgeStep(edge.to, nextDepth))
                    }
                }
            }
            found.distinct()
        }

    val updateTargetsByEdge = linkedMapOf<FlowEdge, List<String>>()
    val directUpdates = model.edges
        .asSequence()
        .filter { edge ->
            edge.kind == EdgeKind.UPDATES_COMPOSE &&
                model.node(edge.from)?.kind != NodeKind.COMPOSABLE
        }
        .mapNotNull { edge ->
            val targets = projectedLaneTargets(edge.to)
            if (targets.isEmpty()) null else edge.also { updateTargetsByEdge[it] = targets }
        }
        .sortedWith(
            compareBy<FlowEdge> { it.source?.file?.path.orEmpty() }
                .thenBy { it.source?.offset ?: Int.MAX_VALUE }
                .thenBy { model.node(it.from)?.label.orEmpty() }
                .thenBy { model.node(it.to)?.label.orEmpty() },
        )
        .toList()

    val localFlowSourceIds = linkedSetOf<String>()
    var flowStateGraphNodeCount = 0
    var flowStateGraphStateCount = 0
    var flowStateGraphCoroutineCount = 0
    if (directUpdates.isNotEmpty()) {
        // Collapsed State Changes contains state-only topology. Runtime branches adds the
        // coroutine stages back through flowStateArchitectureProjection(). Both are rendered
        // by the same de-duplicated causal graph so state/Flow dependencies remain graph-shaped
        // and feed Compose only at the rightmost (or, vertically, bottommost) observed layer.
        val architectureGraphEligible =
            "state changes" in model.rootLabel ||
                "flow/state graph" in model.rootLabel ||
                "runtime branches" in model.rootLabel
        val stateMachine = if (architectureGraphEligible) {
            buildComposeStateMachinePlacement(
                model = model,
                observedStateIds = directUpdates.mapTo(linkedSetOf()) { it.from },
                visualPrefix = "compose-state-machine:$laneIndex:",
            )
        } else null

        if (stateMachine != null) {
            flowStateGraphNodeCount = stateMachine.sourceIds.size
            flowStateGraphStateCount = stateMachine.sourceIds.count { model.node(it)?.kind == NodeKind.STATE }
            flowStateGraphCoroutineCount = flowStateGraphNodeCount - flowStateGraphStateCount
            localFlowSourceIds += stateMachine.sourceIds

            val machineMinY = stateMachine.bounds.values.minOfOrNull { it.y }
                ?: PROJECT_OVERVIEW_CLUSTER_HEADER
            val machineMaxY = stateMachine.bounds.values.maxOfOrNull { it.y + it.height }
                ?: (machineMinY + PROJECT_OVERVIEW_NODE_H)
            val machineContentHeight = (machineMaxY - machineMinY).coerceAtLeast(PROJECT_OVERVIEW_NODE_H)
            val composeMinY = bounds.values.minOfOrNull { it.y } ?: PROJECT_OVERVIEW_CLUSTER_HEADER
            val composeMaxY = bounds.values.maxOfOrNull { it.y + it.height }
                ?: (composeMinY + PROJECT_OVERVIEW_NODE_H)
            val composeCenterY = (composeMinY + composeMaxY) / 2
            val desiredMachineTop = (composeCenterY - machineContentHeight / 2)
                .coerceAtLeast(PROJECT_OVERVIEW_CLUSTER_HEADER)
            val machineDy = desiredMachineTop - machineMinY

            // Flow/state graph first, Compose second. Runtime-active operators/states form one
            // de-duplicated causal network; Compose-driving endpoints occupy the rightmost graph
            // layer, leaving only short UI-driving arrows across this deliberate gutter.
            val composeShift = stateMachine.width + PROJECT_OVERVIEW_STATE_MACHINE_TO_COMPOSE_GAP
            bounds.values.forEach { it.translate(composeShift, 0) }

            stateMachine.bounds.forEach { (visualId, r) ->
                bounds[visualId] = Rectangle(r.x, r.y + machineDy, r.width, r.height)
                sourceByVisual[visualId] = stateMachine.sourceNodeIdByVisualId.getValue(visualId)
            }
            visualEdges += stateMachine.visualEdges

            directUpdates.forEach { update ->
                val stateVisual = stateMachine.visualIdBySourceNodeId[update.from]
                    ?: return@forEach
                updateTargetsByEdge[update].orEmpty().forEach { targetVisualId ->
                    visualEdges += VisualEdge(stateVisual, targetVisualId, update)
                }
            }
        } else {
            data class InfluenceTree(
                val update: FlowEdge,
                val targetVisualId: String,
                val tree: FlowTreePlacement,
            )

            val influenceTrees = mutableListOf<InfluenceTree>()
            var influenceSequence = 0
            for (update in directUpdates) {
                for (targetVisualId in updateTargetsByEdge[update].orEmpty()) {
                    val tree = buildUpstreamFlowTreePlacement(
                        model = model,
                        sinkId = update.from,
                        visualPrefix = "compose-flow-lane:$laneIndex:${influenceSequence++}:",
                        includeReads = false,
                        maxDepth = MAX_PROJECT_COMPOSE_INFLUENCE_DEPTH,
                        maxInstances = MAX_PROJECT_COMPOSE_INFLUENCE_NODES,
                    ) ?: continue
                    localFlowSourceIds += tree.sourceIds
                    influenceTrees += InfluenceTree(update, targetVisualId, tree)
                }
            }

            if (influenceTrees.isNotEmpty()) {
                // Detailed operator mode keeps one explanatory upstream tree per Compose target.
                // Shared Flow nodes may repeat here on purpose because this mode answers "how did
                // this value get here?" rather than presenting the architectural state machine.
                val maxTreeRight = influenceTrees.maxOf { item ->
                    item.tree.bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W
                }
                val composeShift = maxTreeRight + PROJECT_OVERVIEW_COMPOSE_INFLUENCE_GAP
                bounds.values.forEach { it.translate(composeShift, 0) }

                val byTarget = influenceTrees.groupBy { it.targetVisualId }
                    .entries
                    .sortedBy { (targetVisualId, _) -> bounds[targetVisualId]?.y ?: Int.MAX_VALUE }
                var previousForestBottom = Int.MIN_VALUE / 4

                byTarget.forEach { (targetVisualId, targetTrees) ->
                    val targetBounds = bounds[targetVisualId] ?: return@forEach
                    val treeGap = PROJECT_OVERVIEW_FLOW_TREE_GAP
                    var forestHeight = 0
                    val treeVerticalOffsets = mutableListOf<Pair<InfluenceTree, Int>>()
                    targetTrees.forEachIndexed { index, item ->
                        val minY = item.tree.bounds.values.minOfOrNull { it.y } ?: 0
                        val maxY = item.tree.bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H
                        val treeHeight = (maxY - minY).coerceAtLeast(PROJECT_OVERVIEW_NODE_H)
                        if (index > 0) forestHeight += treeGap
                        treeVerticalOffsets += item to (forestHeight - minY)
                        forestHeight += treeHeight
                    }

                    val targetCenterY = targetBounds.y + targetBounds.height / 2
                    val desiredForestTop = targetCenterY - forestHeight / 2
                    val forestTop = max(
                        desiredForestTop,
                        previousForestBottom + PROJECT_OVERVIEW_FLOW_FOREST_GAP,
                    )
                    previousForestBottom = forestTop + forestHeight

                    treeVerticalOffsets.forEach { (item, localDy) ->
                        val treeRight = item.tree.bounds.values.maxOfOrNull { it.x + it.width }
                            ?: PROJECT_OVERVIEW_NODE_W
                        // Right-align every upstream tree immediately before the Compose section.
                        val desiredRight = PROJECT_OVERVIEW_CLUSTER_PADDING + maxTreeRight
                        val dx = desiredRight - treeRight
                        val dy = forestTop + localDy

                        item.tree.bounds.forEach { (visualId, r) ->
                            bounds[visualId] = Rectangle(
                                r.x + dx,
                                r.y + dy,
                                r.width,
                                r.height,
                            )
                            sourceByVisual[visualId] = item.tree.sourceNodeIdByVisualId.getValue(visualId)
                        }
                        visualEdges += item.tree.visualEdges
                        visualEdges += VisualEdge(
                            item.tree.sinkVisualId,
                            targetVisualId,
                            item.update,
                        )
                    }
                }

                // A forest centred on a high Compose node can extend above the lane header. Shift
                // the entire lane together so no node receives a negative/top-clipped coordinate.
                val minLaneY = bounds.values.minOfOrNull { it.y } ?: PROJECT_OVERVIEW_CLUSTER_HEADER
                if (minLaneY < PROJECT_OVERVIEW_CLUSTER_HEADER) {
                    val dy = PROJECT_OVERVIEW_CLUSTER_HEADER - minLaneY
                    bounds.values.forEach { it.translate(0, dy) }
                }
            }
        }
    }

    val width = (bounds.values.maxOfOrNull { it.x + it.width } ?: PROJECT_OVERVIEW_NODE_W) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val height = (bounds.values.maxOfOrNull { it.y + it.height } ?: PROJECT_OVERVIEW_NODE_H) +
        PROJECT_OVERVIEW_CLUSTER_PADDING
    val composeVisualSources = sourceByVisual
        .filterKeys { it.startsWith("compose-lane:") }
        .values
    val duplicateCount = composeVisualSources.size - composeVisualSources.toSet().size
    val label = buildString {
        append("Compose lane • ").append(semanticClusterNodeLabel(rootNode))
        if (flowStateGraphNodeCount > 0) {
            append(" • Flow/state graph ").append(flowStateGraphNodeCount).append(" nodes")
            if (flowStateGraphStateCount > 0) {
                append(" • ").append(flowStateGraphStateCount).append(" state")
                if (flowStateGraphStateCount != 1) append("s")
            }
            if (flowStateGraphCoroutineCount > 0) {
                append(" • ").append(flowStateGraphCoroutineCount).append(" coroutine stage")
                if (flowStateGraphCoroutineCount != 1) append("s")
            }
        } else if (localFlowSourceIds.isNotEmpty()) {
            append(" • ").append(localFlowSourceIds.size).append(" influencing flow/state node")
            if (localFlowSourceIds.size != 1) append("s")
        }
        if (duplicateCount > 0) append(" • ").append(duplicateCount).append(" repeated Compose instance")
        if (duplicateCount != 1 && duplicateCount > 0) append("s")
    }

    return ComposeLanePlacement(
        bounds = bounds,
        sourceNodeIdByVisualId = sourceByVisual,
        visualEdges = visualEdges,
        width = width,
        height = height,
        label = label,
        localFlowSourceIds = localFlowSourceIds,
    )
}


/**
 * Removes only unused outer coordinate space from All flows. Do not scale node geometry here:
 * scaling the coordinates themselves destroys the spacing that makes edges legible. Fit is
 * handled by the canvas zoom, while semantic clustering keeps 200+ node overviews compact.
 *
 * Fit is not allowed below 20%, therefore the layout itself must fit inside the logical
 * viewport available at 20%. This pass measures the *actual occupied bounds* after component
 * packing and, only when necessary, uniformly compacts the geometry so Fit is guaranteed to
 * reach the complete graph without panning. This is intentionally an overview-only transform;
 * selecting a node returns to full-size focused nodes.
 */


