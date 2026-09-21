package com.oskiapps.flowgraph.service

import com.intellij.openapi.project.Project
import com.oskiapps.flowgraph.model.FlowNode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Pixels captured from the app's live Compose composition, not from Layoutlib/@Preview. */
data class ComposeRenderSnapshot(
    val image: BufferedImage,
    val description: String,
    val activeCompositions: Int,
    val groupsScanned: Int,
    val exactMatches: Int,
    val visibleInstances: Int = 1,
    val capturedAtMillis: Long = System.currentTimeMillis(),
)

sealed interface ComposeRenderState {
    data object Idle : ComposeRenderState
    data class Loading(val message: String) : ComposeRenderState
    data class Ready(val snapshot: ComposeRenderSnapshot) : ComposeRenderState
    data class Failed(val message: String) : ComposeRenderState
}

/**
 * Live Compose renderer.
 *
 * Render UI sends an FGC1 request over the already-open trace socket. The instrumented app then:
 *  1. searches the registry of live Composer.compositionData slot tables;
 *  2. finds the source call-group for this @Composable;
 *  3. unions descendant LayoutInfo bounds (the same geometry model used by Compose tooling);
 *  4. draws the owning AndroidComposeView and returns only that exact live region as PNG.
 *
 * There is no generated @Preview, no argument reconstruction and no adb screenshot fallback.
 */
class ComposeRenderService(private val project: Project) {
    private val states = ConcurrentHashMap<String, ComposeRenderState>()
    private val requestTokens = ConcurrentHashMap<String, Long>()

    fun state(nodeId: String): ComposeRenderState = states[nodeId] ?: ComposeRenderState.Idle

    fun snapshot(nodeId: String): ComposeRenderSnapshot? =
        (states[nodeId] as? ComposeRenderState.Ready)?.snapshot

    fun clear(nodeId: String? = null) {
        if (nodeId == null) {
            states.clear()
            requestTokens.clear()
        } else {
            states.remove(nodeId)
            requestTokens.remove(nodeId)
        }
    }

    fun requestRender(
        node: FlowNode,
        force: Boolean = false,
        onUpdate: (ComposeRenderState) -> Unit = {},
    ) {
        val runtimeKey = node.runtimeKey?.takeIf { it.startsWith("@compose|") }
        if (runtimeKey == null) {
            update(node.id, ComposeRenderState.Failed("This Compose host has no inspectable source @Composable runtime key."), onUpdate)
            return
        }

        val existing = state(node.id)
        if (!force && (existing is ComposeRenderState.Loading || existing is ComposeRenderState.Ready)) {
            onUpdate(existing)
            return
        }

        val trace = project.getService(LiveTraceService::class.java)
        if (!trace.isRunning() || trace.status().activeClients <= 0) {
            update(
                node.id,
                ComposeRenderState.Failed(
                    "No instrumented debug app is connected. Enable Live trace, run adb reverse, then run the debug app."
                ),
                onUpdate,
            )
            return
        }

        val requestedAt = System.currentTimeMillis()
        requestTokens[node.id] = requestedAt
        update(node.id, ComposeRenderState.Loading("Inspecting live Compose slot table…"), onUpdate)

        if (trace.requestComposeImage(runtimeKey) <= 0) {
            update(node.id, ComposeRenderState.Failed("The connected app did not accept the Compose inspection request."), onUpdate)
            return
        }

        val timer = Timer(POLL_MS, null)
        timer.addActionListener {
            if (requestTokens[node.id] != requestedAt) {
                timer.stop()
                return@addActionListener
            }

            val event = trace.latestComposeRenderResponse(runtimeKey)
                ?.takeIf {
                    it.receivedAtMillis >= requestedAt &&
                        (it.kind == "compose-image" || it.kind == "compose-image-error")
                }

            if (event != null) {
                timer.stop()
                if (event.kind == "compose-image-error") {
                    update(node.id, ComposeRenderState.Failed(event.valueSummary ?: "Live Compose inspection failed."), onUpdate)
                } else {
                    val snapshot = decodeSnapshot(event.valueSummary)
                    if (snapshot == null) {
                        update(node.id, ComposeRenderState.Failed("The live Compose image payload could not be decoded."), onUpdate)
                    } else {
                        update(node.id, ComposeRenderState.Ready(snapshot), onUpdate)
                    }
                }
                return@addActionListener
            }

            if (System.currentTimeMillis() - requestedAt >= REQUEST_TIMEOUT_MS) {
                timer.stop()
                update(
                    node.id,
                    ComposeRenderState.Failed(
                        "Timed out waiting for the live composable. Navigate to ${node.label} so it is present in the current composition, then refresh."
                    ),
                    onUpdate,
                )
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    private fun decodeSnapshot(payload: String?): ComposeRenderSnapshot? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.split('|', limit = 9)
        if (parts.size !in 8..9 || parts[0] !in setOf(IMAGE_VERSION, LEGACY_IMAGE_VERSION)) return null
        val declaredWidth = parts[1].toIntOrNull() ?: return null
        val declaredHeight = parts[2].toIntOrNull() ?: return null
        val png = runCatching { Base64.getUrlDecoder().decode(parts[3]) }.getOrNull() ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
        val matches = parts[5].toIntOrNull() ?: 1
        val compositions = parts[6].toIntOrNull() ?: 1
        val groups = parts[7].toIntOrNull() ?: 0
        val visibleInstances = parts.getOrNull(8)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val size = if (declaredWidth > 0 && declaredHeight > 0) "${declaredWidth}×${declaredHeight}" else "${image.width}×${image.height}"
        return ComposeRenderSnapshot(
            image = image,
            description = "Live composition • $size • active compositions: $compositions • visible instances: $visibleInstances • groups scanned: $groups • exact source match${if (matches == 1) "" else "es"}: $matches",
            activeCompositions = compositions,
            groupsScanned = groups,
            exactMatches = matches,
            visibleInstances = visibleInstances,
        )
    }

    private fun update(nodeId: String, state: ComposeRenderState, callback: (ComposeRenderState) -> Unit) {
        states[nodeId] = state
        if (SwingUtilities.isEventDispatchThread()) callback(state)
        else SwingUtilities.invokeLater { callback(state) }
    }

    private companion object {
        const val IMAGE_VERSION = "FGIMG3"
        const val LEGACY_IMAGE_VERSION = "FGIMG2"
        const val POLL_MS = 100
        const val REQUEST_TIMEOUT_MS = 6_000L
    }
}
