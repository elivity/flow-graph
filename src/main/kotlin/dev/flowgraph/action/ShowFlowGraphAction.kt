package dev.flowgraph.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.flowgraph.analysis.AnalysisApiFlowAnalyzer
import dev.flowgraph.service.FlowGraphProjectService

class ShowFlowGraphAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Wait until indexing finishes, then run Flow Graph again.", "Flow Graph")
            return
        }

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val offset = editor.caretModel.offset.coerceAtMost((psiFile.textLength - 1).coerceAtLeast(0))
        val element = psiFile.findElementAt(offset) ?: return

        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        ReadAction.nonBlocking<dev.flowgraph.model.FlowGraph> {
            val current = pointer.element
                ?: return@nonBlocking dev.flowgraph.model.FlowGraph(
                    rootLabel = "No Flow",
                    nodes = emptyList(),
                    edges = emptyList(),
                    diagnostics = listOf("Source changed while Flow Graph was analyzing it."),
                )
            AnalysisApiFlowAnalyzer(project).analyzeFrom(current, null)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { graph ->
                if (graph.nodes.isEmpty()) {
                    Messages.showInfoMessage(
                        project,
                        graph.diagnostics.joinToString("\n").ifBlank { "Put the caret on a Flow/StateFlow property or one of its usages." },
                        "Flow Graph",
                    )
                } else {
                    project.getService(FlowGraphProjectService::class.java).publish(graph)
                    ToolWindowManager.getInstance(project).getToolWindow("Flow Graph")?.show()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
