@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ComposeVisualCall
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.COMPOSE_DSL_SCOPE_TYPES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.LAZY_COMPOSE_DSL_CALLS
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.NAVIGATION_DSL_CALLS
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_CANDIDATE_CALLS
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_COMPOSE_TOPOLOGY_EDGES
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
    maxGraphEdges: Int = Int.MAX_VALUE,
    maxCandidateCalls: Int = MAX_COMPOSE_VISUAL_CALLS_PER_BODY,
): Int {
    if (maxCandidateCalls <= 0 ||
        depth >= MAX_COMPOSE_CALL_DEPTH ||
        nodes.size >= maxGraphNodes ||
        (maxComposeNodes != Int.MAX_VALUE &&
            nodes.count { it.value.kind == NodeKind.COMPOSABLE } >= maxComposeNodes)
    ) return 0

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
                name in LAZY_COMPOSE_DSL_CALLS ||
                name in NAVIGATION_DSL_CALLS
        }
        .take(minOf(maxCandidateCalls, MAX_COMPOSE_VISUAL_CALLS_PER_BODY))
        .toList()
    if (candidates.isEmpty()) return 0

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

            isNavigationComposeCall(call, info) -> ComposeVisualCall(
                call = call,
                node = composeNavigationCallNode(call),
                sourceFunction = null,
            )

            isFrameworkVisualComposeCall(call, info) -> ComposeVisualCall(
                call = call,
                node = composeFrameworkCallNode(call, info?.callableId),
                sourceFunction = null,
            )

            else -> null
        }
    }
    if (visualCalls.isEmpty()) return candidates.size

    val visualByCall = visualCalls.associateBy { it.call }
    visualCalls.forEach { visual ->
        if (edges.size >= maxGraphEdges) return@forEach
        if (
            visual.node.id !in nodes &&
            (nodes.size >= maxGraphNodes ||
                (maxComposeNodes != Int.MAX_VALUE &&
                    nodes.count { it.value.kind == NodeKind.COMPOSABLE } >= maxComposeNodes))
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
                label = "calls (static, may be conditional)",
                source = sourceLocation(visual.call),
            ),
        )
    }

    if (!expandSourceBodies) return candidates.size
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
    return candidates.size
}



internal fun AnalysisApiFlowAnalyzer.isFrameworkVisualComposeCall(call: KtCallExpression, info: ResolvedFunctionCall?): Boolean {
    val name = call.calleeExpression?.text ?: return false
    val callableId = info?.callableId.orEmpty()

    // Real @Composable framework calls (Column, Row, Text, Button, LazyVerticalGrid, ...).
    if (name.firstOrNull()?.isUpperCase() == true) {
        // Namespace + PascalCase is not enough: value factories/constructors such as Offset(...),
        // Color(...), DpSize(...), etc. also live under androidx.compose and previously appeared as
        // fake composition nodes. Only a callable that actually carries @Composable belongs here.
        return info?.isComposable == true && (
            callableId.startsWith("androidx.compose.") ||
                callableId.startsWith("androidx.navigation.compose.")
        )
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



/** Navigation declarations are not necessarily @Composable functions themselves. */
internal fun AnalysisApiFlowAnalyzer.isNavigationComposeCall(
    call: KtCallExpression,
    info: ResolvedFunctionCall?,
): Boolean {
    val name = call.calleeExpression?.text ?: return false
    return name in NAVIGATION_DSL_CALLS && (
        info?.callableId?.startsWith("androidx.navigation.") == true ||
            info?.callableId?.startsWith("androidx.compose.material.navigation.") == true
    )
}

internal fun AnalysisApiFlowAnalyzer.composeNavigationCallNode(call: KtCallExpression): FlowNode {
    val name = call.calleeExpression?.text ?: "destination"
    val routeArgument = call.valueArguments.firstOrNull { arg ->
        arg.getArgumentName()?.asName?.asString() == "route"
    } ?: call.valueArguments.firstOrNull()
    val route = call.typeArgumentList?.arguments?.firstOrNull()?.text
        ?: routeArgument?.getArgumentExpression()?.text?.take(70)
    val routeLabel = route?.trim('"')?.takeIf { it.isNotBlank() }
    val label = if (routeLabel == null) name else "$name: $routeLabel"
    val id = "compose-nav:${call.containingKtFile.virtualFile.path}:${call.textOffset}"
    return FlowNode(
        id = id,
        label = label,
        detail = "Navigation destination declaration • ${sourceDetail(call)} • static, may be conditional",
        kind = NodeKind.COMPOSABLE,
        source = sourceLocation(call),
        groupKey = id,
        groupLabel = label,
        groupKind = NodeGroupKind.COMPOSE,
    )
}

/**
 * Independent production Compose scan. There is deliberately no StateFlow/Compose-State anchor:
 * even a project with zero Flow properties exposes its entire declared @Composable UI surface.
 * COMPOSES is a static (possibly conditional) call edge, never proof that a destination actually
 * appeared on screen. Flow-to-Compose edges come only from the separate state-consumer analysis.
 */
internal fun AnalysisApiFlowAnalyzer.analyzeProjectComposables(
    composables: List<KtNamedFunction>,
    composeHosts: List<KtCallExpression>,
    maxGraphNodes: Int,
): FlowGraph {
    val nodes = linkedMapOf<String, FlowNode>()
    val edges = linkedSetOf<FlowEdge>()
    val diagnostics = mutableListOf<String>()
    val sorted = composables.sortedBy(::composeSourceId)
    val sourceFunctions = sorted.take(minOf(MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE, maxGraphNodes))

    // Materialize *every* eligible source declaration before processing call sites. This makes
    // disconnected screens, leaf composables and isolated navigation destinations visible.
    sourceFunctions.forEach { function ->
        ProgressManager.checkCanceled()
        val node = composeSourceNode(function)
        nodes[node.id] = node
    }
    val admittedIds = nodes.keys.toSet()
    val admittedNames = sourceFunctions.mapNotNullTo(hashSetOf()) { it.name }
    var analyzedBodies = 0
    var candidateCallsResolved = 0
    var truncated = sourceFunctions.size < composables.size

    // Source calls, framework UI nesting, and navigation destination declarations are analyzed
    // from actual PSI call sites. Only K2-resolved project targets get project-call edges.
    for (function in sourceFunctions) {
        ProgressManager.checkCanceled()
        if (edges.size >= MAX_ALL_COMPOSE_TOPOLOGY_EDGES ||
            candidateCallsResolved >= MAX_ALL_COMPOSE_CANDIDATE_CALLS
        ) {
            truncated = true
            break
        }
        val body = function.bodyExpression ?: continue
        analyzedBodies++
        candidateCallsResolved += addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = body,
            functionId = composeSourceId(function),
            currentFunction = function,
            depth = 0,
            visited = linkedSetOf(function),
            expandSourceBodies = false,
            allowedSourceIds = admittedIds,
            allowedSourceNames = admittedNames,
            maxGraphNodes = maxGraphNodes,
            maxComposeNodes = Int.MAX_VALUE,
            maxGraphEdges = MAX_ALL_COMPOSE_TOPOLOGY_EDGES,
            maxCandidateCalls = minOf(
                MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION,
                MAX_ALL_COMPOSE_CANDIDATE_CALLS - candidateCallsResolved,
            ),
        )
    }

    // Explicit Activity/ComposeView setContent calls anchor the otherwise source-only UI graph.
    // A host is a declarative entry point, not evidence of an observed composition.
    for (call in composeHosts.sortedBy { it.containingKtFile.virtualFile.path + ":" + it.textOffset }) {
        ProgressManager.checkCanceled()
        if (nodes.size >= maxGraphNodes || edges.size >= MAX_ALL_COMPOSE_TOPOLOGY_EDGES ||
            candidateCallsResolved >= MAX_ALL_COMPOSE_CANDIDATE_CALLS
        ) {
            truncated = true
            break
        }
        val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: call.valueArguments.firstNotNullOfOrNull { it.getArgumentExpression() as? KtLambdaExpression }
            ?: continue
        val host = composeHostNode(lambda, call)
        nodes.putIfAbsent(host.id, host)
        candidateCallsResolved += addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = lambda.bodyExpression ?: lambda,
            functionId = host.id,
            currentFunction = null,
            depth = 0,
            visited = linkedSetOf(),
            expandSourceBodies = false,
            allowedSourceIds = admittedIds,
            allowedSourceNames = admittedNames,
            maxGraphNodes = maxGraphNodes,
            maxComposeNodes = Int.MAX_VALUE,
            maxGraphEdges = MAX_ALL_COMPOSE_TOPOLOGY_EDGES,
            maxCandidateCalls = minOf(
                MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION,
                MAX_ALL_COMPOSE_CANDIDATE_CALLS - candidateCallsResolved,
            ),
        )
    }

    if (nodes.size >= maxGraphNodes || edges.size >= MAX_ALL_COMPOSE_TOPOLOGY_EDGES ||
        candidateCallsResolved >= MAX_ALL_COMPOSE_CANDIDATE_CALLS
    ) {
        truncated = true
    }
    val sourceCount = nodes.values.count { it.id in admittedIds }
    val navigationCount = nodes.keys.count { it.startsWith("compose-nav:") }
    diagnostics += "Independent Compose scan: $sourceCount source declarations, $analyzedBodies bodies, " +
        "$navigationCount navigation destinations and ${composeHosts.size} setContent hosts; " +
        "${edges.size} static call edges ($candidateCallsResolved resolved candidates). " +
        "Unconnected UI is retained."
    if (truncated) diagnostics += "Compose scan reached its IDE safety budget; some call sites or " +
        "source functions could be omitted. Select a Composable for focused inspection."
    return FlowGraph(
        rootLabel = "Project composables",
        nodes = nodes.values.toList(),
        edges = edges.toList(),
        diagnostics = diagnostics,
    )
}
