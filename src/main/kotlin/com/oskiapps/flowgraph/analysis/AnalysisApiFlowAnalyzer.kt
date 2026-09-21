package com.oskiapps.flowgraph.analysis

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
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
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
                    diagnostics = listOf("${root.name} does not resolve to Flow/StateFlow/SharedFlow/Compose State."),
                )
            }

            buildGraph(root)
        }

    /**
     * Builds one project-wide graph containing every Kotlin Flow/StateFlow/SharedFlow/Compose State property
     * under project content roots, including local properties declared inside functions/composables.
     * Disconnected parts are intentionally kept in the same FlowGraph; the canvas lays those
     * connected components out as separate visual clusters.
     */
    @OptIn(KaExperimentalApi::class)
    fun analyzeAllProjectFlows(): FlowGraph = ReadAction.compute<FlowGraph, RuntimeException> {
        val roots = discoverProjectFlowProperties()
        if (roots.isEmpty()) {
            return@compute FlowGraph(
                rootLabel = "All project flows",
                nodes = emptyList(),
                edges = emptyList(),
                diagnostics = listOf("No Kotlin Flow/StateFlow/SharedFlow/Compose State properties were found in project sources."),
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
                // Local StateFlow/SharedFlow properties matter for Compose. A flow created inside
                // a composable/helper can be the direct source of collectAsState*, and skipping it
                // prevents the owning/sub-composable branch from ever entering the project graph.
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


    private fun propertyReferenceExpressions(property: KtProperty): List<KtNameReferenceExpression> {
        // Local/delegated Compose state is not a globally indexed symbol. In practice a project-wide
        // ReferencesSearch can return zero references for `var state by remember { mutableStateOf(...) }`,
        // leaving the state node orphaned even though the composable reads/writes it. Resolve local
        // name references directly inside the lexical owner instead.
        if (property.isLocal) {
            val owner: PsiElement = PsiTreeUtil.getParentOfType(property, KtNamedFunction::class.java, false)
                ?: PsiTreeUtil.getParentOfType(property, KtLambdaExpression::class.java, false)
                ?: property.containingFile
            val expectedName = property.name
            return PsiTreeUtil.collectElementsOfType(owner, KtNameReferenceExpression::class.java)
                .asSequence()
                .filter { expectedName == null || it.getReferencedName() == expectedName }
                .filter { FlowSemantics.resolvesTo(it, property) }
                .toList()
        }
        return ReferencesSearch.search(property, GlobalSearchScope.projectScope(project))
            .findAll()
            .mapNotNull { it.element as? KtNameReferenceExpression }
            .filter { FlowSemantics.resolvesTo(it, property) }
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

            val references = propertyReferenceExpressions(property)
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

                    val composeConsumerAdded = addDirectComposeStateConsumer(
                        nodes = nodes,
                        edges = edges,
                        sourceStateId = stateId,
                        reference = reference,
                    )

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
                val composeConsumerAdded = addComposeConsumer(nodes, edges, path)

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
    private fun delegatedComposeStateAccessKeys(
        property: KtProperty,
        sourceFile: String,
        sourceFunction: String,
    ): Set<String> {
        val propertyName = property.name ?: return emptySet()
        val scope = PsiTreeUtil.getParentOfType(property, KtNamedFunction::class.java, false)
            ?: property.containingKtFile
        return PsiTreeUtil.findChildrenOfType(scope, KtNameReferenceExpression::class.java)
            .asSequence()
            .filter { it.getReferencedName() == propertyName }
            .filter { reference -> runCatching { reference.mainReference.resolve() == property }.getOrDefault(false) }
            .mapNotNull { reference ->
                val line = sourceLocation(reference)?.line?.plus(1) ?: return@mapNotNull null
                val accessKind = delegatedAccessKind(reference)
                "@composestate-access|$sourceFile|$sourceFunction|$propertyName|$line|$accessKind"
            }
            .toSet()
    }

    private fun delegatedAccessKind(reference: KtNameReferenceExpression): String {
        val parent = reference.parent
        if (parent is org.jetbrains.kotlin.psi.KtBinaryExpression && parent.left == reference) {
            val op = parent.operationReference.text
            if (op in setOf("=", "+=", "-=", "*=", "/=", "%=")) return "write"
        }
        if (parent is org.jetbrains.kotlin.psi.KtUnaryExpression) {
            val op = parent.operationReference.text
            if (op == "++" || op == "--") return "write"
        }
        return "read"
    }

    /**
     * Extends a Flow path into Compose when the terminal collector is collectAsState*.
     * The collection call remains a first-class node; the owning @Composable and source child
     * composables are appended so the detail graph can show:
     *
     * MutableStateFlow -> StateFlow -> collectAsStateWithLifecycle -> Screen -> ChildComposable
     */
    private data class ComposeOwner(
        val node: FlowNode,
        val body: PsiElement,
        val function: KtNamedFunction?,
    )

    private fun addDirectComposeStateConsumer(
        nodes: MutableMap<String, FlowNode>,
        edges: MutableSet<FlowEdge>,
        sourceStateId: String,
        reference: KtNameReferenceExpression,
    ): Boolean {
        val root = enclosingComposeOwner(reference) ?: return false
        val rootId = root.node.id
        nodes.putIfAbsent(rootId, root.node)
        addEdge(
            edges,
            FlowEdge(
                from = sourceStateId,
                to = rootId,
                kind = EdgeKind.UPDATES_COMPOSE,
                label = "Compose state → recompose",
                source = sourceLocation(reference),
            ),
        )
        addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = root.body,
            functionId = rootId,
            currentFunction = root.function,
            depth = 0,
            visited = root.function?.let { linkedSetOf(it) } ?: linkedSetOf(),
        )
        return true
    }

    private fun addComposeConsumer(
        nodes: MutableMap<String, FlowNode>,
        edges: MutableSet<FlowEdge>,
        path: FlowUsagePath,
    ): Boolean {
        val collector = path.stages.lastOrNull() ?: return false
        if (collector.kind != NodeKind.COLLECTOR) return false
        val collectorName = collector.label.removeSuffix("*")
        if (collectorName != "collectAsState" && collectorName != "collectAsStateWithLifecycle") return false

        // A collectAsState* call can live either in a named @Composable or directly in a
        // Compose host lambda such as ComposeView.setContent { ... } / Activity.setContent { ... }.
        // Treat both as real Compose consumers so ordinary Fragment/Activity Compose roots are not
        // silently omitted from the graph.
        val root = enclosingComposeOwner(collector.anchor) ?: return false
        val rootId = root.node.id
        nodes.putIfAbsent(rootId, root.node)
        addEdge(
            edges,
            FlowEdge(
                from = stageId(collector),
                to = rootId,
                kind = EdgeKind.UPDATES_COMPOSE,
                label = "recompose",
                source = sourceLocation(collector.anchor),
            ),
        )

        addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = root.body,
            functionId = rootId,
            currentFunction = root.function,
            depth = 0,
            visited = root.function?.let { linkedSetOf(it) } ?: linkedSetOf(),
        )
        return true
    }

    private fun addComposableChildren(
        nodes: MutableMap<String, FlowNode>,
        edges: MutableSet<FlowEdge>,
        function: KtNamedFunction,
        functionId: String,
        depth: Int,
        visited: MutableSet<KtNamedFunction>,
    ) {
        val body = function.bodyExpression ?: return
        addComposableChildrenFromBody(
            nodes = nodes,
            edges = edges,
            body = body,
            functionId = functionId,
            currentFunction = function,
            depth = depth,
            visited = visited,
        )
    }

    private fun addComposableChildrenFromBody(
        nodes: MutableMap<String, FlowNode>,
        edges: MutableSet<FlowEdge>,
        body: PsiElement,
        functionId: String,
        currentFunction: KtNamedFunction?,
        depth: Int,
        visited: MutableSet<KtNamedFunction>,
    ) {
        if (depth >= MAX_COMPOSE_CALL_DEPTH || nodes.count { it.value.kind == NodeKind.COMPOSABLE } >= MAX_COMPOSE_NODES) return
        PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java).forEach { call ->
            ProgressManager.checkCanceled()
            val target = FlowSemantics.sourceFunction(call) ?: return@forEach
            if (!FlowSemantics.isComposableFunction(target) || target == currentFunction) return@forEach
            val childId = composeId(target)
            nodes.putIfAbsent(childId, composeNode(target))
            addEdge(
                edges,
                FlowEdge(
                    from = functionId,
                    to = childId,
                    kind = EdgeKind.COMPOSES,
                    label = "composes",
                    source = sourceLocation(call),
                ),
            )
            if (visited.add(target)) {
                addComposableChildren(nodes, edges, target, childId, depth + 1, visited)
            }
        }
    }

    private fun enclosingComposeOwner(element: PsiElement): ComposeOwner? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtNamedFunction -> {
                    if (FlowSemantics.isComposableFunction(current)) {
                        val body = current.bodyExpression ?: current
                        return ComposeOwner(composeNode(current), body, current)
                    }
                }

                is KtLambdaExpression -> {
                    val hostCall = composeHostCall(current)
                    if (hostCall != null) {
                        val body = current.bodyExpression ?: current
                        return ComposeOwner(composeHostNode(current, hostCall), body, null)
                    }
                }
            }
            current = current.parent
        }
        return null
    }

    /** Returns the Compose root call that owns [lambda], currently the standard setContent APIs. */
    private fun composeHostCall(lambda: KtLambdaExpression): KtCallExpression? {
        // For both trailing lambdas (`setContent { ... }`) and parenthesized lambda arguments,
        // the first call expression above the lambda is the owning call. Keeping this PSI-shape
        // agnostic avoids depending on whether the parser used KtLambdaArgument or KtValueArgument.
        val call = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java, true) ?: return null
        return call.takeIf { it.calleeExpression?.text == "setContent" }
    }

    private fun composeHostNode(lambda: KtLambdaExpression, hostCall: KtCallExpression): FlowNode {
        val file = lambda.containingKtFile
        val ownerFunction = PsiTreeUtil.getParentOfType(lambda, KtNamedFunction::class.java, true)
        val ownerLabel = ownerFunction?.name?.let { "$it()" } ?: "source"
        val id = "compose-host:${file.virtualFile.path}:${lambda.textOffset}"
        return FlowNode(
            id = id,
            label = "setContent",
            detail = "Compose content lambda • $ownerLabel • ${sourceDetail(hostCall)} • live recomposition capable",
            kind = NodeKind.COMPOSABLE,
            source = sourceLocation(hostCall),
            runtimeKey = null,
            groupKey = id,
            groupLabel = "setContent",
            groupKind = NodeGroupKind.COMPOSE,
        )
    }

    private fun composeId(function: KtNamedFunction): String =
        "compose:${function.containingKtFile.virtualFile.path}:${function.textOffset}"

    private fun composeRuntimeKey(function: KtNamedFunction): String {
        val file = function.containingKtFile
        val pkg = file.packageFqName.asString()
        return "@compose|$pkg|${file.name}|${function.name ?: "<anonymous>"}"
    }

    private fun composeNode(function: KtNamedFunction): FlowNode {
        val file = function.containingKtFile
        val name = function.name ?: "<anonymous composable>"
        return FlowNode(
            id = composeId(function),
            label = name,
            detail = "@Composable • ${sourceDetail(function)} • live composition inspectable",
            kind = NodeKind.COMPOSABLE,
            source = sourceLocation(function),
            runtimeKey = composeRuntimeKey(function),
            groupKey = "compose:${file.virtualFile.path}:${function.textOffset}",
            groupLabel = name,
            groupKind = NodeGroupKind.COMPOSE,
        )
    }


    private fun looksLikeViewModel(owner: KtClassOrObject): Boolean {
        val name = owner.name.orEmpty()
        if (name.endsWith("ViewModel")) return true
        return owner.superTypeListEntries.any { entry ->
            val text = entry.text
            text == "ViewModel" || text.startsWith("ViewModel(") || text.endsWith(".ViewModel") || text.contains("ViewModel(")
        }
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
        const val MAX_COMPOSE_CALL_DEPTH = 4
        const val MAX_COMPOSE_NODES = 120
    }
}
