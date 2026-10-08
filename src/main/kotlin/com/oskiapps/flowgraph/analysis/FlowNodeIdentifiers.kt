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

internal fun AnalysisApiFlowAnalyzer.propertyId(property: KtProperty): String =
    "state:${property.containingKtFile.virtualFile.path}:${property.textOffset}"



internal fun AnalysisApiFlowAnalyzer.fieldId(property: KtProperty, fieldName: String): String =
    "field:${property.containingKtFile.virtualFile.path}:${property.textOffset}:$fieldName"



internal fun AnalysisApiFlowAnalyzer.stageId(stage: FlowStage): String =
    "call:${stage.anchor.containingKtFile.virtualFile.path}:${stage.anchor.textOffset}:${stage.callId}"



internal fun AnalysisApiFlowAnalyzer.writerId(writer: WriterSemantics): String {
    val file = writer.anchor.containingFile?.virtualFile?.path ?: "<unknown>"
    return "writer:$file:${writer.anchor.textOffset}:${writer.callId}"
}



internal fun AnalysisApiFlowAnalyzer.readId(sourceStateId: String, anchor: PsiElement): String {
    val file = anchor.containingFile?.virtualFile?.path ?: "<unknown>"
    return "read:$sourceStateId:$file:${anchor.textOffset}"
}



internal fun AnalysisApiFlowAnalyzer.behaviorId(anchor: PsiElement): String {
    val file = anchor.containingFile?.virtualFile?.path ?: "<unknown>"
    return "behavior:$file:${anchor.textOffset}"
}


