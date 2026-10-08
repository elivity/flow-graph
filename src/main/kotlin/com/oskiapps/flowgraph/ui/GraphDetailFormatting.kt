package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.*
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_DETAIL_ITEMS
import java.awt.Rectangle

internal fun StringBuilder.appendTransitions(
        graph: FlowGraph,
        transitions: List<StateTransition>,
        outgoing: Boolean,
    ) {
        if (transitions.isEmpty()) {
            appendLine("  —")
            return
        }
        transitions.take(MAX_DETAIL_ITEMS).forEach { transition ->
            val otherId = if (outgoing) transition.targetStateId else transition.sourceStateId
            val other = graph.node(otherId)
            val kind = when (transition.kind) {
                StateTransitionKind.DERIVED -> "derived"
                StateTransitionKind.EXPOSURE -> "exposed"
                StateTransitionKind.SIDE_EFFECT -> "SIDE EFFECT WRITE"
            }
            val confidence = if (transition.confidence == CausalConfidence.POSSIBLE) " • possible" else ""
            appendLine("  ${if (outgoing) "→" else "←"} ${other?.label ?: "state"} [$kind$confidence]")
            appendLine("      ${transition.summary}")
            transition.viaNodeIds.mapNotNull(graph::node).forEach { via ->
                val where = via.source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
                appendLine("      ${via.label}$where")
            }
        }
        if (transitions.size > MAX_DETAIL_ITEMS) {
            appendLine("  … +${transitions.size - MAX_DETAIL_ITEMS} more")
        }
    }

internal fun StringBuilder.appendNodeList(nodes: List<FlowNode>) {
        if (nodes.isEmpty()) {
            appendLine("  —")
            return
        }
        nodes.take(MAX_DETAIL_ITEMS).forEach { node ->
            val where = node.source?.let { " • ${it.file.name}:${it.line + 1}" } ?: ""
            appendLine("  ${node.label}$where")
        }
        if (nodes.size > MAX_DETAIL_ITEMS) {
            appendLine("  … +${nodes.size - MAX_DETAIL_ITEMS} more")
        }
    }

internal fun FlowEdge.pretty(): String {
        label?.let { return if (confidence == CausalConfidence.POSSIBLE) "possible: $it" else it }
        val base = kind.name.lowercase().replace('_', ' ')
        if (affectedFields.isEmpty()) return base
        val fields = affectedFields
            .map { if (it == "\$value") "value" else it }
            .sorted()
            .take(4)
            .joinToString(", ")
        val more = if (affectedFields.size > 4) " +${affectedFields.size - 4}" else ""
        return "$base • $fields$more"
    }
