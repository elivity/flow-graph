package dev.flowgraph.analysis

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
import dev.flowgraph.model.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import java.util.ArrayDeque

class AnalysisApiFlowAnalyzer(private val project: Project) {

    @Suppress("UNUSED_PARAMETER")
    @OptIn(KaExperimentalApi::class)
    fun analyzeFrom(element: PsiElement, documentForRoot: Document? = null): FlowGraph =
        ReadAction.compute<FlowGraph, RuntimeException> {
            val root = resolveProperty(element)
                ?: return@compute FlowGraph(
                    rootLabel = "No Flow",
                    nodes = emptyList(),
                    edges = emptyList(),
                    diagnostics = listOf("Caret does not resolve to a Kotlin property."),
                )

            if (!FlowSemantics.isFlowProperty(root)) {
                return@compute FlowGraph(
                    rootLabel = root.name ?: "property",
                    nodes = emptyList(),
                    edges = emptyList(),
                    diagnostics = listOf("${root.name} does not resolve to Flow/StateFlow/SharedFlow."),
                )
            }

            buildGraph(root)
        }

    /**
     * Builds one project-wide graph containing every non-local Kotlin Flow/StateFlow/SharedFlow
     * property under project content roots. Disconnected parts are intentionally kept in the same
     * FlowGraph; the canvas lays those connected components out as separate visual clusters.
     */
    @OptIn(KaExperimentalApi::class)
    fun analyzeAllProjectFlows(): FlowGraph = ReadAction.compute<FlowGraph, RuntimeException> {
        val roots = discoverProjectFlowProperties()
        if (roots.isEmpty()) {
            return@compute FlowGraph(
                rootLabel = "All project flows",
                nodes = emptyList(),
                edges = emptyList(),
                diagnostics = listOf("No non-local Kotlin Flow/StateFlow/SharedFlow properties were found in project sources."),
            )
        }

        val graph = buildGraph(
            roots = roots,
            rootLabel = "All project flows",
            maxAnalyzedProperties = MAX_ALL_ANALYZED_PROPERTIES,
            maxGraphNodes = MAX_ALL_GRAPH_NODES,
        )
        // Warm the expensive state-to-state projection while still on the background analysis job.
        // The default All flows view can then render immediately on the EDT instead of doing an
        // O(states × paths) traversal during Swing painting/layout.
        graph.stateTransitions()
        graph
    }

    @OptIn(KaExperimentalApi::class)
    private fun discoverProjectFlowProperties(): List<KtProperty> {
        val psiManager = PsiManager.getInstance(project)
        val files = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            ProgressManager.checkCanceled()
            if (!file.isDirectory && file.extension == "kt") files += file
            true
        }

        val roots = mutableListOf<KtProperty>()
        var propertiesInspected = 0
        for (file in files.sortedBy { it.path }) {
            ProgressManager.checkCanceled()
            val ktFile = psiManager.findFile(file) as? KtFile ?: continue
            for (property in PsiTreeUtil.findChildrenOfType(ktFile, KtProperty::class.java)) {
                ProgressManager.checkCanceled()
                if (property.isLocal) continue
                propertiesInspected++
                if (propertiesInspected > MAX_PROJECT_PROPERTIES_TO_INSPECT) {
                    return roots.distinctBy(::propertyId)
                }
                if (FlowSemantics.isFlowProperty(property)) roots += property
            }
        }
        return roots.distinctBy(::propertyId)
    }

    @OptIn(KaExperimentalApi::class)
    private fun resolveProperty(element: PsiElement): KtProperty? {
        PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)?.let { property ->
            if (property.nameIdentifier?.textRange?.contains(element.textRange) == true) return property
        }

        val ref = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            ?: element as? KtNameReferenceExpression
            ?: return null

        return analyze(ref) {
            (ref.mainReference.resolveToSymbol() as? KaPropertySymbol)?.psi as? KtProperty
        }
    }

    private fun buildGraph(root: KtProperty): FlowGraph = buildGraph(
        roots = listOf(root),
        rootLabel = root.name ?: "Flow",
        maxAnalyzedProperties = MAX_ANALYZED_PROPERTIES,
        maxGraphNodes = MAX_GRAPH_NODES,
    )

    private fun buildGraph(
        roots: Collection<KtProperty>,
        rootLabel: String,
        maxAnalyzedProperties: Int,
        maxGraphNodes: Int,
    ): FlowGraph {
        val nodes = linkedMapOf<String, FlowNode>()
        val edges = linkedSetOf<FlowEdge>()
        val fieldDependencies = linkedSetOf<FieldDependency>()
        val diagnostics = mutableListOf<String>()
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

            val references = ReferencesSearch.search(
                property,
                GlobalSearchScope.projectScope(project),
            ).findAll()
            if (references.size > MAX_REFERENCES_PER_PROPERTY) {
                diagnostics += "${property.name}: ${references.size} references found; analyzing the first $MAX_REFERENCES_PER_PROPERTY to keep the IDE responsive."
            }

            references.asSequence().take(MAX_REFERENCES_PER_PROPERTY).forEach { psiRef ->
                ProgressManager.checkCanceled()
                val reference = psiRef.element as? KtNameReferenceExpression ?: return@forEach
                if (!FlowSemantics.resolvesTo(reference, property)) return@forEach

                val writer = FlowSemantics.writerFor(reference)
                if (writer != null) {
                    addWriter(nodes, edges, property, stateId, writer)
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
                } else if (pathResult.sideEffectWriteCount == 0) {
                    // Terminal collectors/readers that do not mutate tracked state are useful too,
                    // but they belong in the read-only observer cluster rather than the causal graph.
                    addTerminalRead(nodes, edges, stateId, path)
                }
            }
        }

        return FlowGraph(
            rootLabel = rootLabel,
            nodes = nodes.values.toList(),
            edges = edges.toList(),
            fieldDependencies = fieldDependencies.toList(),
            diagnostics = diagnostics,
        )
    }

    private fun addWriter(
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

    private data class ForwardPathResult(
        val discoveredProperties: Set<KtProperty>,
        val sideEffectWriteCount: Int,
    )

    /**
     * Adds the normal Flow chain and, when a transform lambda is understood, field provenance.
     * Returns other Flow properties discovered as operator inputs so the outer traversal can scan them.
     */
    private fun addForwardPath(
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
            addEdge(edges, FlowEdge(previousId, id, stage.edge))

            val contextualIds = stage.contextualInputs.map { input ->
                discoveredInputs += input
                val inputId = propertyId(input)
                nodes.putIfAbsent(inputId, stateNode(input))
                if (inputId != previousId) {
                    addEdge(edges, FlowEdge(inputId, id, EdgeKind.TRANSFORMS))
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

    private fun addReadBehavior(
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

    private fun addTerminalRead(
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
    private fun parameterBindings(
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

    private fun materializeFieldProvenance(
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

    private fun annotateAffectedField(
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
    private fun addEdge(edges: MutableSet<FlowEdge>, edge: FlowEdge) {
        val existing = edges.firstOrNull {
            it.from == edge.from && it.to == edge.to && it.kind == edge.kind
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
            confidence = if (existing.confidence == CausalConfidence.POSSIBLE || edge.confidence == CausalConfidence.POSSIBLE) {
                CausalConfidence.POSSIBLE
            } else existing.confidence ?: edge.confidence,
            source = existing.source ?: edge.source,
        )
        if (merged != existing) {
            edges.remove(existing)
            edges.add(merged)
        }
    }

    private fun propertyId(property: KtProperty): String =
        "state:${property.containingKtFile.virtualFile.path}:${property.textOffset}"

    private fun fieldId(property: KtProperty, fieldName: String): String =
        "field:${property.containingKtFile.virtualFile.path}:${property.textOffset}:$fieldName"

    private fun stageId(stage: FlowStage): String =
        "call:${stage.anchor.containingKtFile.virtualFile.path}:${stage.anchor.textOffset}:${stage.callId}"

    private fun writerId(writer: WriterSemantics): String {
        val file = writer.anchor.containingFile?.virtualFile?.path ?: "<unknown>"
        return "writer:$file:${writer.anchor.textOffset}:${writer.callId}"
    }

    private fun readId(sourceStateId: String, anchor: PsiElement): String {
        val file = anchor.containingFile?.virtualFile?.path ?: "<unknown>"
        return "read:$sourceStateId:$file:${anchor.textOffset}"
    }

    private fun behaviorId(anchor: PsiElement): String {
        val file = anchor.containingFile?.virtualFile?.path ?: "<unknown>"
        return "behavior:$file:${anchor.textOffset}"
    }

    private fun stateNode(property: KtProperty): FlowNode {
        val type = property.typeReference?.text ?: FlowSemantics.flowTypeName(property)
        val owner = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java, false)?.name
        val ownerPrefix = owner?.let { "$it." } ?: ""
        val packageName = property.containingKtFile.packageFqName.asString()
        val simpleKey = "$ownerPrefix${property.name ?: "state"}"
        val runtimeKey = if (packageName.isBlank()) simpleKey else "$packageName.$simpleKey"
        return FlowNode(
            id = propertyId(property),
            label = property.name ?: "<anonymous state>",
            detail = "$type • $simpleKey • ${sourceDetail(property)}",
            kind = NodeKind.STATE,
            source = sourceLocation(property),
            runtimeKey = runtimeKey,
        )
    }

    private fun writerOwnerLabel(writer: WriterSemantics): String {
        val function = PsiTreeUtil.getParentOfType(writer.anchor, KtNamedFunction::class.java, false)?.name
        return function?.let { "$it()" } ?: writer.label
    }

    private fun ownerContext(element: PsiElement): String {
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)?.name
        if (function != null) return "$function()"
        val property = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)?.name
        if (property != null) return property
        return "top-level"
    }

    private fun shortCallId(callId: String): String =
        callId.removePrefix("kotlinx.coroutines.flow.")
            .removePrefix("androidx.compose.runtime.")
            .removePrefix("androidx.lifecycle.compose.")

    private fun sourceDetail(element: PsiElement): String {
        val source = sourceLocation(element) ?: return "<unknown>"
        return "${source.file.name}:${source.line + 1}"
    }

    private fun sourceLocation(element: PsiElement): SourceLocation? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            ?: return SourceLocation(file, element.textOffset, 0)
        return SourceLocation(file, element.textOffset, document.getLineNumber(element.textOffset))
    }

    private companion object {
        const val MAX_DERIVATION_DEPTH = 12
        const val MAX_ANALYZED_PROPERTIES = 250
        const val MAX_GRAPH_NODES = 1_500
        const val MAX_REFERENCES_PER_PROPERTY = 750
        const val MAX_PROJECT_PROPERTIES_TO_INSPECT = 12_000
        const val MAX_ALL_ANALYZED_PROPERTIES = 1_500
        const val MAX_ALL_GRAPH_NODES = 8_000
    }
}
