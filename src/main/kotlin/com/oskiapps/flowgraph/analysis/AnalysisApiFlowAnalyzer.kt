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
        val composeHosts: List<KtCallExpression>,
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


    internal companion object {
        val COMPOSE_DSL_SCOPE_TYPES = setOf(
            "LazyListScope",
            "LazyGridScope",
            "LazyStaggeredGridScope",
            "NavGraphBuilder",
        )
        val LAZY_COMPOSE_DSL_CALLS = setOf(
            "item",
            "items",
            "itemsIndexed",
            "stickyHeader",
        )
        val NAVIGATION_DSL_CALLS = setOf("composable", "dialog", "navigation", "bottomSheet")

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
        const val MAX_ALL_COMPOSE_TOPOLOGY_EDGES = 6_000
    }
}
