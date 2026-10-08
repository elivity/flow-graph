@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ComposeTopologyEdge
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ComposeVisualCall
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.COMPOSE_DSL_SCOPE_TYPES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.LAZY_COMPOSE_DSL_CALLS
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_CANDIDATE_CALLS
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_TOPOLOGY_DISCOVERED_EDGES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_TOPOLOGY_EDGES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_TOPOLOGY_NEW_NODES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_COMPOSE_CALL_DEPTH
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_COMPOSE_NODES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_COMPOSE_VISUAL_CALLS_PER_BODY
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.oskiapps.flowgraph.model.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import java.util.ArrayDeque

internal fun AnalysisApiFlowAnalyzer.addComposableChildren(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    function: KtNamedFunction,
    functionId: String,
    depth: Int,
    visited: MutableSet<KtNamedFunction>,
) {
    val body = function.bodyExpression ?: return
    addComposableChildrenFromBody(
        nodes = nodes,
        edges = edges,
        body = body,
        functionId = functionId,
        currentFunction = function,
        depth = depth,
        visited = visited,
    )
}



internal fun AnalysisApiFlowAnalyzer.addComposableChildrenFromBody(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    body: PsiElement,
    functionId: String,
    currentFunction: KtNamedFunction?,
    depth: Int,
    visited: MutableSet<KtNamedFunction>,
    expandSourceBodies: Boolean = true,
    allowedSourceIds: Set<String>? = null,
    allowedSourceNames: Set<String>? = null,
    maxGraphNodes: Int = Int.MAX_VALUE,
    maxComposeNodes: Int = MAX_COMPOSE_NODES,
) {
    if (
        depth >= MAX_COMPOSE_CALL_DEPTH ||
        nodes.size >= maxGraphNodes ||
        nodes.count { it.value.kind == NodeKind.COMPOSABLE } >= maxComposeNodes
    ) return

    // Keep the actual composition nesting instead of flattening every descendant call back to
    // the owning source function. This is what makes a graph read naturally as:
    // Screen -> LazyVerticalGrid -> Item -> Button -> Text.
    //
    // Source @Composable calls are resolved exactly. For Compose framework calls we materialize
    // visual PascalCase call sites (Column, Row, Text, Button, LazyVerticalGrid, Scaffold, ...).
    // Lower-case helpers such as remember/stringResource/padding are intentionally omitted so
    // the result stays a UI/composition tree rather than becoming a general call graph.
    val candidates = PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java)
        .asSequence()
        .filter { call ->
            currentFunction == null ||
                PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java, true) == currentFunction
        }
        .filter { call ->
            val name = call.calleeExpression?.text ?: return@filter false
            allowedSourceNames == null ||
                name in allowedSourceNames ||
                name.firstOrNull()?.isUpperCase() == true ||
                name in LAZY_COMPOSE_DSL_CALLS
        }
        .take(MAX_COMPOSE_VISUAL_CALLS_PER_BODY)
        .toList()
    if (candidates.isEmpty()) return

    val resolved = FlowSemantics.resolvedFunctionCalls(candidates)
    val visualCalls = candidates.mapNotNull { call ->
        ProgressManager.checkCanceled()
        val info = resolved[call]
        val sourceTarget = info?.sourceFunction
            ?.takeIf { isProjectComposeSourceFunction(it) }
            ?.takeIf { allowedSourceIds == null || composeSourceId(it) in allowedSourceIds }
        when {
            sourceTarget != null -> ComposeVisualCall(
                call = call,
                node = composeSourceNode(sourceTarget),
                sourceFunction = sourceTarget,
            )

            isFrameworkVisualComposeCall(call, info) -> ComposeVisualCall(
                call = call,
                node = composeFrameworkCallNode(call, info?.callableId),
                sourceFunction = null,
            )

            else -> null
        }
    }
    if (visualCalls.isEmpty()) return

    val visualByCall = visualCalls.associateBy { it.call }
    visualCalls.forEach { visual ->
        if (
            visual.node.id !in nodes &&
            (nodes.size >= maxGraphNodes || nodes.count { it.value.kind == NodeKind.COMPOSABLE } >= maxComposeNodes)
        ) return@forEach
        nodes.putIfAbsent(visual.node.id, visual.node)

        var parentCall = PsiTreeUtil.getParentOfType(visual.call, KtCallExpression::class.java, true)
        var parentVisual: ComposeVisualCall? = null
        while (parentCall != null) {
            val candidateParent = visualByCall[parentCall]
            if (candidateParent != null && candidateParent.node.id in nodes) {
                parentVisual = candidateParent
                break
            }
            parentCall = PsiTreeUtil.getParentOfType(parentCall, KtCallExpression::class.java, true)
        }

        addEdge(
            edges,
            FlowEdge(
                from = parentVisual?.node?.id ?: functionId,
                to = visual.node.id,
                kind = EdgeKind.COMPOSES,
                label = "composes",
                source = sourceLocation(visual.call),
            ),
        )
    }

    if (!expandSourceBodies) return
    visualCalls.mapNotNull { it.sourceFunction }.distinctBy(::composeSourceId).forEach { target ->
        if (visited.add(target)) {
            addComposableChildren(
                nodes = nodes,
                edges = edges,
                function = target,
                functionId = composeSourceId(target),
                depth = depth + 1,
                visited = visited,
            )
        }
    }
}



internal fun AnalysisApiFlowAnalyzer.isFrameworkVisualComposeCall(call: KtCallExpression, info: ResolvedFunctionCall?): Boolean {
    val name = call.calleeExpression?.text ?: return false
    val callableId = info?.callableId.orEmpty()

    // Real @Composable framework calls (Column, Row, Text, Button, LazyVerticalGrid, ...).
    if (name.firstOrNull()?.isUpperCase() == true) {
        // Namespace + PascalCase is not enough: value factories/constructors such as Offset(...),
        // Color(...), DpSize(...), etc. also live under androidx.compose and previously appeared as
        // fake composition nodes. Only a callable that actually carries @Composable belongs here.
        return info?.isComposable == true && callableId.startsWith("androidx.compose.")
    }

    // Lazy layout item builders are deliberately *not* @Composable themselves. Their content
    // lambdas are composable, however, so omitting these calls flattens or completely loses UI
    // such as LazyStaggeredGridScope.newsFeed { items { NewsCard(...) } }. Keep the DSL call as
    // a visual framework node so the composition reads Grid -> newsFeed -> items -> NewsCard.
    return name in LAZY_COMPOSE_DSL_CALLS && callableId.startsWith("androidx.compose.foundation.lazy")
}



internal fun AnalysisApiFlowAnalyzer.isProjectSourceComposable(function: KtNamedFunction): Boolean {
    if (!FlowSemantics.isComposableFunction(function)) return false
    if (FlowSemantics.isPreviewFunction(function)) return false
    val file = function.containingKtFile.virtualFile
    return ProjectFileIndex.getInstance(project).isInSourceContent(file)
}

/**
 * Non-@Composable project helpers on Compose lazy DSL scopes still define composition. A common
 * example is `fun LazyStaggeredGridScope.newsFeed(...) { items(...) { NewsCard(...) } }`.
 * Treat these source functions as transparent Compose topology owners even though the function
 * itself is not a recomposition scope and therefore has no runtime Compose key.
 */



internal fun AnalysisApiFlowAnalyzer.isProjectComposeDslHelper(function: KtNamedFunction): Boolean {
    if (FlowSemantics.isComposableFunction(function) || FlowSemantics.isPreviewFunction(function)) return false
    val receiverText = function.receiverTypeReference?.text ?: return false
    val receiverSimpleName = receiverText
        .substringAfterLast('.')
        .substringBefore('<')
        .removeSuffix("?")
        .trim()
    if (receiverSimpleName !in COMPOSE_DSL_SCOPE_TYPES) return false
    val file = function.containingKtFile.virtualFile
    val index = ProjectFileIndex.getInstance(project)
    return index.isInSourceContent(file) && !index.isInTestSourceContent(file)
}



internal fun AnalysisApiFlowAnalyzer.isProjectComposeSourceFunction(function: KtNamedFunction): Boolean =
    isProjectSourceComposable(function) || isProjectComposeDslHelper(function)



internal fun AnalysisApiFlowAnalyzer.composeFrameworkCallNode(call: KtCallExpression, callableId: String?): FlowNode {
    val file = call.containingKtFile
    val name = call.calleeExpression?.text ?: callableId?.substringAfterLast('.') ?: "Compose"
    val id = "compose-call:${file.virtualFile.path}:${call.textOffset}"
    return FlowNode(
        id = id,
        label = name,
        detail = "Compose UI call • ${callableId ?: "androidx.compose"} • ${sourceDetail(call)}",
        kind = NodeKind.COMPOSABLE,
        source = sourceLocation(call),
        runtimeKey = null,
        groupKey = id,
        groupLabel = name,
        groupKind = NodeGroupKind.COMPOSE,
    )
}



internal fun AnalysisApiFlowAnalyzer.addProjectComposeTopology(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    diagnostics: MutableList<String>,
    composables: List<KtNamedFunction>,
    maxGraphNodes: Int,
) {
    val anchoredComposeIds = nodes.values
        .asSequence()
        .filter { it.kind == NodeKind.COMPOSABLE }
        .map { it.id }
        .toCollection(linkedSetOf())
    if (anchoredComposeIds.isEmpty() || composables.isEmpty()) return

    val functionsById = composables.associateBy(::composeSourceId)
    val composableNames = composables.asSequence().mapNotNull { it.name }.toHashSet()
    if (composableNames.isEmpty()) return

    val topologyEdges = linkedMapOf<Pair<String, String>, ComposeTopologyEdge>()
    val adjacency = linkedMapOf<String, LinkedHashSet<String>>()
    var functionsScanned = 0
    var candidateCallsResolved = 0
    var candidateCallLimitHits = 0
    var globalCandidateCallLimitHit = false
    var topologyEdgeDiscoveryTruncated = false

    fun recordEdge(fromId: String, toId: String, source: SourceLocation?) {
        // Keep direct recursion as real composition topology. The visual lane builder already
        // stops expansion when a canonical composable repeats on the current path.
        val key = fromId to toId
        if (key !in topologyEdges && topologyEdges.size >= MAX_ALL_COMPOSE_TOPOLOGY_DISCOVERED_EDGES) {
            topologyEdgeDiscoveryTruncated = true
            return
        }
        topologyEdges.putIfAbsent(key, ComposeTopologyEdge(fromId, toId, source))
        adjacency.getOrPut(fromId) { linkedSetOf() }.add(toId)
        adjacency.getOrPut(toId) { linkedSetOf() }.add(fromId)
    }

    fun analyzeBody(
        body: PsiElement,
        fromId: String,
        ownerFunction: KtNamedFunction?,
    ) {
        ProgressManager.checkCanceled()
        val globalRemaining = MAX_ALL_COMPOSE_CANDIDATE_CALLS - candidateCallsResolved
        if (globalRemaining <= 0) {
            globalCandidateCallLimitHit = true
            return
        }
        val perBodyLimit = minOf(MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION, globalRemaining)
        val candidatesWithSentinel = PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java)
            .asSequence()
            .filter { call ->
                val calleeName = call.calleeExpression?.text ?: return@filter false
                calleeName in composableNames
            }
            .filter { call ->
                ownerFunction == null ||
                    PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java, true) == ownerFunction
            }
            .take(perBodyLimit + 1)
            .toList()
        if (candidatesWithSentinel.size > perBodyLimit) {
            if (perBodyLimit < MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION) globalCandidateCallLimitHit = true
            else candidateCallLimitHits++
        }
        val candidates = candidatesWithSentinel.take(perBodyLimit)
        if (candidates.isEmpty()) return

        // One K2 analysis session per source function/host instead of one per call.
        val resolved = FlowSemantics.sourceFunctions(candidates)
        candidateCallsResolved += candidates.size
        candidates.forEach { call ->
            val target = resolved[call] ?: return@forEach
            val targetId = composeSourceId(target)
            if (targetId !in functionsById) return@forEach
            recordEdge(fromId, targetId, sourceLocation(call))
        }
    }

    // Deterministic order keeps both graph shape and truncation behavior stable across runs.
    for (function in composables.sortedBy(::composeSourceId).take(MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE)) {
        ProgressManager.checkCanceled()
        if (candidateCallsResolved >= MAX_ALL_COMPOSE_CANDIDATE_CALLS) {
            globalCandidateCallLimitHit = true
            break
        }
        functionsScanned++
        val body = function.bodyExpression ?: continue
        analyzeBody(body, composeSourceId(function), function)
    }


    if (topologyEdges.isEmpty()) return

    // Traverse the call graph as an undirected topology from state-connected Compose anchors.
    // This finds callers as well as callees, which is what joins formerly independent islands.
    val remainingGraphSlots = (maxGraphNodes - nodes.size).coerceAtLeast(0)
    val maxNewComposeNodes = minOf(MAX_ALL_COMPOSE_TOPOLOGY_NEW_NODES, remainingGraphSlots)
    val maxRetainedNodes = anchoredComposeIds.size + maxNewComposeNodes
    val retained = linkedSetOf<String>()
    val queue = ArrayDeque<String>()
    anchoredComposeIds.sorted().forEach(queue::addLast)

    var topologyTruncated = false
    while (queue.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val id = queue.removeFirst()
        if (id in retained) continue
        if (retained.size >= maxRetainedNodes && id !in anchoredComposeIds) {
            topologyTruncated = true
            continue
        }
        retained += id
        adjacency[id].orEmpty().forEach { neighbor ->
            if (neighbor !in retained) queue.addLast(neighbor)
        }
    }

    var addedNodes = 0
    retained.forEach { id ->
        if (id in nodes) return@forEach
        if (nodes.size >= maxGraphNodes) {
            topologyTruncated = true
            return@forEach
        }
        val node = functionsById[id]?.let(::composeSourceNode) ?: return@forEach
        nodes[id] = node
        addedNodes++
    }

    // The source-only topology above exists to discover the connected UI surface (including
    // callers that do not themselves collect state). Once that surface is known, rebuild the
    // visible COMPOSES edges from each retained function body with framework call sites in the
    // middle. That avoids the old flattened Screen -> Child shortcut when the real structure is
    // Screen -> LazyVerticalGrid -> Child.
    val composeEdgesBefore = edges.count { it.kind == EdgeKind.COMPOSES }
    val composeNodesBefore = nodes.count { it.value.kind == NodeKind.COMPOSABLE }
    val retainedSourceNames = retained.asSequence()
        .mapNotNull { id -> functionsById[id]?.name }
        .toSet()
    retained.asSequence()
        .mapNotNull { id -> functionsById[id] }
        .sortedBy { function -> composeSourceId(function) }
        .forEach { function ->
            if (nodes.size >= maxGraphNodes) {
                topologyTruncated = true
                return@forEach
            }
            val body = function.bodyExpression ?: return@forEach
            addComposableChildrenFromBody(
                nodes = nodes,
                edges = edges,
                body = body,
                functionId = composeSourceId(function),
                currentFunction = function,
                depth = 0,
                visited = linkedSetOf(function),
                expandSourceBodies = false,
                allowedSourceIds = retained,
                allowedSourceNames = retainedSourceNames,
                maxGraphNodes = maxGraphNodes,
                maxComposeNodes = Int.MAX_VALUE,
            )
        }
    val addedEdges = (edges.count { it.kind == EdgeKind.COMPOSES } - composeEdgesBefore).coerceAtLeast(0)
    val addedFrameworkNodes = (nodes.count { it.value.kind == NodeKind.COMPOSABLE } - composeNodesBefore).coerceAtLeast(0)
    if (addedEdges >= MAX_ALL_COMPOSE_TOPOLOGY_EDGES) topologyTruncated = true

    diagnostics += "Compose topology: scanned $functionsScanned project Compose source functions, resolved $candidateCallsResolved candidate calls, connected $addedNodes additional source UI nodes plus $addedFrameworkNodes framework call sites with $addedEdges composition edges."
    if (
        composables.size > MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE ||
        candidateCallLimitHits > 0 ||
        globalCandidateCallLimitHit ||
        topologyEdgeDiscoveryTruncated ||
        topologyTruncated
    ) {
        diagnostics += "Compose topology was bounded for IDE responsiveness (functions=$MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE, calls/function=$MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION, total calls=$MAX_ALL_COMPOSE_CANDIDATE_CALLS, discovered edges=$MAX_ALL_COMPOSE_TOPOLOGY_DISCOVERED_EDGES, new nodes=$MAX_ALL_COMPOSE_TOPOLOGY_NEW_NODES, visible edges=$MAX_ALL_COMPOSE_TOPOLOGY_EDGES). Focus a composable for a smaller local detail analysis."
    }
}


