@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.ForwardPathResult
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_ANALYZED_PROPERTIES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_DERIVATION_DEPTH
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_GRAPH_NODES
import com.oskiapps.flowgraph.analysis.AnalysisApiFlowAnalyzer.Companion.MAX_REFERENCES_PER_PROPERTY
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.oskiapps.flowgraph.model.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import java.util.ArrayDeque

internal fun AnalysisApiFlowAnalyzer.buildGraph(root: KtProperty): FlowGraph = buildGraph(
    roots = listOf(root),
    rootLabel = root.name ?: "Flow",
    maxAnalyzedProperties = MAX_ANALYZED_PROPERTIES,
    maxGraphNodes = MAX_GRAPH_NODES,
)



internal fun AnalysisApiFlowAnalyzer.buildGraph(
    roots: Collection<KtProperty>,
    rootLabel: String,
    maxAnalyzedProperties: Int,
    maxGraphNodes: Int,
    projectComposables: List<KtNamedFunction> = emptyList(),
    productionSourcesOnly: Boolean = false,
    projectDiscoveryTruncated: Boolean = false,
    excludedTestFiles: Int = 0,
): FlowGraph {
    val nodes = linkedMapOf<String, FlowNode>()
    val edges = linkedSetOf<FlowEdge>()
    val fieldDependencies = linkedSetOf<FieldDependency>()
    val diagnostics = mutableListOf<String>()
    // In All Flows, Compose descendants are built once by the global topology pass. Focused
    // analysis still expands locally from each consumer for fast, rich detail.
    val deferComposeExpansionToProjectTopology = projectComposables.isNotEmpty()
    val queued = mutableSetOf<KtProperty>()
    val queue = ArrayDeque<Pair<KtProperty, Int>>()
    roots.sortedBy(::propertyId).forEach { root ->
        queue += root to 0
        queued += root
    }

    var analyzedProperties = 0
    while (queue.isNotEmpty()) {
        ProgressManager.checkCanceled()
        if (analyzedProperties++ >= maxAnalyzedProperties || nodes.size >= maxGraphNodes) {
            diagnostics += "Analysis truncated for IDE responsiveness (properties=$maxAnalyzedProperties, nodes=$maxGraphNodes). Focus a node to analyze a smaller causal area."
            break
        }
        val (property, depth) = queue.removeFirst()
        if (depth > MAX_DERIVATION_DEPTH) {
            diagnostics += "Stopped Flow traversal after depth $MAX_DERIVATION_DEPTH at ${property.name}."
            continue
        }

        val stateId = propertyId(property)
        nodes.putIfAbsent(stateId, stateNode(property))

        val references = propertyReferenceExpressions(
            property = property,
            productionSourcesOnly = productionSourcesOnly,
        )
        if (references.size > MAX_REFERENCES_PER_PROPERTY) {
            diagnostics += "${property.name}: ${references.size} references found; analyzing the first $MAX_REFERENCES_PER_PROPERTY to keep the IDE responsive."
        }

        references.asSequence().take(MAX_REFERENCES_PER_PROPERTY).forEach { reference ->
            ProgressManager.checkCanceled()

            val writer = FlowSemantics.writerFor(reference)
            if (writer != null) {
                addWriter(nodes, edges, property, stateId, writer)
                return@forEach
            }

            if (FlowSemantics.isComposeStateProperty(property)) {
                val derivedCompose = FlowSemantics.enclosingDerivedComposeStateProperty(reference)
                if (derivedCompose != null && derivedCompose != property) {
                    val derivedId = propertyId(derivedCompose)
                    nodes.putIfAbsent(derivedId, stateNode(derivedCompose))
                    addEdge(
                        edges,
                        FlowEdge(
                            from = stateId,
                            to = derivedId,
                            kind = EdgeKind.DERIVES,
                            label = "derivedStateOf",
                            source = sourceLocation(reference),
                        ),
                    )
                    if (queued.add(derivedCompose)) queue += derivedCompose to (depth + 1)
                }

                // A source read inside derivedStateOf belongs to the derived-state dependency,
                // not directly to the owning composable. The composable observes the derived state
                // when that derived property is read. Adding source -> composable here would invent
                // a direct recomposition dependency and can make unchanged derived values look as if
                // they always recompose the screen.
                val composeConsumerAdded = if (derivedCompose == null) {
                    addDirectComposeStateConsumer(
                        nodes = nodes,
                        edges = edges,
                        sourceStateId = stateId,
                        reference = reference,
                        expandChildren = !deferComposeExpansionToProjectTopology,
                    )
                } else {
                    false
                }

                val behavior = FlowSemantics.readBehavior(reference)
                if (behavior.writes.isNotEmpty() || (!composeConsumerAdded && derivedCompose == null)) {
                    val discovered = addReadBehavior(
                        nodes = nodes,
                        edges = edges,
                        sourceStateId = stateId,
                        behavior = behavior,
                    )
                    discovered.forEach { target ->
                        if (queued.add(target)) queue += target to (depth + 1)
                    }
                }
                return@forEach
            }

            val path = FlowSemantics.traceForward(reference)

            // A plain `.value` read, `first()`, helper argument, condition, etc. can still be
            // causal. Analyze the containing behavior and helper calls for writes into other
            // Flow/StateFlow properties. If none are found, keep it as a read-only observer in
            // the dedicated READS cluster instead of dropping it or calling it "reference".
            if (path.stages.isEmpty()) {
                val behavior = FlowSemantics.readBehavior(reference)
                val discovered = addReadBehavior(
                    nodes = nodes,
                    edges = edges,
                    sourceStateId = stateId,
                    behavior = behavior,
                )
                discovered.forEach { target ->
                    if (queued.add(target)) queue += target to (depth + 1)
                }
                return@forEach
            }

            val derived = path.derivedProperty
            val pathResult = addForwardPath(
                nodes = nodes,
                edges = edges,
                fieldDependencies = fieldDependencies,
                stateId = stateId,
                path = path,
                derived = derived,
            )
            val composeConsumerAdded = addComposeConsumer(
                nodes = nodes,
                edges = edges,
                path = path,
                expandChildren = !deferComposeExpansionToProjectTopology,
            )

            // Contextual inputs and side-effect write targets are first-class graph roots too.
            pathResult.discoveredProperties.forEach { input ->
                if (queued.add(input)) queue += input to (depth + 1)
            }

            if (derived != null && derived != property) {
                val derivedId = propertyId(derived)
                nodes.putIfAbsent(derivedId, stateNode(derived))
                val tailId = path.stages.lastOrNull()?.let(::stageId) ?: stateId
                addEdge(edges, FlowEdge(tailId, derivedId, EdgeKind.DERIVES))
                if (queued.add(derived)) queue += derived to (depth + 1)
            } else if (pathResult.sideEffectWriteCount == 0 && !composeConsumerAdded) {
                // Terminal collectors/readers that do not mutate tracked state are useful too,
                // but they belong in the read-only observer cluster rather than the causal graph.
                addTerminalRead(nodes, edges, stateId, path)
            }
        }
    }

    if (projectComposables.isNotEmpty()) {
        addProjectComposeTopology(
            nodes = nodes,
            edges = edges,
            diagnostics = diagnostics,
            composables = projectComposables,
            maxGraphNodes = maxGraphNodes,
        )
    }
    if (excludedTestFiles > 0) {
        diagnostics += "All Flows ignored $excludedTestFiles Kotlin files from test source roots."
    }
    if (projectDiscoveryTruncated) {
        diagnostics += "Project symbol discovery reached a safety limit; All Flows may omit some distant state or Compose nodes."
    }

    return FlowGraph(
        rootLabel = rootLabel,
        nodes = nodes.values.toList(),
        edges = edges.toList(),
        fieldDependencies = fieldDependencies.toList(),
        diagnostics = diagnostics,
    )
}



internal fun AnalysisApiFlowAnalyzer.addWriter(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    property: KtProperty,
    stateId: String,
    writer: WriterSemantics,
) {
    val writerId = writerId(writer)
    val ownerLabel = writerOwnerLabel(writer)
    val visibleWriterLabel = if (ownerLabel == writer.label) writer.label else "$ownerLabel • ${writer.label}"
    nodes.putIfAbsent(
        writerId,
        FlowNode(
            id = writerId,
            label = visibleWriterLabel,
            detail = "${writer.label} • ${sourceDetail(writer.anchor)} • ${shortCallId(writer.callId)}",
            kind = NodeKind.WRITER,
            source = sourceLocation(writer.anchor),
        ),
    )

    if (writer.changedFields.isEmpty()) {
        addEdge(edges, FlowEdge(writerId, stateId, EdgeKind.WRITES))
        return
    }

    writer.changedFields.sorted().forEach { fieldName ->
        val fieldId = fieldId(property, fieldName)
        nodes.putIfAbsent(
            fieldId,
            FlowNode(
                id = fieldId,
                label = fieldName,
                detail = "${property.name ?: "state"}.$fieldName • explicitly changed at copy/assignment site",
                kind = NodeKind.FIELD,
                source = sourceLocation(writer.anchor),
            ),
        )
        addEdge(edges, FlowEdge(writerId, fieldId, EdgeKind.WRITES_FIELD, setOf(fieldName)))
        addEdge(edges, FlowEdge(fieldId, stateId, EdgeKind.FIELD_OF, setOf(fieldName)))
    }
}



internal fun AnalysisApiFlowAnalyzer.addForwardPath(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    fieldDependencies: MutableSet<FieldDependency>,
    stateId: String,
    path: FlowUsagePath,
    derived: KtProperty?,
): ForwardPathResult {
    var previousId = stateId
    val discoveredInputs = linkedSetOf<KtProperty>()
    var sideEffectWriteCount = 0
    val parameterBindingsByStage = mutableMapOf<String, Map<String, String>>()

    path.stages.forEach { stage ->
        val id = stageId(stage)
        nodes.putIfAbsent(
            id,
            FlowNode(
                id = id,
                label = stage.label,
                detail = "${ownerContext(stage.anchor)} • ${sourceDetail(stage.anchor)} • ${shortCallId(stage.callId)}",
                kind = stage.kind,
                source = sourceLocation(stage.anchor),
            ),
        )
        addEdge(
            edges,
            FlowEdge(
                from = previousId,
                to = id,
                kind = stage.edge,
                confidence = stage.causalConfidence.takeIf { it == CausalConfidence.POSSIBLE },
            ),
        )

        val contextualIds = stage.contextualInputs.map { input ->
            discoveredInputs += input
            val inputId = propertyId(input)
            nodes.putIfAbsent(inputId, stateNode(input))
            if (inputId != previousId) {
                addEdge(
                    edges,
                    FlowEdge(
                        from = inputId,
                        to = id,
                        kind = EdgeKind.TRANSFORMS,
                        confidence = stage.causalConfidence.takeIf { it == CausalConfidence.POSSIBLE },
                    ),
                )
            }
            inputId
        }

        parameterBindingsByStage[id] = parameterBindings(
            stage = stage,
            receiverNodeId = previousId,
            contextualInputIds = contextualIds,
        )

        // A collector/onEach/map lambda can mutate another MutableStateFlow. Connect that write
        // to the running Flow stage so downstream state changes become causal graph edges rather
        // than unrelated writer nodes. Simple project helper calls are followed by FlowSemantics.
        FlowSemantics.sideEffectWrites(stage).forEach { effect ->
            sideEffectWriteCount += 1
            val targetId = propertyId(effect.target)
            nodes.putIfAbsent(targetId, stateNode(effect.target))
            addWriter(nodes, edges, effect.target, targetId, effect.writer)
            val writerNodeId = writerId(effect.writer)
            val helperLabel = effect.viaFunctions
                .mapNotNull { it.name }
                .joinToString(" → ") { "$it()" }
                .takeIf { it.isNotBlank() }
            addEdge(
                edges,
                FlowEdge(
                    from = id,
                    to = writerNodeId,
                    kind = if (stage.causalConfidence == CausalConfidence.POSSIBLE) {
                        EdgeKind.POSSIBLY_TRIGGERS_WRITE
                    } else EdgeKind.TRIGGERS_WRITE,
                    affectedFields = effect.writer.changedFields,
                    label = helperLabel,
                    confidence = stage.causalConfidence,
                ),
            )
            discoveredInputs += effect.target
        }

        previousId = id
    }

    if (derived != null) {
        materializeFieldProvenance(
            nodes = nodes,
            edges = edges,
            dependencies = fieldDependencies,
            path = path,
            derived = derived,
            parameterBindingsByStage = parameterBindingsByStage,
        )
    }
    return ForwardPathResult(
        discoveredProperties = discoveredInputs,
        sideEffectWriteCount = sideEffectWriteCount,
    )
}



internal fun AnalysisApiFlowAnalyzer.addReadBehavior(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    sourceStateId: String,
    behavior: ReadBehavior,
): Set<KtProperty> {
    if (behavior.writes.isEmpty()) {
        val id = readId(sourceStateId, behavior.anchor)
        nodes.putIfAbsent(
            id,
            FlowNode(
                id = id,
                label = behavior.label,
                detail = "${behavior.detail} • ${sourceDetail(behavior.anchor)}",
                kind = NodeKind.READ,
                source = sourceLocation(behavior.anchor),
            ),
        )
        addEdge(
            edges,
            FlowEdge(
                from = sourceStateId,
                to = id,
                kind = EdgeKind.READS,
                label = behavior.label.substringAfter(" • ", "read"),
            ),
        )
        return emptySet()
    }

    val behaviorId = behaviorId(behavior.anchor)
    nodes.putIfAbsent(
        behaviorId,
        FlowNode(
            id = behaviorId,
            label = behavior.label.substringBefore(" • "),
            detail = "${behavior.detail} • ${sourceDetail(behavior.anchor)}",
            kind = NodeKind.BEHAVIOR,
            source = sourceLocation(behavior.anchor),
        ),
    )
    addEdge(
        edges,
        FlowEdge(
            from = sourceStateId,
            to = behaviorId,
            kind = EdgeKind.POSSIBLY_TRIGGERS_WRITE,
            label = "reads → behavior",
            confidence = behavior.confidence,
        ),
    )

    val targets = linkedSetOf<KtProperty>()
    behavior.writes.forEach { effect ->
        targets += effect.target
        val targetId = propertyId(effect.target)
        nodes.putIfAbsent(targetId, stateNode(effect.target))
        addWriter(nodes, edges, effect.target, targetId, effect.writer)
        val helperLabel = effect.viaFunctions
            .mapNotNull { it.name }
            .joinToString(" → ") { "$it()" }
            .takeIf { it.isNotBlank() }
        addEdge(
            edges,
            FlowEdge(
                from = behaviorId,
                to = writerId(effect.writer),
                kind = EdgeKind.POSSIBLY_TRIGGERS_WRITE,
                affectedFields = effect.writer.changedFields,
                label = helperLabel ?: "possible write",
                confidence = CausalConfidence.POSSIBLE,
            ),
        )
    }
    return targets
}



internal fun AnalysisApiFlowAnalyzer.addTerminalRead(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    sourceStateId: String,
    path: FlowUsagePath,
) {
    val last = path.stages.lastOrNull() ?: return
    val id = readId(sourceStateId, last.anchor)
    val summary = path.stages.joinToString(" → ") { it.label }
    nodes.putIfAbsent(
        id,
        FlowNode(
            id = id,
            label = "${ownerContext(last.anchor)} • ${last.label}",
            detail = "Read-only Flow observation • $summary • ${sourceDetail(last.anchor)}",
            kind = NodeKind.READ,
            source = sourceLocation(last.anchor),
        ),
    )
    addEdge(
        edges,
        FlowEdge(
            from = sourceStateId,
            to = id,
            kind = EdgeKind.READS,
            label = summary,
        ),
    )
}

/**
 * Maps transform lambda parameters to graph inputs.
 *
 *   flow.map { value -> ... }            value -> receiver
 *   flow.combine(other) { a, b -> ... } a -> receiver, b -> other
 *   combine(a, b) { x, y -> ... }       x -> a, y -> b
 *
 * If an overload's parameter shape does not match these rules, we deliberately return no
 * provenance rather than inventing a relationship.
 */



internal fun AnalysisApiFlowAnalyzer.parameterBindings(
    stage: FlowStage,
    receiverNodeId: String,
    contextualInputIds: List<String>,
): Map<String, String> {
    val names = stage.lambdaParameters
    if (names.isEmpty()) return emptyMap()

    return if (stage.hasReceiverInput) {
        if (names.size != contextualInputIds.size + 1) return emptyMap()
        buildMap {
            put(names.first(), receiverNodeId)
            names.drop(1).zip(contextualInputIds).forEach { (name, id) -> put(name, id) }
        }
    } else {
        if (names.size != contextualInputIds.size) return emptyMap()
        names.zip(contextualInputIds).toMap()
    }
}



internal fun AnalysisApiFlowAnalyzer.materializeFieldProvenance(
    nodes: MutableMap<String, FlowNode>,
    edges: MutableSet<FlowEdge>,
    dependencies: MutableSet<FieldDependency>,
    path: FlowUsagePath,
    derived: KtProperty,
    parameterBindingsByStage: Map<String, Map<String, String>>,
) {
    // Use only the last understood payload transform, and only when no later unknown payload
    // transform can invalidate its output-field mapping. Pass-through operators like stateIn,
    // shareIn, filter, distinctUntilChanged, debounce, etc. are safe after it.
    val candidateIndex = path.stages.indexOfLast { it.fieldEffects.isNotEmpty() }
    if (candidateIndex < 0) return
    if (path.stages.drop(candidateIndex + 1).any { it.payloadTransforming }) return

    val stage = path.stages[candidateIndex]
    val operatorId = stageId(stage)
    val bindings = parameterBindingsByStage[operatorId].orEmpty()
    if (bindings.isEmpty()) return

    val outputStateId = propertyId(derived)
    nodes.putIfAbsent(outputStateId, stateNode(derived))

    stage.fieldEffects.forEach { effect ->
        val outputFieldLabel = if (effect.outputField == "\$value") "value" else effect.outputField
        val outputFieldId = fieldId(derived, effect.outputField)
        nodes.putIfAbsent(
            outputFieldId,
            FlowNode(
                id = outputFieldId,
                label = outputFieldLabel,
                detail = if (effect.outputField == "\$value") {
                    "${derived.name ?: "derived Flow"} value • inferred from ${stage.label} transform"
                } else {
                    "${derived.name ?: "derived state"}.${effect.outputField} • inferred from ${stage.label} transform"
                },
                kind = NodeKind.FIELD,
                source = sourceLocation(stage.anchor),
            ),
        )
        addEdge(edges, FlowEdge(operatorId, outputFieldId, EdgeKind.PRODUCES_FIELD))
        addEdge(edges, FlowEdge(outputFieldId, outputStateId, EdgeKind.FIELD_OF))

        effect.sourceUses.forEach { use ->
            val sourceNodeId = bindings[use.parameterName] ?: return@forEach
            dependencies += FieldDependency(
                sourceNodeId = sourceNodeId,
                sourceField = use.sourceField,
                viaOperatorId = operatorId,
                outputFieldId = outputFieldId,
                outputField = effect.outputField,
                outputStateId = outputStateId,
                confidence = use.confidence,
            )
            annotateAffectedField(edges, sourceNodeId, operatorId, effect.outputField)
        }
    }
}



internal fun AnalysisApiFlowAnalyzer.annotateAffectedField(
    edges: MutableSet<FlowEdge>,
    sourceNodeId: String,
    operatorId: String,
    outputField: String,
) {
    val matching = edges.filter { it.from == sourceNodeId && it.to == operatorId }
    if (matching.isEmpty()) {
        addEdge(edges, FlowEdge(sourceNodeId, operatorId, EdgeKind.TRANSFORMS, setOf(outputField)))
        return
    }
    matching.forEach { old ->
        edges.remove(old)
        edges.add(old.copy(affectedFields = old.affectedFields + outputField))
    }
}

/** Merges annotations when the same semantic edge is discovered from multiple reference walks. */



internal fun AnalysisApiFlowAnalyzer.addEdge(edges: MutableSet<FlowEdge>, edge: FlowEdge) {
    val existing = edges.firstOrNull { existing ->
        existing.from == edge.from &&
            existing.to == edge.to &&
            existing.kind == edge.kind &&
            (
                edge.kind != EdgeKind.COMPOSES ||
                    (existing.source?.file?.path == edge.source?.file?.path &&
                        existing.source?.offset == edge.source?.offset)
                )
    }
    if (existing == null) {
        edges += edge
        return
    }
    val mergedLabel = listOfNotNull(existing.label, edge.label)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" | ")
        .takeIf { it.isNotBlank() }
    val merged = existing.copy(
        affectedFields = existing.affectedFields + edge.affectedFields,
        label = mergedLabel,
        confidence = when {
            existing.confidence == CausalConfidence.POSSIBLE && edge.confidence == CausalConfidence.POSSIBLE ->
                CausalConfidence.POSSIBLE
            existing.confidence == CausalConfidence.DEFINITE || edge.confidence == CausalConfidence.DEFINITE ->
                CausalConfidence.DEFINITE
            // null is the normal definite structural case. If the same edge is also discovered
            // through a possible route, keep the definite evidence rather than downgrading it.
            existing.confidence == null || edge.confidence == null -> null
            else -> existing.confidence
        },
        source = existing.source ?: edge.source,
    )
    if (merged != existing) {
        edges.remove(existing)
        edges.add(merged)
    }
}



internal fun AnalysisApiFlowAnalyzer.stateNode(property: KtProperty): FlowNode {
    val type = property.typeReference?.text ?: FlowSemantics.flowTypeName(property)
    val ownerClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java, false)
    val owner = ownerClass?.name
    val ownerPrefix = owner?.let { "$it." } ?: ""
    val packageName = property.containingKtFile.packageFqName.asString()
    val simpleKey = "$ownerPrefix${property.name ?: "state"}"
    // Local variables have no JVM field key, so do not pretend they can be matched by the
    // field-based runtime registration path. They are still first-class static graph roots and
    // can expose their collectAsState* -> Compose subtree correctly.
    val composeState = FlowSemantics.isComposeStateProperty(property)
    val delegatedComposeState = composeState && property.delegateExpression != null
    val composeFactory = if (composeState) FlowSemantics.composeStateFactoryAnchor(property) else null
    val composeFactoryLine = composeFactory?.let(::sourceLocation)?.line?.plus(1)
    val composeFactoryName = if (composeState) FlowSemantics.composeStateFactoryName(property) else null
    val composeDeclarationLine = sourceLocation(property)?.line?.plus(1)
    val composeSourceFile = property.containingKtFile.name
    val composeSourceFunction = PsiTreeUtil.getParentOfType(property, KtNamedFunction::class.java, false)?.name
        ?: "<top-level>"
    val runtimeKey = when {
        composeState && property.isLocal && delegatedComposeState && property.name != null &&
            composeDeclarationLine != null ->
            "@composestate-decl|$composeSourceFile|$composeSourceFunction|${property.name}|$composeDeclarationLine"
        composeState && (property.isLocal || delegatedComposeState) &&
            composeFactoryLine != null && composeFactoryName != null ->
            "@composestate-source2|$composeSourceFile|$composeFactoryLine|$composeFactoryName|$composeSourceFunction"
        property.isLocal || delegatedComposeState -> null
        else -> if (packageName.isBlank()) simpleKey else "$packageName.$simpleKey"
    }
    val runtimeAliases = if (composeState && property.isLocal && delegatedComposeState && property.name != null) {
        delegatedComposeStateAccessKeys(property, composeSourceFile, composeSourceFunction)
    } else {
        emptySet()
    }
    val localContext = if (property.isLocal) "local in ${ownerContext(property)} • " else ""
    val stateFlavor = if (composeState) "Compose state • " else ""
    val viewModel = ownerClass?.takeIf(::looksLikeViewModel)
    return FlowNode(
        id = propertyId(property),
        label = property.name ?: "<anonymous state>",
        detail = "$type • $stateFlavor$localContext$simpleKey • ${sourceDetail(property)}",
        kind = NodeKind.STATE,
        source = sourceLocation(property),
        runtimeKey = runtimeKey,
        groupKey = viewModel?.let { "vm:${it.containingKtFile.virtualFile.path}:${it.textOffset}" },
        groupLabel = viewModel?.name,
        groupKind = viewModel?.let { NodeGroupKind.VIEW_MODEL },
        runtimeAliases = runtimeAliases,
    )
}

/**
 * Runtime matching for delegated local Compose State is deliberately based on *source access*
 * sites, not just file/function/property name. Kotlin/Compose inlining can copy dependency
 * bytecode into the caller and preserve a generic local name such as `state`; those synthetic
 * delegates must never be attributed to the user's source declaration.
 */


