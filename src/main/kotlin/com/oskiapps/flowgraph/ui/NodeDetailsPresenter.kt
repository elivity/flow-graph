package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_COMPOSE_INSTANCE_DETAIL_ITEMS
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_DETAIL_ITEMS

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

internal fun FlowGraphPanel.updateFieldFocusChoices(nodeId: String) {
    val graph = fullGraph ?: return
    val node = graph.node(nodeId)
    val previous = fieldFocus.selectedItem?.toString()
    fieldFocus.removeAllItems()
    fieldFocus.addItem("All fields")
    if (node?.kind == NodeKind.STATE) {
        val discovered = linkedSetOf<String>()
        graph.fieldsForState(nodeId).mapTo(discovered) { it.label }
        graph.transitionsFrom(nodeId).flatMapTo(discovered) { it.affectedFields }
        graph.transitionsTo(nodeId).flatMapTo(discovered) { it.affectedFields }
        graph.fieldDependencies
            .filter { it.outputStateId == nodeId }
            .mapTo(discovered) { it.outputField }
        discovered.remove("\$value")
        discovered.filter { it.isNotBlank() }.sorted().forEach(fieldFocus::addItem)
    }
    fieldFocus.isEnabled = fieldFocus.itemCount > 1
    if (previous != null && (0 until fieldFocus.itemCount).any { fieldFocus.getItemAt(it) == previous }) {
        fieldFocus.selectedItem = previous
    } else {
        fieldFocus.selectedIndex = 0
    }
}



internal fun FlowGraphPanel.publishGraph(graph: FlowGraph, focusedLabel: String? = null) {
    val extra = if (graph.diagnostics.isEmpty()) "" else " • ${graph.diagnostics.size} note(s)"
    val mode = if (showOperators.isSelected) "full chain" else "state changes"
    title.text = if (focusedLabel == null) {
        "${fullGraph?.rootLabel ?: graph.rootLabel} • $mode • ${graph.nodes.size} nodes • ${graph.edges.size} connections$extra"
    } else {
        "$focusedLabel • $mode • ${graph.nodes.size} nodes • ${graph.edges.size} connections"
    }
    val previous = canvas.graph
    if (previous?.nodes != graph.nodes || previous.edges != graph.edges ||
        previous.rootLabel != graph.rootLabel
    ) {
        cancelPendingAutoFit()
        canvas.graph = graph
        canvas.relayout()
    } else {
        canvas.repaint()
    }
}



internal fun FlowGraphPanel.updateDetails() {
    val graph = fullGraph ?: return
    val id = selectedNodeId
    if (id == null) {
        affected.isEnabled = false
        causes.isEnabled = false
        return
    }
    val impact = graph.impactFor(id) ?: return
    val node = impact.node
    val fieldCauses = graph.dependenciesForField(id)
    val fieldEffects = graph.fieldEffectsFrom(id)
        .distinctBy { listOf(it.sourceNodeId, it.sourceField, it.viaOperatorId, it.outputFieldId) }
    val stateChangesOut = if (node.kind == NodeKind.STATE) graph.transitionsFrom(id) else emptyList()
    val stateChangesIn = if (node.kind == NodeKind.STATE) graph.transitionsTo(id) else emptyList()
    val reads = if (node.kind == NodeKind.STATE) graph.readsFrom(id) else emptyList()
    val shownRuntime = displayedRuntimeOverlay()
    val isComposeState = node.kind == NodeKind.STATE && node.detail.contains("Compose State", ignoreCase = true)

    affected.isEnabled = impact.outgoing.isNotEmpty() ||
        impact.downstreamStates.isNotEmpty() || impact.downstreamCollectors.isNotEmpty() ||
        impact.downstreamBehaviors.isNotEmpty() || impact.downstreamReads.isNotEmpty() ||
        fieldEffects.isNotEmpty() || stateChangesOut.isNotEmpty()
    causes.isEnabled = impact.incoming.isNotEmpty() ||
        impact.upstreamWriters.isNotEmpty() || impact.upstreamStates.isNotEmpty() ||
        fieldCauses.isNotEmpty() || stateChangesIn.isNotEmpty()

    details.text = buildString {
        appendLine(node.label)
        appendLine(node.kind.name.lowercase().replace('_', ' '))
        appendLine(node.detail)
        node.source?.let { appendLine("${it.file.path}:${it.line + 1}") }
        node.runtimeKey?.let { appendLine("runtime key: $it") }
        shownRuntime.nodeCounts[node.id]?.let { count ->
            appendLine(if (node.kind == NodeKind.COMPOSABLE) "runtime compose body executions: $count" else "runtime emissions: $count")
        }
        shownRuntime.changeCounts[node.id]?.let { count -> appendLine("runtime state changes: $count") }
        shownRuntime.deliveryCounts[node.id]?.let { count -> appendLine("runtime deliveries: $count") }
        shownRuntime.collectCounts[node.id]?.let { count -> appendLine("runtime collections: $count") }
        shownRuntime.emitRequestCounts[node.id]?.let { count -> appendLine("suspending emit requests: $count") }
        shownRuntime.readCounts[node.id]?.let { count -> appendLine("runtime reads: $count") }

        val composeInstances = shownRuntime.composeInstanceEventsByNode[node.id].orEmpty()
        if (isComposeState && composeInstances.isNotEmpty()) {
            appendLine("runtime instances observed: ${composeInstances.size}")
            if (composeInstances.size == 1) {
                val lastInstanceEvent = composeInstances.values.single()
                lastInstanceEvent.valueSummary?.let { appendLine("last value: $it") }
                appendLine("last runtime event key: ${lastInstanceEvent.stateKey}")
                lastInstanceEvent.site?.takeIf { it.isNotBlank() }?.let {
                    appendLine("last runtime event site: $it")
                }
            } else {
                appendLine("instance values:")
                composeInstances.entries
                    .sortedBy { it.key }
                    .take(MAX_COMPOSE_INSTANCE_DETAIL_ITEMS)
                    .forEach { (instanceId, event) ->
                        appendLine("  #$instanceId = ${event.valueSummary ?: "<unknown>"}")
                    }
                if (composeInstances.size > MAX_COMPOSE_INSTANCE_DETAIL_ITEMS) {
                    appendLine("  … +${composeInstances.size - MAX_COMPOSE_INSTANCE_DETAIL_ITEMS} more instances")
                }
            }
        } else {
            shownRuntime.lastEventByNode[node.id]?.let { lastEvent ->
                lastEvent.valueSummary?.let { appendLine("last value: $it") }
                if (isComposeState) {
                    appendLine("last runtime event key: ${lastEvent.stateKey}")
                    appendLine("last runtime event kind: ${lastEvent.kind}")
                    lastEvent.site?.takeIf { it.isNotBlank() }?.let {
                        appendLine("last runtime event site: $it")
                    }
                }
            }
        }
        if (node.kind == NodeKind.COMPOSABLE) {
            appendLine()
            appendLine("COMPOSE RENDER")
            when (val render = composeRenderService.state(node.id)) {
                ComposeRenderState.Idle -> {
                    if (!isRenderableCompose(node)) {
                        if (node.id.startsWith("compose-call:")) {
                            appendLine("  This is a static Compose framework call site used to show composition structure.")
                            appendLine("  Select a named source @Composable to inspect live pixels.")
                        } else {
                            appendLine("  This is a Compose host node without a source @Composable runtime key.")
                            appendLine("  Select a named @Composable child to inspect its live pixels.")
                        }
                    } else {
                        appendLine("  UI render inspects the running Compose slot table and LayoutInfo bounds automatically when selected.")
                        appendLine("  Pixels are drawn from the live AndroidComposeView; no generated @Preview or adb screenshot is used.")
                    }
                }
                is ComposeRenderState.Loading -> appendLine("  ${render.message}")
                is ComposeRenderState.Ready -> {
                    appendLine("  ${render.snapshot.description}")
                    appendLine("  ${render.snapshot.image.width}×${render.snapshot.image.height} • shown in the UI Preview tab")
                }
                is ComposeRenderState.Failed -> appendLine("  ${render.message}")
            }
        }

        if (node.kind == NodeKind.STATE) {
            appendLine()
            appendLine("FLOW / STATE PROPAGATION OUT (${stateChangesOut.size})")
            appendTransitions(graph, stateChangesOut, outgoing = true)

            appendLine()
            appendLine("FLOW / STATE PROPAGATION IN (${stateChangesIn.size})")
            appendTransitions(graph, stateChangesIn, outgoing = false)

            appendLine()
            appendLine("READ-ONLY OBSERVERS (${reads.size})")
            appendNodeList(reads)
        }

        if (fieldCauses.isNotEmpty()) {
            appendLine()
            appendLine("FIELD PROVENANCE (${fieldCauses.size})")
            fieldCauses.take(MAX_DETAIL_ITEMS).forEach { dependency ->
                val source = graph.node(dependency.sourceNodeId)
                val operator = graph.node(dependency.viaOperatorId)
                val sourceField = dependency.sourceField?.let { ".$it" } ?: " (whole value)"
                val confidence = dependency.confidence.name.lowercase()
                appendLine(
                    "  ${source?.label ?: "input"}$sourceField → ${node.label}" +
                        " via ${operator?.label ?: "operator"} [$confidence]",
                )
            }
        }

        if (fieldEffects.isNotEmpty()) {
            appendLine()
            appendLine("FIELDS AFFECTED DOWNSTREAM (${fieldEffects.size})")
            fieldEffects.take(MAX_DETAIL_ITEMS).forEach { dependency ->
                val outputState = graph.node(dependency.outputStateId)
                val operator = graph.node(dependency.viaOperatorId)
                val sourceField = dependency.sourceField?.let { ".$it" } ?: ""
                val outputField = if (dependency.outputField == "\$value") "value" else dependency.outputField
                appendLine(
                    "  ${node.label}$sourceField → ${outputState?.label ?: "derived"}.$outputField" +
                        " via ${operator?.label ?: "operator"}",
                )
            }
            if (fieldEffects.size > MAX_DETAIL_ITEMS) {
                appendLine("  … +${fieldEffects.size - MAX_DETAIL_ITEMS} more")
            }
        }

        appendLine()
        appendLine("ALL AFFECTED FLOWS / STATE (${impact.downstreamStates.size})")
        appendNodeList(impact.downstreamStates)

        appendLine()
        appendLine("AFFECTED COLLECTORS (${impact.downstreamCollectors.size})")
        appendNodeList(impact.downstreamCollectors)

        appendLine()
        appendLine("CAUSAL BEHAVIORS (${impact.downstreamBehaviors.size})")
        appendNodeList(impact.downstreamBehaviors)

        appendLine()
        appendLine("READS / OBSERVERS (${impact.downstreamReads.size})")
        appendNodeList(impact.downstreamReads)

        appendLine()
        appendLine("UPSTREAM WRITERS (${impact.upstreamWriters.size})")
        appendNodeList(impact.upstreamWriters)

        if (showOperators.isSelected) {
            appendLine()
            appendLine("DIRECT INPUTS (${impact.incoming.size})")
            if (impact.incoming.isEmpty()) appendLine("  —")
            impact.incoming.forEach { (edge, other) ->
                appendLine("  ${other.label} --${edge.pretty()}→ ${node.label}")
            }

            appendLine()
            appendLine("DIRECT OUTPUTS (${impact.outgoing.size})")
            if (impact.outgoing.isEmpty()) appendLine("  —")
            impact.outgoing.forEach { (edge, other) ->
                appendLine("  ${node.label} --${edge.pretty()}→ ${other.label}")
            }
        }
    }.trimEnd()
    details.caretPosition = 0
}


