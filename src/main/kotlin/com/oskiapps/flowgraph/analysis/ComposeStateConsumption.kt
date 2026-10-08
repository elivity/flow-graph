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

internal fun AnalysisApiFlowAnalyzer.delegatedComposeStateAccessKeys(
    property: KtProperty,
    sourceFile: String,
    sourceFunction: String,
): Set<String> {
    val propertyName = property.name ?: return emptySet()
    val scope = PsiTreeUtil.getParentOfType(property, KtNamedFunction::class.java, false)
        ?: property.containingKtFile
    return PsiTreeUtil.findChildrenOfType(scope, KtNameReferenceExpression::class.java)
        .asSequence()
        .filter { it.getReferencedName() == propertyName }
        .filter { reference -> runCatching { reference.mainReference.resolve() == property }.getOrDefault(false) }
        .mapNotNull { reference ->
            val line = sourceLocation(reference)?.line?.plus(1) ?: return@mapNotNull null
            val accessKind = delegatedAccessKind(reference)
            "@composestate-access|$sourceFile|$sourceFunction|$propertyName|$line|$accessKind"
        }
        .toSet()
}



internal fun AnalysisApiFlowAnalyzer.delegatedAccessKind(reference: KtNameReferenceExpression): String {
    val parent = reference.parent
    if (parent is org.jetbrains.kotlin.psi.KtBinaryExpression && parent.left == reference) {
        val op = parent.operationReference.text
        if (op in setOf("=", "+=", "-=", "*=", "/=", "%=")) return "write"
    }
    if (parent is org.jetbrains.kotlin.psi.KtUnaryExpression) {
        val op = parent.operationReference.text
        if (op == "++" || op == "--") return "write"
    }
    return "read"
}

/**
 * Extends a Flow path into Compose when the terminal collector is collectAsState*.
 * The collection call remains a first-class node; the owning @Composable and source child
 * composables are appended so the detail graph can show:
 *
 * MutableStateFlow -> StateFlow -> collectAsStateWithLifecycle -> Screen -> ChildComposable
 */



internal fun AnalysisApiFlowAnalyzer.addDirectComposeStateConsumer(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    sourceStateId: String,
    reference: KtNameReferenceExpression,
    expandChildren: Boolean = true,
): Boolean {
    val root = enclosingComposeOwner(reference) ?: return false
    val rootId = root.node.id
    nodes.putIfAbsent(rootId, root.node)
    addEdge(
        edges,
        FlowEdge(
            from = sourceStateId,
            to = rootId,
            kind = EdgeKind.UPDATES_COMPOSE,
            label = "Compose state → recompose",
            source = sourceLocation(reference),
        ),
    )
    if (expandChildren) {
        addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = root.body,
            functionId = rootId,
            currentFunction = root.function,
            depth = 0,
            visited = root.function?.let { linkedSetOf(it) } ?: linkedSetOf(),
        )
    }
    return true
}



internal fun AnalysisApiFlowAnalyzer.addComposeConsumer(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    path: FlowUsagePath,
    expandChildren: Boolean = true,
): Boolean {
    val collector = path.stages.lastOrNull() ?: return false
    if (collector.kind != NodeKind.COLLECTOR) return false
    val collectorName = collector.label.removeSuffix("*")
    if (collectorName != "collectAsState" && collectorName != "collectAsStateWithLifecycle") return false

    // A collectAsState* call can live either in a named @Composable or directly in a
    // Compose host lambda such as ComposeView.setContent { ... } / Activity.setContent { ... }.
    // Treat both as real Compose consumers so ordinary Fragment/Activity Compose roots are not
    // silently omitted from the graph.
    val root = enclosingComposeOwner(collector.anchor) ?: return false
    val rootId = root.node.id
    nodes.putIfAbsent(rootId, root.node)
    addEdge(
        edges,
        FlowEdge(
            from = stageId(collector),
            to = rootId,
            kind = EdgeKind.UPDATES_COMPOSE,
            label = "recompose",
            source = sourceLocation(collector.anchor),
        ),
    )

    if (expandChildren) {
        addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = root.body,
            functionId = rootId,
            currentFunction = root.function,
            depth = 0,
            visited = root.function?.let { linkedSetOf(it) } ?: linkedSetOf(),
        )
    }
    return true
}


