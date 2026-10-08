package com.oskiapps.flowgraph.model

import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal fun FlowGraph.node(nodeId: String): FlowNode? = nodesById[nodeId]



internal fun FlowGraph.incomingEdges(nodeId: String): List<FlowEdge> = edgesByTo[nodeId].orEmpty()



internal fun FlowGraph.fieldsForState(stateId: String): List<FlowNode> {
    val fieldIds = edges.asSequence()
        .filter { it.kind == EdgeKind.FIELD_OF && it.to == stateId }
        .map { it.from }
        .toSet()
    return nodes.filter { it.id in fieldIds && it.kind == NodeKind.FIELD }.sortedBy { it.label }
}

/** Maps both source-property runtime keys and v0.11 synthetic cold-flow operator keys. */



internal fun FlowGraph.nodeByRuntimeKey(key: String): FlowNode? {
    val normalized = key.trim()
    if (normalized.isEmpty()) return null
    runtimeLookupCache[normalized]?.let { return it }
    if (normalized in runtimeLookupMisses) return null

    val resolved = resolveNodeByRuntimeKey(normalized)
    if (resolved != null) runtimeLookupCache[normalized] = resolved else runtimeLookupMisses += normalized
    return resolved
}



internal fun FlowGraph.resolveNodeByRuntimeKey(normalized: String): FlowNode? {
    if (normalized.startsWith("@flowop|")) {
        val parts = normalized.split('|')
        val line = parts.getOrNull(2)?.toIntOrNull()
        val callName = parts.getOrNull(3)?.trim().orEmpty()
        if (line != null && callName.isNotEmpty()) {
            val candidates = nodes.filter { node ->
                node.kind in setOf(NodeKind.OPERATOR, NodeKind.COLLECTOR, NodeKind.EXPOSURE) &&
                    node.source?.line?.plus(1) == line &&
                    node.label.removeSuffix("*") == callName
            }
            if (candidates.size == 1) return candidates.first()
            // Source line is usually enough in a focused graph even when a custom operator label
            // differs slightly from the bytecode method name.
            val byLine = nodes.filter { node ->
                node.kind in setOf(NodeKind.OPERATOR, NodeKind.COLLECTOR, NodeKind.EXPOSURE) &&
                    node.source?.line?.plus(1) == line
            }
            if (byLine.size == 1) return byLine.first()
        }
        return null
    }
    if (normalized.startsWith("@collect|")) {
        val site = normalized.removePrefix("@collect|")
        val line = site.substringAfterLast(':', "").toIntOrNull()
        if (line != null) {
            val candidates = nodes.filter { node ->
                node.kind == NodeKind.COLLECTOR && node.source?.line?.plus(1) == line
            }
            if (candidates.size == 1) return candidates.first()
        }
        return null
    }
    if (normalized.startsWith("@compose2|")) {
        // v2 source-composable identity includes the source line, so same-name overloads in the
        // same file no longer collide. Aliases cover the small declaration/body line variance
        // produced by Kotlin/Compose LineNumberTable generation without falling back by name.
        return nodes.filter { node ->
            node.kind == NodeKind.COMPOSABLE &&
                (node.runtimeKey == normalized || normalized in node.runtimeAliases)
        }.singleOrNull()
    }
    if (normalized.startsWith("@compose|")) {
        // Legacy APK compatibility. Old keys did not include a source line and can therefore be
        // ambiguous for overloads. Only accept them when exactly one source composable claims
        // the key/alias; never guess between same-name declarations.
        return nodes.filter { node ->
            node.kind == NodeKind.COMPOSABLE &&
                (node.runtimeKey == normalized || normalized in node.runtimeAliases)
        }.singleOrNull()
    }
    if (normalized.startsWith("@composestate2|")) {
        val parts = normalized.split('|')
        val sourceFile = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val line = parts.getOrNull(2)?.toIntOrNull()
        val callName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val sourceFunction = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
        if (sourceFile != null && line != null && callName != null && sourceFunction != null) {
            // v0.17.34+: exact source declaration identity. Function context is essential: Kotlin
            // inline/composable lowering can assign the same source line to unrelated State
            // factories. Never fall back to file+line for a v2 runtime event.
            val exactSourceKey =
                "@composestate-source2|$sourceFile|$line|$callName|$sourceFunction"
            val exact = nodes.filter { node ->
                node.kind == NodeKind.STATE && node.runtimeKey == exactSourceKey
            }
            if (exact.size == 1) return exact.first()
            return null
        }
        return null
    }
    if (normalized.startsWith("@composestate|")) {
        val parts = normalized.split('|')
        val line = parts.getOrNull(2)?.toIntOrNull()
        val callName = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val sourceFile = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
        if (line != null && callName != null && sourceFile != null) {
            // Legacy v0.17.32 runtime key: source factory without function context.
            // Do not collapse every State-producing call on one bytecode/source line into the
            // first static Compose-state node; animated/updated states can write at frame rate.
            val exactSourceKey = "@composestate-source|$sourceFile|$line|$callName"
            val exact = nodes.filter { node ->
                node.kind == NodeKind.STATE && node.runtimeKey == exactSourceKey
            }
            if (exact.size == 1) return exact.first()

            // Backward compatibility for graphs produced by pre-v0.17.32 analyzers. Restrict
            // legacy matching to an explicit old line key and the same source file; never use
            // arbitrary node.source line fallback for a modern dynamic Compose-state event.
            val legacy = nodes.filter { node ->
                node.kind == NodeKind.STATE &&
                    node.runtimeKey == "@composestate-line|$line" &&
                    node.source?.file?.name == sourceFile
            }
            if (legacy.size == 1) return legacy.first()
            return null
        }
        return null
    }
    return stateByRuntimeKey(normalized)
}



internal fun FlowGraph.stateByRuntimeKey(key: String): FlowNode? {
    val normalized = key.trim()
    if (normalized.isEmpty()) return null
    val exact = nodes.firstOrNull {
        it.kind == NodeKind.STATE && (it.runtimeKey == normalized || normalized in it.runtimeAliases)
    }
    if (exact != null) return exact
    val suffix = nodes.filter { it.kind == NodeKind.STATE && it.runtimeKey?.endsWith(normalized) == true }
    if (suffix.size == 1) return suffix.first()

    // Fuzzy label/trailing-field matching is only valid for real JVM field-backed state. Local
    // and delegated Compose State uses synthetic @composestate* identities and must never win
    // this fallback. Otherwise an unrelated runtime key such as `SomeRepository.state` can be
    // mapped to a local Compose variable merely because its source label is also `state`. That
    // was the source of impossible values such as [] appearing on ThumbState.
    val fieldBackedCandidates = nodes.filter { node ->
        node.kind == NodeKind.STATE &&
            node.runtimeKey != null &&
            !node.runtimeKey.startsWith("@")
    }
    val byLabel = fieldBackedCandidates.filter { it.label == normalized }
    if (byLabel.size == 1) return byLabel.first()
    // Automatic bytecode instrumentation can only see JVM owners/fields. Companion/top-level
    // lowering can therefore produce a key whose owner differs from the source-level owner.
    // Keep the trailing-field fallback, but only inside field-backed candidates.
    val trailingName = normalized.substringAfterLast('.')
    return fieldBackedCandidates.filter { it.label == trailingName }.singleOrNull()
}



internal fun FlowGraph.dependenciesForField(fieldId: String): List<FieldDependency> =
    fieldDependencies.filter { it.outputFieldId == fieldId }



internal fun FlowGraph.fieldEffectsFrom(nodeId: String): List<FieldDependency> =
    fieldDependencies.filter { it.sourceNodeId == nodeId || it.viaOperatorId == nodeId }

/**
 * Collapses the detailed graph into semantic state-to-state change paths.
 * READ-only branches never become transitions; BEHAVIOR branches do when they eventually write
 * another tracked Flow/StateFlow.
 */



internal fun FlowGraph.upstreamStatesFor(
    startId: String,
    maxDepth: Int,
    initialConfidence: CausalConfidence = CausalConfidence.DEFINITE,
): Map<String, CausalConfidence> {
    data class Step(val id: String, val depth: Int, val confidence: CausalConfidence)
    val result = linkedMapOf<String, CausalConfidence>()
    val bestSeen = mutableMapOf(startId to initialConfidence)
    val queue = ArrayDeque<Step>()
    queue += Step(startId, 0, initialConfidence)
    while (queue.isNotEmpty()) {
        val step = queue.removeFirst()
        if (step.depth >= maxDepth) continue
        edgesByTo[step.id].orEmpty().asSequence()
            .filter { it.kind != EdgeKind.READS && it.kind != EdgeKind.COMPOSES && it.kind != EdgeKind.UPDATES_COMPOSE }
            .forEach { edge ->
                val edgeConfidence = edge.effectiveConfidence()
                val nextConfidence = if (
                    step.confidence == CausalConfidence.POSSIBLE ||
                    edgeConfidence == CausalConfidence.POSSIBLE
                ) CausalConfidence.POSSIBLE else CausalConfidence.DEFINITE
                val source = nodesById[edge.from] ?: return@forEach
                if (source.kind == NodeKind.STATE) {
                    val previous = result[source.id]
                    if (previous == null || nextConfidence == CausalConfidence.DEFINITE) {
                        result[source.id] = nextConfidence
                    }
                } else {
                    val previous = bestSeen[source.id]
                    // Revisit a node only when we found a stronger (definite) route than a
                    // previously known possible one. This preserves definite evidence without
                    // exploding traversal on cycles.
                    if (previous == null ||
                        (previous == CausalConfidence.POSSIBLE && nextConfidence == CausalConfidence.DEFINITE)
                    ) {
                        bestSeen[source.id] = nextConfidence
                        queue += Step(source.id, step.depth + 1, nextConfidence)
                    }
                }
            }
    }
    return result
}


