package dev.flowgraph.model

data class RuntimeTraceEvent(
    val timestampNanos: Long,
    val stateKey: String,
    val kind: String,
    val valueSummary: String?,
    val site: String?,
    val fields: Set<String> = emptySet(),
    /** IDE receipt wall-clock time. Device monotonic timestamps cannot be compared to host time. */
    val receivedAtMillis: Long = System.currentTimeMillis(),
)

data class RuntimeObservedEdge(
    val fromStateId: String,
    val toStateId: String,
    val count: Int,
    val lastTimestampNanos: Long,
)

data class RuntimeOverlay(
    /** Confirmed source emissions/writes (StateFlow set/update, SharedFlow tryEmit/immediate emit). */
    val nodeCounts: Map<String, Int> = emptyMap(),
    val readCounts: Map<String, Int> = emptyMap(),
    /** Cold/hot Flow values actually delivered across a bound FlowCollector boundary. */
    val deliveryCounts: Map<String, Int> = emptyMap(),
    /** Number of runtime collect subscriptions observed for this Flow. */
    val collectCounts: Map<String, Int> = emptyMap(),
    /** Suspended SharedFlow.emit requests; completion may occur later. */
    val emitRequestCounts: Map<String, Int> = emptyMap(),
    val edgeCounts: Map<Pair<String, String>, Int> = emptyMap(),
    val lastEventByNode: Map<String, RuntimeTraceEvent> = emptyMap(),
    /** IDE-receipt wall-clock time used only for transient live-activity highlighting. */
    val lastActivityAtMillis: Map<String, Long> = emptyMap(),
    val lastNodeId: String? = null,
) {
    companion object {
        val EMPTY = RuntimeOverlay()
    }
}
