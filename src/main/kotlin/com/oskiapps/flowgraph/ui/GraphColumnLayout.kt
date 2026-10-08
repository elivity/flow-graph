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

internal fun GraphCanvas.computeColumns(model: FlowGraph): Map<String, Int> {
    val ids = model.nodes.mapTo(linkedSetOf()) { it.id }
    val incoming = ids.associateWithTo(mutableMapOf()) { 0 }
    val outgoing = ids.associateWithTo(mutableMapOf()) { mutableListOf<String>() }
    model.edges.forEach { edge ->
        if (edge.from !in ids || edge.to !in ids) return@forEach
        outgoing.getValue(edge.from) += edge.to
        incoming[edge.to] = incoming.getValue(edge.to) + 1
    }

    val level = ids.associateWithTo(mutableMapOf()) { 0 }
    val queue = ArrayDeque<String>()
    incoming.filterValues { it == 0 }.keys.forEach(queue::addLast)
    val remainingIncoming = incoming.toMutableMap()

    while (queue.isNotEmpty()) {
        val from = queue.removeFirst()
        outgoing.getValue(from).forEach { to ->
            level[to] = max(level.getValue(to), level.getValue(from) + 1)
            val left = remainingIncoming.getValue(to) - 1
            remainingIncoming[to] = left
            if (left == 0) queue.addLast(to)
        }
    }

    repeat(ids.size.coerceAtMost(20)) {
        var changed = false
        model.edges.forEach { edge ->
            val proposed = (level[edge.from] ?: 0) + 1
            if (proposed > (level[edge.to] ?: 0) && proposed <= ids.size) {
                level[edge.to] = proposed
                changed = true
            }
        }
        if (!changed) return@repeat
    }
    return level
}


