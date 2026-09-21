package com.oskiapps.flowgraph.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.oskiapps.flowgraph.model.CausalConfidence
import com.oskiapps.flowgraph.model.EdgeKind
import com.oskiapps.flowgraph.model.NodeKind
import com.oskiapps.flowgraph.model.ProvenanceConfidence
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.*

internal data class StageSourceUse(
    val parameterName: String,
    val sourceField: String?,
    val confidence: ProvenanceConfidence,
)

internal data class StageFieldEffect(
    /** `$value` means the whole scalar value produced by the derived Flow. */
    val outputField: String,
    val sourceUses: List<StageSourceUse>,
)

internal data class FlowStage(
    val kind: NodeKind,
    val edge: EdgeKind,
    val label: String,
    val callId: String,
    val anchor: KtCallExpression,
    val contextualInputs: List<KtProperty> = emptyList(),
    /** Transform lambda parameter names in declaration order. Empty means no usable transform lambda. */
    val lambdaParameters: List<String> = emptyList(),
    val fieldEffects: List<StageFieldEffect> = emptyList(),
    /** True for `receiver.map {}` / `receiver.combine(other) {}`, false for top-level combine(a,b). */
    val hasReceiverInput: Boolean = false,
    /** True when the operator can change the payload type/value rather than merely pass/filter it. */
    val payloadTransforming: Boolean = false,
    /** Unknown/custom project calls are surfaced conservatively as POSSIBLE causality. */
    val causalConfidence: CausalConfidence = CausalConfidence.DEFINITE,
)

internal data class FlowUsagePath(
    val stages: List<FlowStage>,
    val derivedProperty: KtProperty?,
)

internal data class WriterSemantics(
    val label: String,
    val callId: String,
    val anchor: PsiElement,
    val changedFields: Set<String>,
)

internal data class SideEffectWrite(
    val target: KtProperty,
    val writer: WriterSemantics,
    /** Project helper functions traversed between the Flow lambda and the actual state write. */
    val viaFunctions: List<KtNamedFunction> = emptyList(),
)

internal data class ReadBehavior(
    val anchor: PsiElement,
    val label: String,
    val detail: String,
    val writes: List<SideEffectWrite>,
    val confidence: CausalConfidence,
)

/**
 * Semantic recognizer for Kotlin Flow code.
 *
 * PSI provides expression shape, while K2 Analysis API decides identity:
 * - property references are re-resolved to the declaration being graphed;
 * - Flow/StateFlow types are checked from KaType;
 * - operators/writers/collectors are accepted only after resolving their callableId;
 * - writer field extraction accepts only resolved Kotlin expressions;
 * - field provenance re-resolves explicit lambda parameters before attributing a field read.
 *
 * No KaSymbol is cached outside an analyze { } lifetime.
 */
@OptIn(KaExperimentalApi::class)
internal object FlowSemantics {
    private val coroutineFlowIds = setOf(
        ClassId.fromString("kotlinx/coroutines/flow/StateFlow"),
        ClassId.fromString("kotlinx/coroutines/flow/MutableStateFlow"),
        ClassId.fromString("kotlinx/coroutines/flow/SharedFlow"),
        ClassId.fromString("kotlinx/coroutines/flow/MutableSharedFlow"),
        ClassId.fromString("kotlinx/coroutines/flow/Flow"),
    )
    private val composeStateIds = setOf(
        ClassId.fromString("androidx/compose/runtime/State"),
        ClassId.fromString("androidx/compose/runtime/MutableState"),
        ClassId.fromString("androidx/compose/runtime/IntState"),
        ClassId.fromString("androidx/compose/runtime/MutableIntState"),
        ClassId.fromString("androidx/compose/runtime/LongState"),
        ClassId.fromString("androidx/compose/runtime/MutableLongState"),
        ClassId.fromString("androidx/compose/runtime/FloatState"),
        ClassId.fromString("androidx/compose/runtime/MutableFloatState"),
        ClassId.fromString("androidx/compose/runtime/DoubleState"),
        ClassId.fromString("androidx/compose/runtime/MutableDoubleState"),
    )
    private val composeStateFactoryNames = setOf(
        "mutableStateOf", "derivedStateOf", "rememberUpdatedState", "produceState",
        "mutableIntStateOf", "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf",
    )

    private val writerNames = setOf("update", "getAndUpdate", "updateAndGet", "emit", "tryEmit")
    private val exposureNames = setOf("asStateFlow", "asSharedFlow")
    private val collectorNames = setOf(
        "collect", "collectLatest", "launchIn",
        "collectAsState", "collectAsStateWithLifecycle",
        "first", "firstOrNull", "single", "singleOrNull", "last", "lastOrNull",
        "toList", "toSet", "count", "reduce", "fold", "produceIn",
    )
    private val operatorNames = setOf(
        "map", "mapLatest", "mapNotNull", "filter", "filterNot", "filterNotNull", "filterIsInstance",
        "transform", "transformLatest", "transformWhile",
        "combine", "combineTransform", "flatMapLatest", "flatMapConcat", "flatMapMerge",
        "flattenLatest", "flattenConcat", "flattenMerge", "distinctUntilChanged",
        "debounce", "sample", "catch", "retry", "retryWhen", "onEach", "onStart", "onCompletion",
        "stateIn", "shareIn", "scan", "runningFold", "runningReduce", "withIndex",
        "zip", "merge", "flowOn", "buffer", "conflate", "cancellable",
        "take", "takeWhile", "drop", "dropWhile",
    )

    private val payloadTransformingNames = setOf(
        "map", "mapLatest", "mapNotNull", "transform", "transformLatest", "transformWhile",
        "combine", "combineTransform", "flatMapLatest", "flatMapConcat", "flatMapMerge",
        "flattenLatest", "flattenConcat", "flattenMerge", "scan", "runningFold", "runningReduce", "zip",
    )

    fun isFlowProperty(property: KtProperty): Boolean = isCoroutineFlowProperty(property) || isComposeStateProperty(property)

    fun isCoroutineFlowProperty(property: KtProperty): Boolean = analyze(property) {
        val type = property.symbol.returnType
        type is KaClassType && type.classId in coroutineFlowIds
    }

    fun isComposeStateProperty(property: KtProperty): Boolean {
        val typedState = analyze(property) {
            val type = property.symbol.returnType
            type is KaClassType && type.classId in composeStateIds
        }
        if (typedState) return true

        // Delegated Compose state (`var count by remember { mutableStateOf(0) }`) has the value type
        // as the KtProperty return type, so inspect the delegate/initializer for the state factory.
        return composeStateFactoryAnchor(property) != null
    }

    fun composeStateFactoryAnchor(property: KtProperty): KtCallExpression? {
        val root = property.delegateExpression ?: property.initializer ?: return null
        val calls = PsiTreeUtil.collectElementsOfType(root, KtCallExpression::class.java)

        // Prefer the explicit runtime factories first. This avoids selecting a generic wrapper such
        // as remember<T>(), whose source-level substituted type may be State even though its JVM
        // return descriptor is Object and therefore cannot be registered at that call site.
        calls.firstOrNull { call ->
            val id = resolveCallId(call) ?: return@firstOrNull false
            val name = id.substringAfterLast('.')
            name in composeStateFactoryNames && id.startsWith("androidx.compose.runtime.")
        }?.let { return it }

        // Also support State-producing APIs outside androidx.compose.runtime, notably animation
        // *AsState helpers and Flow collectAsState variants. Their declared callable return type is
        // a concrete Compose State interface, which matches the JVM return-type instrumentation.
        return calls.firstOrNull(::callReturnsComposeState)
    }

    /**
     * JVM instrumentation reports the actual Compose state-producing method name (for example
     * `rememberUpdatedState` or `mutableFloatStateOf`). Keep that discriminator in the static
     * graph too: source line alone is not precise enough once inline Compose code, animations and
     * multiple state factories share bytecode line tables.
     */
    fun composeStateFactoryName(property: KtProperty): String? {
        val call = composeStateFactoryAnchor(property) ?: return null
        return resolveCallId(call)?.substringAfterLast('.')
    }

    fun flowTypeName(property: KtProperty): String {
        if (isComposeStateProperty(property)) {
            val delegated = property.delegateExpression != null
            if (delegated) return "Compose State (delegated)"
        }
        return analyze(property) {
            val type = property.symbol.returnType as? KaClassType ?: return@analyze "Flow"
            type.classId?.shortClassName?.asString() ?: "Flow"
        }
    }

    fun resolvesTo(reference: KtNameReferenceExpression, target: KtProperty): Boolean = analyze(reference) {
        val symbol = reference.mainReference.resolveToSymbol() as? KaPropertySymbol ?: return@analyze false
        symbol.psi == target
    }

    fun resolveFlowProperty(reference: KtNameReferenceExpression): KtProperty? {
        val property = analyze(reference) {
            (reference.mainReference.resolveToSymbol() as? KaPropertySymbol)?.psi as? KtProperty
        } ?: return null
        return property.takeIf(::isFlowProperty)
    }

    /**
     * Finds MutableStateFlow/MutableSharedFlow writes that are causally inside this Flow stage.
     *
     * This is what turns code such as
     *
     *     source.collectLatest { value -> _other.update { ... } }
     *
     * into a real source -> otherState propagation edge. It also follows ordinary project helper
     * functions a few levels deep, so collect { sync(value) } where sync() updates another
     * MutableStateFlow remains visible in the graph.
     */
    fun sideEffectWrites(stage: FlowStage): List<SideEffectWrite> {
        val roots = mutableListOf<Pair<PsiElement, List<KtNamedFunction>>>()
        stageLambdaRoots(stage.anchor).forEach { roots += it to emptyList() }

        // Custom/project Flow operators are important in real codebases. If the resolved call has
        // source PSI, inspect that implementation too; this catches `source.observeFoo()` wrappers
        // whose implementation ultimately updates another MutableStateFlow.
        sourceFunction(stage.anchor)?.let { function ->
            function.bodyExpression?.let { roots += it to listOf(function) }
        }
        if (roots.isEmpty()) return emptyList()

        val writes = linkedMapOf<String, SideEffectWrite>()
        val visitedFunctions = linkedSetOf<KtNamedFunction>()
        roots.forEach { (root, helperPath) ->
            collectSideEffectWrites(
                root = root,
                helperPath = helperPath,
                depth = 0,
                visitedFunctions = visitedFunctions,
                out = writes,
            )
        }
        return writes.values.toList()
    }

    /**
     * Analyzes a non-operator/plain read. This intentionally over-approximates at function scope:
     * if the same behavior reads the source Flow and can reach a write to another tracked Flow, we
     * surface a POSSIBLE causal edge instead of hiding it. This is useful for spaghetti-flow audits.
     */
    fun readBehavior(reference: KtNameReferenceExpression): ReadBehavior {
        val immediateCall = nextCallUsingValue(reference)?.call
        val nearestLambda = PsiTreeUtil.getParentOfType(reference, KtLambdaExpression::class.java, false)
        val nearestFunction = PsiTreeUtil.getParentOfType(reference, KtNamedFunction::class.java, false)
        val nearestProperty = PsiTreeUtil.getParentOfType(reference, KtProperty::class.java, false)

        // Function/property scope is intentional: it includes enclosing writer calls such as
        // `_target.update { copy(x = source.value) }`, not just the innermost lambda body.
        val root: PsiElement = nearestFunction?.bodyExpression
            ?: nearestProperty?.initializer
            ?: nearestLambda?.bodyExpression
            ?: reference

        val writes = linkedMapOf<String, SideEffectWrite>()
        collectSideEffectWrites(
            root = root,
            helperPath = emptyList(),
            depth = 0,
            visitedFunctions = linkedSetOf(),
            out = writes,
        )

        val callName = immediateCall?.let(::resolveCallId)?.substringAfterLast('.')
        val valueRead = valueReadName(reference)
        val owner = nearestFunction?.name?.let { "$it()" }
            ?: nearestProperty?.name
            ?: "code block"
        val operation = when {
            callName != null -> "$callName(…)"
            valueRead != null -> valueRead
            else -> "read"
        }

        return ReadBehavior(
            anchor = nearestFunction ?: nearestProperty ?: nearestLambda ?: immediateCall ?: reference,
            label = "$owner • $operation",
            detail = if (writes.isEmpty()) {
                "Reads Flow/StateFlow; no tracked Flow write discovered in this behavior/helper path"
            } else {
                "Reads Flow/StateFlow and can reach ${writes.size} tracked state write(s); shown as possible causality"
            },
            writes = writes.values.toList(),
            confidence = CausalConfidence.POSSIBLE,
        )
    }

    /** Returns a writer only when the immediate operation really resolves to kotlinx.coroutines Flow APIs. */
    fun writerFor(reference: KtNameReferenceExpression): WriterSemantics? {
        directPropertyWrite(reference)?.let { return it }
        valueWrite(reference)?.let { return it }

        val call = immediateReceiverCall(reference) ?: return null
        val callId = resolveCallId(call) ?: return null
        val name = callId.substringAfterLast('.')
        if (name !in writerNames || !isFlowCallable(callId)) return null

        return WriterSemantics(
            label = "$name(…)",
            callId = callId,
            anchor = call,
            changedFields = extractChangedFields(writerMutationRoot(call, name)),
        )
    }

    /**
     * Walks the value forward from a property reference and returns one stage per resolved operator.
     * The same KtCallExpression is used as graph identity, so separate combine inputs converge on one node.
     */
    fun traceForward(reference: KtNameReferenceExpression): FlowUsagePath {
        val stages = mutableListOf<FlowStage>()
        val seenOffsets = mutableSetOf<Int>()
        var current: KtExpression = reference

        while (true) {
            val next = nextCallUsingValue(current) ?: break
            val call = next.call
            if (!seenOffsets.add(call.textOffset)) break

            val callId = resolveCallId(call) ?: break
            val name = callId.substringAfterLast('.')
            val shape = transformShape(call, name)
            val stage = when {
                name in exposureNames && isFlowCallable(callId) -> FlowStage(
                    kind = NodeKind.EXPOSURE,
                    edge = EdgeKind.EXPOSES,
                    label = name,
                    callId = callId,
                    anchor = call,
                    contextualInputs = directFlowArguments(call),
                )

                name in collectorNames && isKnownCollector(callId) -> FlowStage(
                    kind = NodeKind.COLLECTOR,
                    edge = EdgeKind.COLLECTS,
                    label = name,
                    callId = callId,
                    anchor = call,
                    contextualInputs = directFlowArguments(call),
                )

                name in operatorNames && isFlowCallable(callId) -> FlowStage(
                    kind = NodeKind.OPERATOR,
                    edge = EdgeKind.TRANSFORMS,
                    label = name,
                    callId = callId,
                    anchor = call,
                    contextualInputs = directFlowArguments(call),
                    lambdaParameters = shape.parameterNames,
                    fieldEffects = shape.fieldEffects,
                    hasReceiverInput = hasReceiverInput(call),
                    payloadTransforming = name in payloadTransformingNames,
                )

                // Do not stop at project-specific/custom Flow operators. If this reference lives in
                // a Flow-valued property initializer, treat unknown calls as transform stages. If it
                // is terminal but has a lambda/project implementation, treat it as a behavior/collector
                // stage so writes inside it can still be discovered. The `*` makes inference visible.
                enclosingDerivedFlowProperty(reference) != null ||
                    stageLambdaRoots(call).isNotEmpty() || sourceFunction(call) != null -> FlowStage(
                    kind = if (enclosingDerivedFlowProperty(reference) != null) NodeKind.OPERATOR else NodeKind.COLLECTOR,
                    edge = if (enclosingDerivedFlowProperty(reference) != null) EdgeKind.TRANSFORMS else EdgeKind.COLLECTS,
                    label = "$name*",
                    callId = callId,
                    anchor = call,
                    contextualInputs = directFlowArguments(call),
                    lambdaParameters = shape.parameterNames,
                    fieldEffects = shape.fieldEffects,
                    hasReceiverInput = hasReceiverInput(call),
                    payloadTransforming = true,
                    causalConfidence = CausalConfidence.POSSIBLE,
                )

                else -> break
            }

            stages += stage
            if (stage.kind == NodeKind.COLLECTOR) break
            current = next.resultExpression
        }

        return FlowUsagePath(
            stages = stages,
            derivedProperty = if (stages.lastOrNull()?.kind == NodeKind.COLLECTOR) null
            else enclosingDerivedFlowProperty(reference),
        )
    }

    private data class TransformShape(
        val parameterNames: List<String>,
        val fieldEffects: List<StageFieldEffect>,
    )

    /**
     * Extracts field-sensitive semantics from the operator's transform lambda.
     *
     * Supported high-value shapes include:
     *   combine(user, settings) { u, s -> UiState(name = u.name, theme = s.theme) }
     *   state.map { item -> RowUi(title = item.title) }
     *   state.map { it.selectedSong?.id }  // `$value <- selectedSong.id`
     *   pair.map { p -> p.copy(loading = source.isEmpty()) }
     *
     * Explicit lambda parameters are resolved with Analysis API. The implicit `it` parameter has
     * no source PSI declaration to compare against on this API generation, so it is marked MEDIUM.
     */
    private fun transformShape(call: KtCallExpression, operatorName: String): TransformShape {
        if (operatorName !in payloadTransformingNames) return TransformShape(emptyList(), emptyList())
        val lambda = transformLambda(call) ?: return TransformShape(emptyList(), emptyList())
        val parameters = lambda.functionLiteral.valueParameters
        val explicitNames = parameters.mapNotNull { it.name }
        val implicitIt = parameters.isEmpty()
        val parameterNames = if (implicitIt) listOf("it") else explicitNames
        if (parameterNames.isEmpty()) return TransformShape(emptyList(), emptyList())

        val result = lambda.bodyExpression?.statements?.lastOrNull() as? KtExpression
            ?: return TransformShape(parameterNames, emptyList())
        val resultCall = outerCall(result)
        val namedArguments = resultCall?.valueArguments
            ?.mapNotNull { argument ->
                val name = argument.getArgumentName()?.asName?.asString() ?: return@mapNotNull null
                val expression = argument.getArgumentExpression() ?: return@mapNotNull null
                name to expression
            }
            .orEmpty()

        val effects = if (namedArguments.isNotEmpty()) {
            namedArguments.mapNotNull { (outputField, expression) ->
                val uses = collectSourceUses(expression, parameters, parameterNames, implicitIt)
                uses.takeIf { it.isNotEmpty() }?.let { StageFieldEffect(outputField, it) }
            }
        } else {
            val uses = collectSourceUses(result, parameters, parameterNames, implicitIt)
            if (uses.isEmpty()) emptyList() else listOf(StageFieldEffect("\$value", uses))
        }

        return TransformShape(parameterNames, effects)
    }

    private fun stageLambdaRoots(call: KtCallExpression): List<PsiElement> = buildList {
        call.lambdaArguments.mapNotNullTo(this) { it.getLambdaExpression()?.bodyExpression }
        call.valueArguments.mapNotNullTo(this) { argument ->
            (argument.getArgumentExpression() as? KtLambdaExpression)?.bodyExpression
        }
    }.distinct()

    private fun collectSideEffectWrites(
        root: PsiElement,
        helperPath: List<KtNamedFunction>,
        depth: Int,
        visitedFunctions: MutableSet<KtNamedFunction>,
        out: MutableMap<String, SideEffectWrite>,
    ) {
        // Direct writes in this lambda/function body.
        PsiTreeUtil.collectElementsOfType(root, KtNameReferenceExpression::class.java).forEach { reference ->
            val target = resolveFlowProperty(reference) ?: return@forEach
            val writer = writerFor(reference) ?: return@forEach
            val key = "${target.containingKtFile.virtualFile.path}:${target.textOffset}:${writer.anchor.textOffset}"
            out.putIfAbsent(
                key,
                SideEffectWrite(
                    target = target,
                    writer = writer,
                    viaFunctions = helperPath,
                ),
            )
        }

        if (depth >= MAX_SIDE_EFFECT_CALL_DEPTH) return

        // Follow source functions called by the stage. Library calls have no KtNamedFunction PSI and
        // are ignored. This intentionally stays bounded: it is causal side-effect discovery, not a
        // whole-program call graph.
        PsiTreeUtil.collectElementsOfType(root, KtCallExpression::class.java).forEach { call ->
            val function = sourceFunction(call) ?: return@forEach
            if (!visitedFunctions.add(function)) return@forEach
            val body = function.bodyExpression ?: return@forEach
            collectSideEffectWrites(
                root = body,
                helperPath = helperPath + function,
                depth = depth + 1,
                visitedFunctions = visitedFunctions,
                out = out,
            )
        }
    }

    fun sourceFunction(call: KtCallExpression): KtNamedFunction? = analyze(call) {
        call.resolveToCall()?.singleFunctionCallOrNull()?.symbol?.psi as? KtNamedFunction
    }

    fun isComposableFunction(function: KtNamedFunction): Boolean =
        function.annotationEntries.any { entry -> entry.shortName?.asString() == "Composable" }

    private fun transformLambda(call: KtCallExpression): KtLambdaExpression? =
        call.lambdaArguments.lastOrNull()?.getLambdaExpression()
            ?: call.valueArguments.asReversed().firstNotNullOfOrNull { argument ->
                argument.getArgumentExpression() as? KtLambdaExpression
            }

    private fun outerCall(expression: KtExpression): KtCallExpression? = when (expression) {
        is KtCallExpression -> expression
        is KtQualifiedExpression -> expression.selectorExpression as? KtCallExpression
        else -> null
    }

    private fun collectSourceUses(
        expression: KtExpression,
        explicitParameters: List<KtParameter>,
        parameterNames: List<String>,
        implicitIt: Boolean,
    ): List<StageSourceUse> {
        val uses = linkedSetOf<StageSourceUse>()
        val explicitSet = explicitParameters.toSet()

        PsiTreeUtil.collectElementsOfType(expression, KtNameReferenceExpression::class.java).forEach { reference ->
            val name = reference.getReferencedName()
            if (name !in parameterNames) return@forEach

            val confidence = if (implicitIt && name == "it") {
                ProvenanceConfidence.MEDIUM
            } else {
                val resolvedParameter = analyze(reference) {
                    reference.mainReference.resolveToSymbol()?.psi as? KtParameter
                }
                if (resolvedParameter == null || resolvedParameter !in explicitSet) return@forEach
                ProvenanceConfidence.HIGH
            }

            uses += StageSourceUse(
                parameterName = name,
                sourceField = accessPathFromParameter(reference),
                confidence = confidence,
            )
        }
        return uses.toList()
    }

    /** Returns `profile.theme` for `settings.profile.theme`; method calls count as whole-value use. */
    private fun accessPathFromParameter(reference: KtNameReferenceExpression): String? {
        val parts = mutableListOf<String>()
        var current: KtExpression = reference

        while (true) {
            val parent = current.parent as? KtQualifiedExpression ?: break
            if (parent.receiverExpression != current) break
            when (val selector = parent.selectorExpression) {
                is KtNameReferenceExpression -> {
                    parts += selector.getReferencedName()
                    current = parent
                }
                // `songs.isEmpty()` depends on the input value, but not on a resolvable data field.
                is KtCallExpression -> break
                else -> break
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun hasReceiverInput(call: KtCallExpression): Boolean {
        val parent = call.parent as? KtQualifiedExpression ?: return false
        return parent.selectorExpression == call
    }

    private data class NextCall(
        val call: KtCallExpression,
        val resultExpression: KtExpression,
    )

    /**
     * Finds the next call whose output depends on [expression]. Handles both:
     *
     *     flow.map { ... }.stateIn(...)
     *
     * and top-level/multi-input operators:
     *
     *     combine(flowA, flowB) { ... }.map { ... }
     */
    private fun nextCallUsingValue(expression: KtExpression): NextCall? {
        var current = liftTransparentParents(expression)

        val qualified = current.parent as? KtDotQualifiedExpression
        if (qualified != null && qualified.receiverExpression == current) {
            val call = qualified.selectorExpression as? KtCallExpression ?: return null
            return NextCall(call, qualified)
        }

        val argument = current.parent as? KtValueArgument
        if (argument != null && argument.getArgumentExpression() == current) {
            val argumentList = argument.parent as? KtValueArgumentList ?: return null
            val call = argumentList.parent as? KtCallExpression ?: return null
            return NextCall(call, call)
        }

        return null
    }

    private fun liftTransparentParents(expression: KtExpression): KtExpression {
        var current = expression
        while (true) {
            current = when (val parent = current.parent) {
                is KtParenthesizedExpression -> parent
                is KtAnnotatedExpression -> parent

                // A reference to a member Flow is usually the selector of a qualified access:
                //
                //     tracksViewModel.trackObjectsFlow.collectAsStateWithLifecycle()
                //                     ^^^^^^^^^^^^^^^^
                //
                // The value that feeds the next call is the *whole* qualified expression
                // `tracksViewModel.trackObjectsFlow`, not just the selector token. Lift through
                // selector-qualified accesses so nextCallUsingValue() can continue outward to the
                // collector/operator call. This also handles longer chains such as holder.vm.flow.
                is KtQualifiedExpression -> {
                    if (parent.selectorExpression == current) parent else return current
                }

                else -> return current
            }
        }
    }

    private fun valueReadName(reference: KtNameReferenceExpression): String? {
        val qualified = PsiTreeUtil.getParentOfType(reference, KtDotQualifiedExpression::class.java, false)
            ?: return null
        if (!qualified.receiverExpression.textRange.contains(reference.textRange)) return null
        val selector = qualified.selectorExpression as? KtNameReferenceExpression ?: return null
        if (selector.getReferencedName() != "value") return null
        val assignment = qualified.parent as? KtBinaryExpression
        if (assignment != null && assignment.left == qualified && assignment.operationToken == KtTokens.EQ) return null
        return "value read"
    }

    private fun directPropertyWrite(reference: KtNameReferenceExpression): WriterSemantics? {
        val target = resolveFlowProperty(reference) ?: return null
        if (!isComposeStateProperty(target)) return null
        val assignment = reference.parent as? KtBinaryExpression ?: return null
        if (assignment.left != reference || assignment.operationToken != KtTokens.EQ) return null
        return WriterSemantics(
            label = "state =",
            callId = "androidx.compose.runtime.delegatedState.setValue",
            anchor = assignment,
            changedFields = extractChangedFields(assignment.right),
        )
    }

    fun enclosingDerivedComposeStateProperty(reference: KtNameReferenceExpression): KtProperty? {
        val property = PsiTreeUtil.getParentOfType(reference, KtProperty::class.java, false) ?: return null
        if (property == reference) return null
        val root = property.delegateExpression ?: property.initializer ?: return null
        if (!root.textRange.contains(reference.textRange)) return null
        return property.takeIf(::isComposeStateProperty)
    }

    private fun valueWrite(reference: KtNameReferenceExpression): WriterSemantics? {
        val qualified = PsiTreeUtil.getParentOfType(reference, KtDotQualifiedExpression::class.java, false)
            ?: return null
        if (qualified.receiverExpression != reference &&
            !qualified.receiverExpression.textRange.contains(reference.textRange)) return null

        val selector = qualified.selectorExpression as? KtNameReferenceExpression ?: return null
        if (selector.getReferencedName() != "value") return null

        val callableId = analyze(selector) {
            (selector.mainReference.resolveToSymbol() as? KaPropertySymbol)?.callableId?.asSingleFqName()?.asString()
        } ?: return null
        val supportedValueOwner = callableId.startsWith("kotlinx.coroutines.flow.") ||
            callableId.startsWith("androidx.compose.runtime.")
        if (!supportedValueOwner) return null

        val assignment = qualified.parent as? KtBinaryExpression ?: return null
        if (assignment.left != qualified || assignment.operationToken != KtTokens.EQ) return null

        return WriterSemantics(
            label = "value =",
            callId = callableId,
            anchor = assignment,
            changedFields = extractChangedFields(assignment.right),
        )
    }

    private fun immediateReceiverCall(reference: KtNameReferenceExpression): KtCallExpression? {
        var current: KtExpression = reference
        while (true) {
            when (val parent = current.parent) {
                is KtParenthesizedExpression -> current = parent
                is KtDotQualifiedExpression -> {
                    if (parent.receiverExpression != current &&
                        !parent.receiverExpression.textRange.contains(reference.textRange)) return null
                    return parent.selectorExpression as? KtCallExpression
                }
                else -> return null
            }
        }
    }

    private fun enclosingDerivedFlowProperty(reference: KtNameReferenceExpression): KtProperty? {
        val property = PsiTreeUtil.getParentOfType(reference, KtProperty::class.java, false) ?: return null
        if (property.initializer?.textRange?.contains(reference.textRange) != true) return null
        return property.takeIf(::isFlowProperty)
    }

    private fun writerMutationRoot(call: KtCallExpression, writerName: String): PsiElement? = when (writerName) {
        "update", "getAndUpdate", "updateAndGet" ->
            call.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression
                ?: call.valueArguments.lastOrNull()?.getArgumentExpression()

        "emit", "tryEmit" -> call.valueArguments.firstOrNull()?.getArgumentExpression()
        else -> call
    }

    /**
     * Resolves direct Flow-valued arguments of a call. This intentionally does not dig through
     * nested operator expressions: `combine(a.map { ... }, b)` adds `b` as a contextual input,
     * while the `a -> map -> combine` path is represented by the forward walk itself.
     */
    private fun directFlowArguments(call: KtCallExpression): List<KtProperty> =
        call.valueArguments.mapNotNull { argument ->
            flowPropertyFromExpression(argument.getArgumentExpression())
        }.distinctBy { property ->
            "${property.containingKtFile.virtualFile.path}:${property.textOffset}"
        }

    private fun flowPropertyFromExpression(expression: KtExpression?): KtProperty? {
        val candidate = when (expression) {
            is KtNameReferenceExpression -> expression
            is KtDotQualifiedExpression -> expression.selectorExpression as? KtNameReferenceExpression
            is KtParenthesizedExpression -> return flowPropertyFromExpression(expression.expression)
            else -> null
        } ?: return null

        val property = analyze(candidate) {
            (candidate.mainReference.resolveToSymbol() as? KaPropertySymbol)?.psi as? KtProperty
        } ?: return null
        return property.takeIf(::isFlowProperty)
    }

    /** Extracts fields explicitly changed by copy(...) or direct property assignment inside a writer. */
    private fun extractChangedFields(root: PsiElement?): Set<String> {
        if (root == null) return emptySet()
        val fields = linkedSetOf<String>()

        PsiTreeUtil.collectElementsOfType(root, KtCallExpression::class.java).forEach { call ->
            val callId = resolveCallId(call) ?: return@forEach
            if (callId.substringAfterLast('.') != "copy") return@forEach
            call.valueArguments.mapNotNullTo(fields) { argument ->
                argument.getArgumentName()?.asName?.asString()
            }
        }

        PsiTreeUtil.collectElementsOfType(root, KtBinaryExpression::class.java).forEach { assignment ->
            if (assignment.operationToken !in assignmentTokens) return@forEach
            val left = assignment.left as? KtDotQualifiedExpression ?: return@forEach
            val selector = left.selectorExpression as? KtNameReferenceExpression ?: return@forEach
            val fieldId = analyze(selector) {
                (selector.mainReference.resolveToSymbol() as? KaPropertySymbol)
                    ?.callableId
                    ?.asSingleFqName()
                    ?.asString()
            } ?: return@forEach
            if (fieldId.startsWith("kotlinx.coroutines.flow.")) return@forEach
            fields += selector.getReferencedName()
        }

        return fields
    }


    private const val MAX_SIDE_EFFECT_CALL_DEPTH = 6

    private val assignmentTokens = setOf(
        KtTokens.EQ,
        KtTokens.PLUSEQ,
        KtTokens.MINUSEQ,
        KtTokens.MULTEQ,
        KtTokens.DIVEQ,
        KtTokens.PERCEQ,
    )

    private fun callReturnsComposeState(call: KtCallExpression): Boolean = analyze(call) {
        val type = call.resolveToCall()?.singleFunctionCallOrNull()?.symbol?.returnType
        type is KaClassType && type.classId in composeStateIds
    }

    private fun resolveCallId(call: KtCallExpression): String? = analyze(call) {
        call.resolveToCall()?.singleFunctionCallOrNull()?.symbol?.callableId?.asSingleFqName()?.asString()
    }

    private fun isFlowCallable(callId: String?): Boolean =
        callId?.startsWith("kotlinx.coroutines.flow.") == true

    private fun isKnownCollector(callId: String?): Boolean = when {
        callId == null -> false
        callId.startsWith("kotlinx.coroutines.flow.") -> true
        callId.startsWith("androidx.compose.runtime.") -> true
        callId.startsWith("androidx.lifecycle.compose.") -> true
        else -> false
    }
}
