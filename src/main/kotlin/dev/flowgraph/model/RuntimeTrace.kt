package dev.flowgraph.model

data class RuntimeTraceEvent(
    val timestampNanos: Long,
    val stateKey: String,
    val kind: String,
    val valueSummary: String?,
    val site: String?,
    val fields: Set<String> = emptySet(),
    /** Number of source events represented by this transport sample after runtime coalescing. */
    val occurrences: Int = 1,
    /** Runtime identity for repeated/keyed Compose State objects sharing one source node. */
    val instanceId: Int? = null,
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
    /** Confirmed Flow/SharedFlow emissions and @Composable recompositions. */
    val nodeCounts: Map<String, Int> = emptyMap(),
    /** Confirmed Compose State value changes. Kept separate so reads are never mislabeled as emits. */
    val changeCounts: Map<String, Int> = emptyMap(),
    val readCounts: Map<String, Int> = emptyMap(),
    /** Cold/hot Flow values actually delivered across a bound FlowCollector boundary. */
    val deliveryCounts: Map<String, Int> = emptyMap(),
    /** Number of runtime collect subscriptions observed for this Flow. */
    val collectCounts: Map<String, Int> = emptyMap(),
    /** Suspended SharedFlow.emit requests; completion may occur later. */
    val emitRequestCounts: Map<String, Int> = emptyMap(),
    val edgeCounts: Map<Pair<String, String>, Int> = emptyMap(),
    val lastEventByNode: Map<String, RuntimeTraceEvent> = emptyMap(),
    /** Latest value-bearing event for each observed Compose State runtime instance. */
    val composeInstanceEventsByNode: Map<String, Map<Int, RuntimeTraceEvent>> = emptyMap(),
    /** IDE-receipt wall-clock time used only for transient live-activity highlighting. */
    val lastActivityAtMillis: Map<String, Long> = emptyMap(),
    val lastNodeId: String? = null,
) {
    companion object {
        val EMPTY = RuntimeOverlay()
    }
}
