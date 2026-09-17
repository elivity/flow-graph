@file:Suppress("unused")

package dev.flowgraph.runtime

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Optional debug-only bridge for Flow Graph v0.8.1.
 *
 * On a physical device/emulator run:
 *   adb reverse tcp:50737 tcp:50737
 *
 * Then enable "Live trace" in the IDE plugin and register the StateFlows you want to observe.
 * Runtime connectivity is logged under the Logcat tag "FlowGraphTrace".
 */
object FlowGraphTrace {
    @Volatile var enabled: Boolean = true
    @Volatile var host: String = "127.0.0.1"
    @Volatile var port: Int = 50737
    @Volatile var logEvents: Boolean = true
    @Volatile var connectTimeoutMs: Int = 2_000

    private const val TAG = "FlowGraphTrace"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FlowGraphTrace").apply { isDaemon = true }
    }
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    fun emit(
        stateKey: String,
        value: Any?,
        kind: String = "emit",
        site: String? = null,
        fields: Set<String> = emptySet(),
    ) {
        if (!enabled) return
        val valueSummary = summarize(value)
        executor.execute {
            runCatching {
                val out = ensureWriter()
                val line = listOf(
                    "FG1",
                    System.nanoTime().toString(),
                    enc(stateKey),
                    enc(kind),
                    enc(valueSummary),
                    enc(site.orEmpty()),
                    enc(fields.joinToString(",")),
                ).joinToString("\t")
                out.write(line)
                out.newLine()
                out.flush()
                if (logEvents) {
                    Log.d(TAG, "SEND $kind $stateKey = $valueSummary")
                }
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Live trace send failed for $stateKey → $host:$port: " +
                        "${error.message ?: error.javaClass.simpleName}. " +
                        "Check that Flow Graph Live trace is ON and run: adb reverse tcp:$port tcp:$port",
                    error,
                )
                closeConnection()
            }
        }
    }

    fun close() {
        executor.execute { closeConnection() }
    }

    private fun ensureWriter(): BufferedWriter {
        writer?.let { return it }
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = newSocket
        Log.i(TAG, "Connected to Flow Graph live trace at $host:$port")
        return BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8)).also {
            writer = it
        }
    }

    private fun closeConnection() {
        val hadConnection = socket != null || writer != null
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
        if (hadConnection) Log.i(TAG, "Disconnected from Flow Graph live trace")
    }

    private fun enc(value: String): String =
        Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun summarize(value: Any?): String = when (value) {
        null -> "null"
        else -> value.toString().replace('\n', ' ').take(500)
    }
}

/**
 * Observe real StateFlow emissions. The key should match the IDE runtime key displayed in the node
 * details, e.g. "com.example.player.PlayerViewModel._state".
 */
fun <T> StateFlow<T>.traceFlowGraph(
    scope: CoroutineScope,
    stateKey: String,
    fields: Set<String> = emptySet(),
): Job = scope.launch {
    if (FlowGraphTrace.logEvents) {
        Log.i("FlowGraphTrace", "Registered StateFlow: $stateKey")
    }
    var first = true
    collect { value ->
        FlowGraphTrace.emit(
            stateKey = stateKey,
            value = value,
            kind = if (first) "initial" else "emit",
            fields = fields,
        )
        first = false
    }
}
