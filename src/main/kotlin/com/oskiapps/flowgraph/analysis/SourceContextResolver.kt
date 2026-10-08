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

internal fun AnalysisApiFlowAnalyzer.looksLikeViewModel(owner: KtClassOrObject): Boolean {
    val name = owner.name.orEmpty()
    if (name.endsWith("ViewModel")) return true
    return owner.superTypeListEntries.any { entry ->
        val text = entry.text
        text == "ViewModel" || text.startsWith("ViewModel(") || text.endsWith(".ViewModel") || text.contains("ViewModel(")
    }
}



internal fun AnalysisApiFlowAnalyzer.writerOwnerLabel(writer: WriterSemantics): String {
    val function = PsiTreeUtil.getParentOfType(writer.anchor, KtNamedFunction::class.java, false)?.name
    return function?.let { "$it()" } ?: writer.label
}



internal fun AnalysisApiFlowAnalyzer.ownerContext(element: PsiElement): String {
    val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)?.name
    if (function != null) return "$function()"
    val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)?.name
    if (property != null) return property
    return "top-level"
}



internal fun AnalysisApiFlowAnalyzer.shortCallId(callId: String): String =
    callId.removePrefix("kotlinx.coroutines.flow.")
        .removePrefix("androidx.compose.runtime.")
        .removePrefix("androidx.lifecycle.compose.")



internal fun AnalysisApiFlowAnalyzer.sourceDetail(element: PsiElement): String {
    val source = sourceLocation(element) ?: return "<unknown>"
    return "${source.file.name}:${source.line + 1}"
}



internal fun AnalysisApiFlowAnalyzer.sourceLocation(element: PsiElement): SourceLocation? {
    val file = element.containingFile?.virtualFile ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        ?: return SourceLocation(file, element.textOffset, 0)
    return SourceLocation(file, element.textOffset, document.getLineNumber(element.textOffset))
}


