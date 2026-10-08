@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ComposeOwner
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

internal fun AnalysisApiFlowAnalyzer.enclosingComposeOwner(element: PsiElement): ComposeOwner? {
    var current: PsiElement? = element
    while (current != null) {
        when (current) {
            is KtNamedFunction -> {
                if (FlowSemantics.isComposableFunction(current)) {
                    if (FlowSemantics.isPreviewFunction(current)) return null
                    val body = current.bodyExpression ?: current
                    return ComposeOwner(composeNode(current), body, current)
                }
            }

            is KtLambdaExpression -> {
                val hostCall = composeHostCall(current)
                if (hostCall != null) {
                    val body = current.bodyExpression ?: current
                    return ComposeOwner(composeHostNode(current, hostCall), body, null)
                }
            }
        }
        current = current.parent
    }
    return null
}

/** Returns the Compose root call that owns [lambda], currently the standard setContent APIs. */



internal fun AnalysisApiFlowAnalyzer.composeHostCall(lambda: KtLambdaExpression): KtCallExpression? {
    // For both trailing lambdas (`setContent { ... }`) and parenthesized lambda arguments,
    // the first call expression above the lambda is the owning call. Keeping this PSI-shape
    // agnostic avoids depending on whether the parser used KtLambdaArgument or KtValueArgument.
    val call = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, true) ?: return null
    return call.takeIf { FlowSemantics.isComposeSetContentCall(it) }
}



internal fun AnalysisApiFlowAnalyzer.composeHostNode(lambda: KtLambdaExpression, hostCall: KtCallExpression): FlowNode {
    val file = lambda.containingKtFile
    val ownerFunction = PsiTreeUtil.getParentOfType(lambda, KtNamedFunction::class.java, true)
    val ownerLabel = ownerFunction?.name?.let { "$it()" } ?: "source"
    val id = "compose-host:${file.virtualFile.path}:${lambda.textOffset}"
    return FlowNode(
        id = id,
        label = "setContent",
        detail = "Compose content lambda • $ownerLabel • ${sourceDetail(hostCall)} • live recomposition capable",
        kind = NodeKind.COMPOSABLE,
        source = sourceLocation(hostCall),
        runtimeKey = null,
        groupKey = id,
        groupLabel = "setContent",
        groupKind = NodeGroupKind.COMPOSE,
    )
}



internal fun AnalysisApiFlowAnalyzer.composeId(function: KtNamedFunction): String =
    "compose:${function.containingKtFile.virtualFile.path}:${function.textOffset}"



internal fun AnalysisApiFlowAnalyzer.composeDslId(function: KtNamedFunction): String =
    "compose-dsl:${function.containingKtFile.virtualFile.path}:${function.textOffset}"



internal fun AnalysisApiFlowAnalyzer.composeSourceId(function: KtNamedFunction): String =
    if (FlowSemantics.isComposableFunction(function)) composeId(function) else composeDslId(function)



internal fun AnalysisApiFlowAnalyzer.composeSourceNode(function: KtNamedFunction): FlowNode =
    if (FlowSemantics.isComposableFunction(function)) composeNode(function) else composeDslNode(function)



internal fun AnalysisApiFlowAnalyzer.composeDslNode(function: KtNamedFunction): FlowNode {
    val file = function.containingKtFile
    val name = function.name ?: "<compose DSL helper>"
    val receiver = function.receiverTypeReference?.text?.let { "$it." }.orEmpty()
    val id = composeDslId(function)
    return FlowNode(
        id = id,
        label = name,
        detail = "Compose DSL helper • $receiver$name • ${sourceDetail(function)} • static topology",
        kind = NodeKind.COMPOSABLE,
        source = sourceLocation(function),
        runtimeKey = null,
        groupKey = id,
        groupLabel = name,
        groupKind = NodeGroupKind.COMPOSE,
    )
}



internal fun AnalysisApiFlowAnalyzer.composeRuntimeLine(function: KtNamedFunction): Int {
    val file = function.containingKtFile
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    val offset = function.nameIdentifier?.textOffset ?: function.textOffset
    return (document?.getLineNumber(offset) ?: sourceLocation(function)?.line ?: 0) + 1
}



internal fun AnalysisApiFlowAnalyzer.composeRuntimeKey(function: KtNamedFunction): String {
    val file = function.containingKtFile
    val pkg = file.packageFqName.asString()
    return "@compose2|$pkg|${file.name}|${function.name ?: "<anonymous>"}|${composeRuntimeLine(function)}"
}



internal fun AnalysisApiFlowAnalyzer.composeRuntimeAliases(function: KtNamedFunction): Set<String> {
    val file = function.containingKtFile
    val pkg = file.packageFqName.asString()
    val name = function.name ?: "<anonymous>"
    val aliases = linkedSetOf("@compose|$pkg|${file.name}|$name")
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return aliases

    // Kotlin's LineNumberTable normally points at the declaration line, but depending on the
    // generated Compose body it can point at the opening body/first statement instead. Keep a
    // few exact, source-derived aliases so @compose2 remains robust without fuzzy name matching.
    function.bodyExpression?.let { body ->
        aliases += "@compose2|$pkg|${file.name}|$name|${document.getLineNumber(body.textOffset) + 1}"
        (body as? KtBlockExpression)?.statements?.firstOrNull()?.let { first ->
            aliases += "@compose2|$pkg|${file.name}|$name|${document.getLineNumber(first.textOffset) + 1}"
        }
    }
    aliases.remove(composeRuntimeKey(function))
    return aliases
}



internal fun AnalysisApiFlowAnalyzer.composeNode(function: KtNamedFunction): FlowNode {
    val file = function.containingKtFile
    val name = function.name ?: "<anonymous composable>"
    return FlowNode(
        id = composeId(function),
        label = name,
        detail = "@Composable • ${sourceDetail(function)} • live composition inspectable",
        kind = NodeKind.COMPOSABLE,
        source = sourceLocation(function),
        runtimeKey = composeRuntimeKey(function),
        groupKey = "compose:${file.virtualFile.path}:${function.textOffset}",
        groupLabel = name,
        groupKind = NodeGroupKind.COMPOSE,
        runtimeAliases = composeRuntimeAliases(function),
    )
}


