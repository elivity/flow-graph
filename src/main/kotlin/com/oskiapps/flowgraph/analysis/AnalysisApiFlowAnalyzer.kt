@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

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

class AnalysisApiFlowAnalyzer(internal val project: Project) {

    internal data class ProjectDiscovery(
        val flowProperties: List<KtProperty>,
        val composables: List<KtNamedFunction>,
        val inspectedPropertyLimitHit: Boolean,
        val composableLimitHit: Boolean,
        val excludedTestFiles: Int,
    )








    internal data class ForwardPathResult(
        val discoveredProperties: Set<KtProperty>,
        val sideEffectWriteCount: Int,
    )

    /**
     * Adds the normal Flow chain and, when a transform lambda is understood, field provenance.
     * Returns other Flow properties discovered as operator inputs so the outer traversal can scan them.
     */












    internal data class ComposeOwner(
        val node: FlowNode,
        val body: PsiElement,
        val function: KtNamedFunction?,
    )





    internal data class ComposeVisualCall(
        val call: KtCallExpression,
        val node: FlowNode,
        val sourceFunction: KtNamedFunction?,
    )





    internal data class ComposeTopologyEdge(
        val fromId: String,
        val toId: String,
        val source: SourceLocation?,
    )

    /**
     * Adds the project-level Compose call topology to All Flows.
     *
     * The focused graph grows Compose downward from a state consumer. Doing that independently for
     * every All-Flows root leaves sibling screens as visual islands because their common callers may
     * never read Flow state themselves. This pass builds a compact project Compose call graph once,
     * then keeps only components reachable from Compose nodes already anchored to the state graph.
     *
     * Performance rules:
     *  - only production Kotlin source roots are admitted by discoverProjectSymbols();
     *  - project Kotlin files were already scanned once by discoverProjectSymbols();
     *  - calls are prefiltered by project-composable name before K2 resolution;
     *  - candidate calls for one function are resolved in one K2 analysis session;
     *  - only state-connected Compose components are materialized as FlowNodes;
     *  - node/edge/call caps keep large/generated projects bounded;
     *  - multi-source BFS prefers topology closest to existing state-connected composables when a
     *    cap is hit.
     */



















    internal companion object {
        val COMPOSE_DSL_SCOPE_TYPES = setOf(
            "LazyListScope",
            "LazyGridScope",
            "LazyStaggeredGridScope",
        )
        val LAZY_COMPOSE_DSL_CALLS = setOf(
            "item",
            "items",
            "itemsIndexed",
            "stickyHeader",
        )

        const val MAX_DERIVATION_DEPTH = 12
        const val MAX_ANALYZED_PROPERTIES = 250
        const val MAX_GRAPH_NODES = 1_500
        const val MAX_REFERENCES_PER_PROPERTY = 750
        const val MAX_PROJECT_PROPERTIES_TO_INSPECT = 12_000
        const val MAX_PROJECT_COMPOSABLES_TO_DISCOVER = 4_000
        const val MAX_ALL_ANALYZED_PROPERTIES = 1_500
        const val MAX_ALL_GRAPH_NODES = 8_000
        const val MAX_COMPOSE_CALL_DEPTH = 4
        const val MAX_COMPOSE_NODES = 120
        const val MAX_COMPOSE_VISUAL_CALLS_PER_BODY = 700
        const val MAX_ALL_COMPOSE_FUNCTIONS_TO_ANALYZE = 3_000
        const val MAX_COMPOSE_CANDIDATE_CALLS_PER_FUNCTION = 240
        const val MAX_ALL_COMPOSE_CANDIDATE_CALLS = 24_000
        const val MAX_ALL_COMPOSE_TOPOLOGY_DISCOVERED_EDGES = 12_000
        const val MAX_ALL_COMPOSE_TOPOLOGY_NEW_NODES = 1_500
        const val MAX_ALL_COMPOSE_TOPOLOGY_EDGES = 6_000
    }
}
