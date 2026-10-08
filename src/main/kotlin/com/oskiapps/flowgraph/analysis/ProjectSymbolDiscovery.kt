@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ProjectDiscovery
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_ANALYZED_PROPERTIES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ALL_GRAPH_NODES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_PROJECT_COMPOSABLES_TO_DISCOVER
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_PROJECT_PROPERTIES_TO_INSPECT
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

internal fun AnalysisApiFlowAnalyzer.analyzeFrom(element: PsiElement, documentForRoot: Document? = null): FlowGraph =
    ReadAction.compute<FlowGraph, RuntimeException> {
        val root = resolveProperty(element)
            ?: return@compute FlowGraph(
                rootLabel = "No Flow",
                nodes = emptyList(),
                edges = emptyList(),
                diagnostics = listOf("Caret does not resolve to a Kotlin property."),
            )

        if (!FlowSemantics.isFlowProperty(root)) {
            return@compute FlowGraph(
                rootLabel = root.name ?: "property",
                nodes = emptyList(),
                edges = emptyList(),
                diagnostics = listOf("${root.name} does not resolve to Flow/StateFlow/SharedFlow/Compose State."),
            )
        }

        buildGraph(root)
    }

/**
 * Builds one project-wide graph containing every Kotlin Flow/StateFlow/SharedFlow/Compose State property
 * under project content roots, including local properties declared inside functions/composables.
 * Disconnected parts are intentionally kept in the same FlowGraph; the canvas lays those
 * connected components out as separate visual clusters.
 */



internal fun AnalysisApiFlowAnalyzer.analyzeAllProjectFlows(): FlowGraph = ReadAction.compute<FlowGraph, RuntimeException> {
    // All Flows needs both the state graph and the project Compose topology. Discover both in
    // one project-file discovery pass so large projects are not enumerated twice just to connect UI islands.
    val discovery = discoverProjectSymbols()
    val roots = discovery.flowProperties
    if (roots.isEmpty()) {
        return@compute FlowGraph(
            rootLabel = "All project flows",
            nodes = emptyList(),
            edges = emptyList(),
            diagnostics = listOf("No Kotlin Flow/StateFlow/SharedFlow/Compose State properties were found in project sources."),
        )
    }

    val graph = buildGraph(
        roots = roots,
        rootLabel = "All project flows",
        maxAnalyzedProperties = MAX_ALL_ANALYZED_PROPERTIES,
        maxGraphNodes = MAX_ALL_GRAPH_NODES,
        projectComposables = discovery.composables,
        productionSourcesOnly = true,
        projectDiscoveryTruncated = discovery.inspectedPropertyLimitHit || discovery.composableLimitHit,
        excludedTestFiles = discovery.excludedTestFiles,
    )
    // Warm the expensive state-to-state projection while still on the background analysis job.
    // The default All flows view can then render immediately on the EDT instead of doing an
    // O(states × paths) traversal during Swing painting/layout.
    graph.stateTransitions()
    graph
}

/**
 * Scans production Kotlin source roots once and collects the two symbol sets needed by All Flows:
 * state properties plus project @Composable and Compose lazy-DSL source functions. Test source roots are deliberately ignored;
 * Compose tests commonly contain many `setContent { ... }` calls and should not become part of the
 * application's architecture graph.
 *
 * Flow classification is the expensive part (K2), so it has its own hard inspection cap.
 * Compose-source discovery is annotation/PSI based and much cheaper, but is capped separately to
 * keep pathological/generated source trees bounded. `setContent` hosts are not materialized in
 * All Flows; named @Composable call relationships provide the useful production topology without
 * repeating framework host nodes throughout the graph.
 */



internal fun AnalysisApiFlowAnalyzer.discoverProjectSymbols(): ProjectDiscovery {
    val psiManager = PsiManager.getInstance(project)
    val projectFileIndex = ProjectFileIndex.getInstance(project)
    val files = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
    var excludedTestFiles = 0
    projectFileIndex.iterateContent { file ->
        ProgressManager.checkCanceled()
        if (file.isDirectory || file.extension != "kt") return@iterateContent true
        if (!projectFileIndex.isInSourceContent(file)) return@iterateContent true
        if (projectFileIndex.isInTestSourceContent(file)) {
            excludedTestFiles++
            return@iterateContent true
        }
        files += file
        true
    }

    val roots = mutableListOf<KtProperty>()
    val composables = mutableListOf<KtNamedFunction>()
    var propertiesInspected = 0
    var propertyLimitHit = false
    var composableLimitHit = false

    for (file in files.sortedBy { it.path }) {
        ProgressManager.checkCanceled()
        val ktFile = psiManager.findFile(file) as? KtFile ?: continue

        if (!composableLimitHit) {
            for (function in PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)) {
                ProgressManager.checkCanceled()
                val isComposable = FlowSemantics.isComposableFunction(function)
                val isComposeDslHelper = isProjectComposeDslHelper(function)
                if (!isComposable && !isComposeDslHelper) continue
                if (FlowSemantics.isPreviewFunction(function)) continue
                if (composables.size >= MAX_PROJECT_COMPOSABLES_TO_DISCOVER) {
                    composableLimitHit = true
                    break
                }
                composables += function
            }

        }

        if (!propertyLimitHit) {
            for (property in PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java)) {
                ProgressManager.checkCanceled()
                // Local StateFlow/SharedFlow properties matter for Compose. A flow created inside
                // a composable/helper can be the direct source of collectAsState*, and skipping it
                // prevents the owning/sub-composable branch from ever entering the project graph.
                if (propertiesInspected++ >= MAX_PROJECT_PROPERTIES_TO_INSPECT) {
                    propertyLimitHit = true
                    break
                }
                if (FlowSemantics.isFlowProperty(property)) roots += property
            }
        }

        if (propertyLimitHit && composableLimitHit) break
    }

    return ProjectDiscovery(
        flowProperties = roots.distinctBy(::propertyId),
        composables = composables.distinctBy(::composeSourceId),
        inspectedPropertyLimitHit = propertyLimitHit,
        composableLimitHit = composableLimitHit,
        excludedTestFiles = excludedTestFiles,
    )
}



internal fun AnalysisApiFlowAnalyzer.resolveProperty(element: PsiElement): KtProperty? {
    PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)?.let { property ->
        if (property.nameIdentifier?.textRange?.contains(element.textRange) == true) return property
    }

    val ref = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
        ?: element as? KtNameReferenceExpression
        ?: return null

    return analyze(ref) {
        (ref.mainReference.resolveToSymbol() as? KaPropertySymbol)?.psi as? KtProperty
    }
}



internal fun AnalysisApiFlowAnalyzer.propertyReferenceExpressions(
    property: KtProperty,
    productionSourcesOnly: Boolean = false,
): List<KtNameReferenceExpression> {
    // Local/delegated Compose state is not a globally indexed symbol. In practice a project-wide
    // ReferencesSearch can return zero references for `var state by remember { mutableStateOf(...) }`,
    // leaving the state node orphaned even though the composable reads/writes it. Resolve local
    // name references directly inside the lexical owner instead.
    if (property.isLocal) {
        val owner: PsiElement = PsiTreeUtil.getParentOfType(property, KtNamedFunction::class.java, false)
            ?: PsiTreeUtil.getParentOfType(property, KtLambdaExpression::class.java, false)
            ?: property.containingFile
        val expectedName = property.name
        return PsiTreeUtil.collectElementsOfType(owner, KtNameReferenceExpression::class.java)
            .asSequence()
            .filter { expectedName == null || it.getReferencedName() == expectedName }
            .filter { !productionSourcesOnly || isProductionSource(it) }
            .filter { FlowSemantics.resolvesTo(it, property) }
            .toList()
    }
    return ReferencesSearch.search(property, GlobalSearchScope.projectScope(project))
        .findAll()
        .mapNotNull { it.element as? KtNameReferenceExpression }
        .filter { !productionSourcesOnly || isProductionSource(it) }
        .filter { FlowSemantics.resolvesTo(it, property) }
}



internal fun AnalysisApiFlowAnalyzer.isProductionSource(element: PsiElement): Boolean {
    val file = element.containingFile?.virtualFile ?: return false
    val index = ProjectFileIndex.getInstance(project)
    return index.isInSourceContent(file) && !index.isInTestSourceContent(file)
}


