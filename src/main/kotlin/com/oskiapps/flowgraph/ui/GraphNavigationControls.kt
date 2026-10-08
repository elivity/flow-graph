package com.oskiapps.flowgraph.ui


import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer
import com.oskiapps.flowgraph.model.*
import com.oskiapps.flowgraph.service.FlowGraphProjectService
import com.oskiapps.flowgraph.service.ComposeRenderService
import com.oskiapps.flowgraph.service.ComposeRenderSnapshot
import com.oskiapps.flowgraph.service.ComposeRenderState
import com.oskiapps.flowgraph.service.LiveTraceService
import com.oskiapps.flowgraph.service.LiveTraceStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

internal fun FlowGraphPanel.updateClusterNavigator(entries: List<ClusterNavigationEntry>) {
    if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater { updateClusterNavigator(entries) }
        return
    }

    val validIndexes = entries.mapTo(linkedSetOf()) { it.index }
    if (selectedClusterNavigationIndex?.let { it in validIndexes } != true) {
        selectedClusterNavigationIndex = null
    }

    clusterNavigatorButtons.removeAll()
    clusterNavigatorButtonByIndex.clear()
    if (entries.isEmpty()) {
        clusterNavigatorScroll.isVisible = false
        clusterNavigatorButtons.revalidate()
        clusterNavigatorButtons.repaint()
        clusterNavigatorScroll.parent?.revalidate()
        return
    }

    val group = ButtonGroup()
    entries.forEach { entry ->
        val displayName = clusterNavigationDisplayName(entry.label)
        val button = JToggleButton(displayName).apply {
            horizontalAlignment = SwingConstants.LEFT
            isFocusable = false
            isSelected = entry.index == selectedClusterNavigationIndex
            toolTipText = "Center and fit: ${entry.label}"
            margin = Insets(5, 7, 5, 7)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                selectedClusterNavigationIndex = entry.index
                cancelPendingAutoFit()
                canvas.focusCluster(entry.index)
            }
        }
        group.add(button)
        clusterNavigatorButtonByIndex[entry.index] = button
        styleClusterNavigationButton(button, entry.index == selectedClusterNavigationIndex)
        clusterNavigatorButtons.add(button)
        clusterNavigatorButtons.add(Box.createVerticalStrut(3))
    }

    clusterNavigatorScroll.isVisible = true
    clusterNavigatorButtons.revalidate()
    clusterNavigatorButtons.repaint()
    clusterNavigatorScroll.parent?.revalidate()
    clusterNavigatorScroll.parent?.repaint()
    selectedClusterNavigationIndex?.let(::scrollClusterNavigationButtonIntoView)
}

/**
 * Keep the bookmark rail synchronized with the part of the overview that is actually under
 * the viewport. The selected bookmark is also scrolled into view, so the rail follows long
 * vertical/horizontal overview navigation instead of leaving its highlight off-screen.
 */



internal fun FlowGraphPanel.updateActiveClusterNavigation(index: Int?) {
    if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater { updateActiveClusterNavigation(index) }
        return
    }
    selectedClusterNavigationIndex = index
    clusterNavigatorButtonByIndex.forEach { (buttonIndex, button) ->
        val active = buttonIndex == index
        button.isSelected = active
        styleClusterNavigationButton(button, active)
    }
    index?.let(::scrollClusterNavigationButtonIntoView)
}



internal fun FlowGraphPanel.styleClusterNavigationButton(button: JToggleButton, active: Boolean) {
    button.font = button.font.deriveFont(if (active) Font.BOLD else Font.PLAIN)
    button.isOpaque = active
    if (active) {
        button.background = UIManager.getColor("List.selectionBackground")
            ?: UIManager.getColor("Button.select")
            ?: button.background
        button.foreground = UIManager.getColor("List.selectionForeground")
            ?: UIManager.getColor("Button.foreground")
            ?: button.foreground
    } else {
        button.background = UIManager.getColor("Panel.background") ?: button.background
        button.foreground = UIManager.getColor("Button.foreground") ?: button.foreground
    }
}



internal fun FlowGraphPanel.scrollClusterNavigationButtonIntoView(index: Int) {
    val activeButton = clusterNavigatorButtonByIndex[index] ?: return
    SwingUtilities.invokeLater {
        if (!activeButton.isShowing) return@invokeLater
        activeButton.scrollRectToVisible(Rectangle(0, 0, activeButton.width, activeButton.height))
    }
}



internal fun FlowGraphPanel.clusterNavigationDisplayName(label: String): String {
    val parts = label.split(" • ")
    if (parts.size <= 1) return label
    return when {
        parts[0] == "Compose lane" -> parts.getOrNull(1) ?: label
        parts[0] == "Flow lane" -> parts.getOrNull(1) ?: label
        parts[0] == "ViewModel" -> parts.getOrNull(1) ?: label
        parts[0] == "ViewModels" -> parts.getOrNull(1) ?: label
        parts[0] == "Compose" -> parts.getOrNull(1) ?: label
        parts[0].startsWith("Boundary ") -> buildString {
            append(parts[0])
            parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
        parts[0].startsWith("Cluster ") -> {
            val preview = parts.drop(2).firstOrNull { it.isNotBlank() }
            if (preview != null) "${parts[0]} — $preview" else parts[0]
        }
        else -> parts.take(2).joinToString(" — ")
    }
}



internal fun FlowGraphPanel.focusDownstream() {
    val graph = fullGraph ?: return
    val id = selectedNodeId ?: return
    rememberGraphView()
    activeGraph = graph.focusDownstream(id, selectedDepth(), includePossible.isSelected)
    selectionFocusActive = false
    showAll.isEnabled = activeGraph?.nodes?.size != graph.nodes.size
    renderActive(autoFit = true)
}



internal fun FlowGraphPanel.focusUpstream() {
    val graph = fullGraph ?: return
    val id = selectedNodeId ?: return
    rememberGraphView()
    activeGraph = graph.focusUpstream(id, selectedDepth(), includePossible.isSelected)
    selectionFocusActive = false
    showAll.isEnabled = activeGraph?.nodes?.size != graph.nodes.size
    renderActive(autoFit = true)
}


