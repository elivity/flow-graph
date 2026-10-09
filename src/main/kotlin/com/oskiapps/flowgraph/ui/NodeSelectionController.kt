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

// No right-side inspector: node clicks only affect graph focus/selection.
internal fun FlowGraphPanel.updateComposeRenderPaneVisibility() = Unit

/**
 * Right-side overview navigation. Cluster labels are deliberately derived from the semantic
 * lane headers instead of node ids so the list reads like Screen/ViewModel bookmarks.
 */



internal fun FlowGraphPanel.isRenderableCompose(node: FlowNode): Boolean =
    node.kind == NodeKind.COMPOSABLE && isSourceComposeRuntimeKey(node.runtimeKey)



internal fun FlowGraphPanel.selectNode(nodeId: String) {
    if (selectedNodeId == nodeId && selectionFocusActive) return
    // In Detail view, clicking another visible node is inspection, not navigation.
    // Do not recompute the causal subgraph or move the viewport.
    if (selectionFocusActive && canvas.graph?.node(nodeId) != null) {
        selectedNodeId = nodeId
        canvas.selectedId = nodeId
        updateComposeRenderPaneVisibility()
        updateDetails()
        updateComposePreview()
        canvas.repaint()
        return
    }
    rememberGraphView()
    detailFocusNodeId = nodeId
    selectedNodeId = nodeId
    selectionFocusActive = true
    backToGraph.isEnabled = true
    resetDetailExpansionDepths()
    showAll.isEnabled = true
    updateFieldFocusChoices(nodeId)
    hint.text = "Focused detail • < expands one cause level • > expands one affected level • existing nodes stay anchored"
    renderActive(autoFit = false)
    scheduleDetailFitAfterLayout()
    // Showing a focused detail view changes both graph geometry and the amount of usable
    // viewport space. Fit after Swing has settled those changes instead of fitting against the
    // previous frame's extent.
    val selectedNode = fullGraph?.node(nodeId)
    updateComposePreview()
}



internal fun FlowGraphPanel.requestComposeRenderingFor(node: FlowNode, force: Boolean) {
    // Prefer the exact runtime identity observed from the connected APK. The analyzer's primary
    // @compose2 key uses the declaration line, while Kotlin's LineNumberTable can report the
    // opening body/first statement line. nodeByRuntimeKey() maps both to the same source node;
    // sending the observed key back preserves the exact compiler group registration for render.
    val observedRuntimeKey = runtimeOverlay.lastEventByNode[node.id]
        ?.stateKey
        ?.takeIf(::isSourceComposeRuntimeKey)
    composeRenderService.requestRender(
        node = node,
        force = force,
        preferredRuntimeKey = observedRuntimeKey,
    ) { state ->
        canvas.repaint()
        if (selectedNodeId == node.id) {
            updateDetails()
            updateComposePreview()
        }
        when (state) {
            is ComposeRenderState.Loading -> hint.text = "Rendering ${node.label}: ${state.message}"
            is ComposeRenderState.Ready -> hint.text = "Rendered ${node.label} • shown in the UI render pane"
            is ComposeRenderState.Failed -> hint.text = "Could not render ${node.label}: ${state.message}"
            ComposeRenderState.Idle -> Unit
        }
    }
}


