package com.oskiapps.flowgraph.model

import java.util.ArrayDeque

enum class SimulationStatus {
    DEFINITE,
    CONDITIONAL,
    TIMING_DEPENDENT,
    CANCELLATION_AWARE,
    POSSIBLE,
    SUPPRESSED,
}

data class SimulationSpec(
    val sourceStateId: String,
    /** Null means the whole state value changed. */
    val changedField: String? = null,
    /** Models assigning an equal value to a StateFlow; propagation stops immediately. */
    val equalToCurrentValue: Boolean = false,
)

data class SimulationHop(
    val sequence: Int,
    val sourceStateId: String,
    val targetStateId: String,
    val transition: StateTransition,
    val status: SimulationStatus,
    val sourceFields: Set<String>,
    val targetFields: Set<String>,
    val note: String,
)

data class SimulationResult(
    val spec: SimulationSpec,
    val hops: List<SimulationHop>,
    val reachedStateIds: Set<String>,
    val diagnostics: List<String> = emptyList(),
) {
    val maxSequence: Int get() = hops.maxOfOrNull { it.sequence } ?: 0

    fun hopsThrough(sequence: Int): List<SimulationHop> = hops.filter { it.sequence <= sequence }
}

/**
 * Static/symbolic propagation simulator. It deliberately does not execute arbitrary Kotlin code.
 * Instead it follows the semantic Flow graph and annotates points where runtime values/timing can
 * suppress, delay, switch or cancel propagation.
 */
fun FlowGraph.simulate(spec: SimulationSpec): SimulationResult {
    val source = node(spec.sourceStateId)
        ?: return SimulationResult(spec, emptyList(), emptySet(), listOf("Source state was not found in the graph."))
    if (source.kind != NodeKind.STATE) {
        return SimulationResult(spec, emptyList(), setOf(source.id), listOf("Simulation must start from a Flow/StateFlow state node."))
    }

    if (spec.equalToCurrentValue) {
        return SimulationResult(
            spec = spec,
            hops = emptyList(),
            reachedStateIds = setOf(source.id),
            diagnostics = listOf("Equal StateFlow assignment simulated: StateFlow equality suppresses the emission at the source."),
        )
    }

    val transitions = stateTransitions().groupBy { it.sourceStateId }
    val fieldDeps = fieldDependencies.groupBy { it.sourceNodeId to it.outputStateId }
    val result = mutableListOf<SimulationHop>()
    val reached = linkedSetOf(source.id)
    val diagnostics = mutableListOf<String>()

    data class Cursor(
        val stateId: String,
        val fields: Set<String>,
        val sequence: Int,
        val visited: Set<Pair<String, Set<String>>>,
    )

    val initialFields = spec.changedField?.let(::setOf) ?: emptySet()
    val queue = ArrayDeque<Cursor>()
    queue += Cursor(source.id, initialFields, 0, setOf(source.id to initialFields))

    var nextSequence = 1
    while (queue.isNotEmpty()) {
        val cursor = queue.removeFirst()
        transitions[cursor.stateId].orEmpty().forEach { transition ->
            val dependencies = fieldDeps[cursor.stateId to transition.targetStateId].orEmpty()
            val targetFields = when {
                cursor.fields.isEmpty() -> transition.affectedFields
                dependencies.isNotEmpty() -> dependencies.asSequence()
                    .filter { dependency ->
                        dependency.sourceField == null || cursor.fields.any { changed ->
                            dependency.sourceField == changed ||
                                dependency.sourceField.startsWith("$changed.") ||
                                changed.startsWith("${dependency.sourceField}.")
                        }
                    }
                    .map { it.outputField }
                    .toCollection(linkedSetOf())
                transition.affectedFields.isNotEmpty() -> transition.affectedFields
                else -> emptySet()
            }

            // If we have explicit field provenance and none of the changed fields feed this target,
            // this branch is not part of the field-specific simulation.
            if (cursor.fields.isNotEmpty() && dependencies.isNotEmpty() && targetFields.isEmpty()) {
                return@forEach
            }

            val status = classifySimulationStatus(transition)
            val note = simulationNote(transition, status)
            val hop = SimulationHop(
                sequence = nextSequence++,
                sourceStateId = cursor.stateId,
                targetStateId = transition.targetStateId,
                transition = transition,
                status = status,
                sourceFields = cursor.fields,
                targetFields = targetFields,
                note = note,
            )
            result += hop
            reached += transition.targetStateId

            // CONDITIONAL/TIMING/POSSIBLE hops are still followed: this is a "could propagate"
            // simulation. The annotation makes the uncertainty explicit instead of silently pruning.
            val nextKey = transition.targetStateId to targetFields
            if (nextKey !in cursor.visited && cursor.visited.size < 64) {
                queue += Cursor(
                    stateId = transition.targetStateId,
                    fields = targetFields,
                    sequence = hop.sequence,
                    visited = cursor.visited + nextKey,
                )
            }
        }
    }

    if (result.isEmpty()) {
        diagnostics += if (spec.changedField == null) {
            "No downstream state-changing path was found from ${source.label}."
        } else {
            "No downstream path was found for field '${spec.changedField}'. Try whole-value simulation if provenance is incomplete."
        }
    }

    return SimulationResult(spec, result, reached, diagnostics)
}

private fun classifySimulationStatus(transition: StateTransition): SimulationStatus {
    if (transition.confidence == CausalConfidence.POSSIBLE) return SimulationStatus.POSSIBLE
    val labels = transition.summary.lowercase()
    return when {
        labels.contains("collectlatest") ||
            labels.contains("flatmaplatest") ||
            labels.contains("maplatest") ||
            labels.contains("transformlatest") -> SimulationStatus.CANCELLATION_AWARE

        labels.contains("debounce") ||
            labels.contains("sample") ||
            labels.contains("throttle") -> SimulationStatus.TIMING_DEPENDENT

        labels.contains("filter") ||
            labels.contains("distinctuntilchanged") ||
            labels.contains("mapnotnull") ||
            labels.contains("takewhile") ||
            labels.contains("dropwhile") ||
            labels.contains("catch") -> SimulationStatus.CONDITIONAL

        else -> SimulationStatus.DEFINITE
    }
}

private fun simulationNote(transition: StateTransition, status: SimulationStatus): String = when (status) {
    SimulationStatus.DEFINITE -> "Static graph says this emission propagates through ${transition.summary}."
    SimulationStatus.CONDITIONAL -> "Runtime value can suppress this path (${transition.summary})."
    SimulationStatus.TIMING_DEPENDENT -> "Timing can delay or suppress this path (${transition.summary})."
    SimulationStatus.CANCELLATION_AWARE -> "A newer emission can cancel/switch work on this path (${transition.summary})."
    SimulationStatus.POSSIBLE -> "Possible imperative coupling; runtime control flow decides whether it happens (${transition.summary})."
    SimulationStatus.SUPPRESSED -> "Propagation is suppressed."
}
