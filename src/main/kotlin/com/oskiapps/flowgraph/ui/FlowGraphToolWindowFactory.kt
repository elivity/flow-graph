package com.oskiapps.flowgraph.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.oskiapps.flowgraph.service.FlowGraphProjectService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class FlowGraphToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val host = FlowGraphSessionHost(project, toolWindow)
        Disposer.register(project, host)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(host, "", false)
        )
        host.startSession()
    }
}

/** A stopped session retains only a small launcher, not the graph, listeners, or live transport. */
class FlowGraphSessionHost(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {
    private val graphs = project.getService(FlowGraphProjectService::class.java)
    private var activePanel: FlowGraphPanel? = null
    private var graphListener: ((com.oskiapps.flowgraph.model.FlowGraph) -> Unit)? = null
    private var disposed = false

    fun startSession() {
        if (disposed || activePanel != null || project.isDisposed) return
        val panel = FlowGraphPanel(project, onQuit = ::quit)
        val listener: (com.oskiapps.flowgraph.model.FlowGraph) -> Unit = panel::setGraph
        activePanel = panel
        graphListener = listener
        removeAll()
        add(panel, BorderLayout.CENTER)
        graphs.addListener(listener)
        revalidate()
        repaint()
    }

    private fun quit() {
        stopSession()
        toolWindow.hide(null)
    }

    fun stopSession() {
        val panel = activePanel ?: return
        graphListener?.let(graphs::removeListener)
        graphListener = null
        panel.shutdown()
        graphs.endSession()
        activePanel = null
        removeAll()
        val launch = JPanel(FlowLayout(FlowLayout.CENTER, 12, 18)).apply {
            add(JLabel("Flow Graph is stopped. Live tracing and graph resources were released."))
            add(JButton("Start Flow Graph").apply { addActionListener { startSession() } })
        }
        add(launch, BorderLayout.NORTH)
        revalidate()
        repaint()
    }

    override fun dispose() {
        if (disposed) return
        stopSession()
        disposed = true
    }
}
