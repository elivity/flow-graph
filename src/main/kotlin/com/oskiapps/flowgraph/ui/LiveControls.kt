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

internal fun FlowGraphPanel.updateLiveControlVisibility() {
    val enabled = liveTrace.isSelected
    listOf(
        liveStatusIndicator, adbReverse, currentUi, clearRuntime, liveInfo,
        recentOnly, eventLens, runtimeBranchesOnly, runtimeTimeline,
    ).forEach { it.isVisible = enabled }
    liveDiagnostics.isVisible = enabled && liveInfo.isSelected
    if (!enabled) {
        // Preserve user preferences, but do not keep live-only filtering active.
        canvas.recentFocusEnabled = false
        canvas.recentFocusIds = emptySet()
        canvas.eventLensEnabled = false
    } else {
        canvas.recentFocusEnabled = recentOnly.isSelected
        canvas.eventLensEnabled = eventLens.isSelected
    }
    updateComposeRenderPaneVisibility()
    revalidate()
    repaint()
}

/** ADB work stays off the Swing EDT; an explicit device is chosen if several exist. */



internal fun FlowGraphPanel.configureAdbReverse() {
    val panel = this
    adbReverse.isEnabled = false
    object : SwingWorker<Pair<List<String>, String>, Unit>() {
        override fun doInBackground(): Pair<List<String>, String> {
            val sdk = sequenceOf(
                System.getenv("ANDROID_SDK_ROOT"),
                System.getenv("ANDROID_HOME"),
                project.basePath?.let { base ->
                    val props = java.util.Properties()
                    val file = java.io.File(base, "local.properties")
                    if (file.isFile) file.inputStream().use { props.load(it) }
                    props.getProperty("sdk.dir")
                },
            ).filterNotNull().firstOrNull { java.io.File(it, "platform-tools/adb").isFile }
            val adb = sdk?.let { "$it/platform-tools/adb" } ?: "adb"
            fun run(vararg args: String): Pair<Int, String> {
                val process = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
                if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("ADB command timed out")
                }
                return process.exitValue() to process.inputStream.bufferedReader().readText().trim()
            }
            val (exit, output) = run("devices")
            if (exit != 0) error(output.ifBlank { "Unable to execute adb" })
            val devices = output.lineSequence().drop(1).mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                columns.firstOrNull()?.takeIf { columns.getOrNull(1) == "device" }
            }.toList()
            return devices to adb
        }

        override fun done() {
            adbReverse.isEnabled = true
            try {
                val (devices, adb) = get()
                if (devices.isEmpty()) {
                    JOptionPane.showMessageDialog(panel,
                        "No authorized Android device found. Connect a device and enable USB debugging.",
                        "ADB reverse", JOptionPane.WARNING_MESSAGE)
                    return
                }
                val device = if (devices.size == 1) devices.first() else {
                    JOptionPane.showInputDialog(panel,
                        "Choose the Android device:", "ADB reverse",
                        JOptionPane.QUESTION_MESSAGE, null,
                        devices.toTypedArray(), devices.first()) as? String ?: return
                }
                adbReverse.isEnabled = false
                object : SwingWorker<String, Unit>() {
                    override fun doInBackground(): String {
                        val port = liveTraceService.status().port ?: LiveTraceService.DEFAULT_PORT
                        val process = ProcessBuilder(adb, "-s", device, "reverse",
                            "tcp:$port", "tcp:$port")
                            .redirectErrorStream(true).start()
                        if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                            error("ADB reverse timed out")
                        }
                        val output = process.inputStream.bufferedReader().readText().trim()
                        if (process.exitValue() != 0) error(output.ifBlank { "adb reverse failed" })
                        return "ADB reverse configured for $device on port $port."
                    }

                    override fun done() {
                        adbReverse.isEnabled = true
                        try {
                            JOptionPane.showMessageDialog(panel, get(),
                                "ADB reverse", JOptionPane.INFORMATION_MESSAGE)
                        } catch (error: Exception) {
                            JOptionPane.showMessageDialog(panel,
                                error.cause?.message ?: error.message,
                                "ADB reverse failed", JOptionPane.ERROR_MESSAGE)
                        }
                    }
                }.execute()
            } catch (error: Exception) {
                JOptionPane.showMessageDialog(panel,
                    error.cause?.message ?: error.message,
                    "ADB reverse failed", JOptionPane.ERROR_MESSAGE)
            }
        }
    }.execute()
}



internal fun FlowGraphPanel.toggleLiveTrace() {
    if (liveTrace.isSelected) {
        liveTraceService.start().onSuccess { port ->
            hint.text = if (liveTraceService.isRecordingPaused()) {
                "Live trace transport listening, but profiler recording is PAUSED • press LIVE on the timeline to resume"
            } else {
                "Live trace listening on :$port • waiting for an instrumented debug client"
            }
            liveTransportStatus = liveTraceService.status()
            updateLiveStatusIndicator(liveTransportStatus)
            updateLiveTraceDiagnostics()
        }.onFailure { error ->
            liveTrace.isSelected = false
            liveTransportStatus = liveTraceService.status()
            updateLiveStatusIndicator(liveTransportStatus)
            updateLiveTraceDiagnostics()
            JOptionPane.showMessageDialog(
                this,
                "Could not start Flow Graph live trace server: ${error.message}",
                "Flow Graph",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    } else {
        liveTraceService.stop()
        runtimeActivityCooldownTimer.stop()
        clearRuntimeOverlay()
        canvas.recentFocusIds = emptySet()
        canvas.recentFocusEnabled = false
        liveTransportStatus = liveTraceService.status()
        updateLiveStatusIndicator(liveTransportStatus)
        updateLiveTraceDiagnostics()
        hint.text = "Static flow dependencies • enable Live to inspect runtime events"
        renderActive(autoFit = false)
    }
}



internal fun FlowGraphPanel.updateLiveStatusIndicator(status: LiveTraceStatus = liveTransportStatus) {
    updateLiveControlVisibility()
    if (activeGraph != null) renderActive(autoFit = false)
    currentUi.isEnabled = status.running && status.activeClients > 0
    when {
        !status.running -> {
            liveStatusIndicator.text = "● Off"
            liveStatusIndicator.foreground = UIManager.getColor("Label.disabledForeground") ?: Color(130, 135, 140)
            liveStatusIndicator.toolTipText = "Live trace server is off"
        }
        status.activeClients <= 0 -> {
            liveStatusIndicator.text = "● Waiting for client"
            liveStatusIndicator.foreground = Color(222, 166, 55)
            liveStatusIndicator.toolTipText = "Server is listening on :${status.port ?: LiveTraceService.DEFAULT_PORT}; no instrumented client is connected"
        }
        else -> {
            liveStatusIndicator.text = "● On"
            liveStatusIndicator.foreground = Color(76, 190, 112)
            liveStatusIndicator.toolTipText = "${status.activeClients} live client${if (status.activeClients == 1) "" else "s"} connected"
        }
    }
}


