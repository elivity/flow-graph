package dev.flowgraph.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import dev.flowgraph.model.RuntimeTraceEvent
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Transport-level status for the debug live-trace socket. */
data class LiveTraceStatus(
    val running: Boolean = false,
    val port: Int? = null,
    val activeClients: Int = 0,
    val totalConnections: Long = 0,
    val validEvents: Long = 0,
    val malformedLines: Long = 0,
    val recordingPaused: Boolean = false,
    val ignoredWhilePaused: Long = 0,
    val lastClient: String? = null,
    val lastError: String? = null,
)

@Service(Service.Level.PROJECT)
class LiveTraceService : Disposable {
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(RuntimeTraceEvent) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(LiveTraceStatus) -> Unit>()
    private val historyLock = Any()
    private val recentEvents = ArrayDeque<RuntimeTraceEvent>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "FlowGraph-LiveTrace").apply { isDaemon = true }
    }

    private val activeClients = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private val validEvents = AtomicLong(0)
    private val malformedLines = AtomicLong(0)
    private val recordingPaused = AtomicBoolean(false)
    private val ignoredWhilePaused = AtomicLong(0)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var lastClient: String? = null
    @Volatile private var lastError: String? = null

    fun isRunning(): Boolean = running.get()

    fun status(): LiveTraceStatus = LiveTraceStatus(
        running = running.get(),
        port = server?.localPort,
        activeClients = activeClients.get(),
        totalConnections = totalConnections.get(),
        validEvents = validEvents.get(),
        malformedLines = malformedLines.get(),
        recordingPaused = recordingPaused.get(),
        ignoredWhilePaused = ignoredWhilePaused.get(),
        lastClient = lastClient,
        lastError = lastError,
    )

    fun start(port: Int = DEFAULT_PORT): Result<Int> = runCatching {
        if (running.get()) return@runCatching server?.localPort ?: port

        val socket = ServerSocket(port)
        server = socket
        lastError = null
        running.set(true)
        publishStatus()

        executor.submit {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    activeClients.incrementAndGet()
                    totalConnections.incrementAndGet()
                    lastClient = client.remoteSocketAddress?.toString()
                    lastError = null
                    publishStatus()

                    executor.submit {
                        try {
                            client.use { connection ->
                                connection.getInputStream().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                                    lines.forEach { line ->
                                        val event = parse(line)
                                        if (event == null) {
                                            val malformed = malformedLines.incrementAndGet()
                                            // Do not flood the IDE UI with a status callback per bad line.
                                            if (malformed == 1L || malformed % STATUS_PUBLISH_EVERY == 0L) publishStatus()
                                        } else {
                                            val valid = validEvents.incrementAndGet()
                                            if (recordingPaused.get()) {
                                                val ignored = ignoredWhilePaused.incrementAndGet()
                                                // Keep draining the socket so instrumentation never blocks the app,
                                                // but deliberately do not retain or publish events while the user
                                                // is inspecting a frozen profiler history.
                                                if (ignored == 1L || ignored % STATUS_PUBLISH_EVERY == 0L) publishStatus()
                                            } else {
                                                remember(event)
                                                listeners.forEach { it(event) }
                                                // Runtime Flow delivery can be extremely hot. Status is sampled;
                                                // the UI also polls status whenever it flushes an event batch.
                                                if (valid % STATUS_PUBLISH_EVERY == 0L) publishStatus()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            if (running.get()) {
                                lastError = "Client error: ${error.message ?: error.javaClass.simpleName}"
                            }
                        } finally {
                            activeClients.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
                            publishStatus()
                        }
                    }
                } catch (_: SocketException) {
                    // Expected when stopping the server.
                } catch (error: Throwable) {
                    if (running.get()) {
                        lastError = "Server error: ${error.message ?: error.javaClass.simpleName}"
                        publishStatus()
                    }
                }
            }
        }
        socket.localPort
    }.onFailure { error ->
        lastError = "Could not listen: ${error.message ?: error.javaClass.simpleName}"
        publishStatus()
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        activeClients.set(0)
        publishStatus()
    }


    /**
     * Freeze runtime recording without closing the socket. Existing history is preserved exactly
     * as-is; incoming device events are drained from the transport but intentionally ignored until
     * [resumeRecording] is called. This prevents an instrumented app from blocking on a full socket
     * while giving timeline scrubbing real profiler-style pause semantics.
     */
    fun pauseRecording() {
        if (recordingPaused.compareAndSet(false, true)) {
            ignoredWhilePaused.set(0)
            publishStatus()
        }
    }

    /** Resume retaining/publishing future runtime events after a timeline pause. */
    fun resumeRecording() {
        if (recordingPaused.compareAndSet(true, false)) publishStatus()
    }

    fun isRecordingPaused(): Boolean = recordingPaused.get()

    fun addListener(listener: (RuntimeTraceEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (RuntimeTraceEvent) -> Unit) {
        listeners -= listener
    }

    fun addStatusListener(listener: (LiveTraceStatus) -> Unit) {
        statusListeners += listener
        listener(status())
    }

    fun removeStatusListener(listener: (LiveTraceStatus) -> Unit) {
        statusListeners -= listener
    }

    /**
     * Recent runtime events are retained independently of the currently displayed static graph.
     * This lets the user switch to a different StateFlow graph without rebuilding or losing the
     * runtime activity that already happened while another graph was visible.
     */
    fun recentEvents(limit: Int = MAX_HISTORY_EVENTS): List<RuntimeTraceEvent> = synchronized(historyLock) {
        if (limit <= 0 || recentEvents.isEmpty()) return@synchronized emptyList()
        recentEvents.toList().takeLast(limit.coerceAtMost(MAX_HISTORY_EVENTS))
    }

    fun clearRecentEvents() = synchronized(historyLock) {
        recentEvents.clear()
    }

    override fun dispose() {
        stop()
        executor.shutdownNow()
        listeners.clear()
        statusListeners.clear()
    }

    private fun remember(event: RuntimeTraceEvent) {
        synchronized(historyLock) {
            recentEvents.addLast(event)
            val cutoff = event.receivedAtMillis - MAX_HISTORY_AGE_MS
            while (recentEvents.isNotEmpty() &&
                (recentEvents.size > MAX_HISTORY_EVENTS || recentEvents.first().receivedAtMillis < cutoff)
            ) {
                recentEvents.removeFirst()
            }
        }
    }

    private fun publishStatus() {
        val snapshot = status()
        statusListeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun parse(line: String): RuntimeTraceEvent? {
        val parts = line.split('\t')
        if (parts.size < 7 || parts[0] != "FG1") return null
        fun decode(index: Int): String = runCatching {
            String(Base64.getUrlDecoder().decode(parts[index]), StandardCharsets.UTF_8)
        }.getOrDefault("")

        return RuntimeTraceEvent(
            timestampNanos = parts[1].toLongOrNull() ?: return null,
            stateKey = decode(2),
            kind = decode(3),
            valueSummary = decode(4).takeIf { it.isNotBlank() },
            site = decode(5).takeIf { it.isNotBlank() },
            fields = decode(6).split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
        )
    }

    companion object {
        const val DEFAULT_PORT = 50737
        const val MAX_HISTORY_EVENTS = 30_000
        const val MAX_HISTORY_AGE_MS = 5 * 60_000L
        const val STATUS_PUBLISH_EVERY = 500L
    }
}
