package com.oskiapps.flowgraph.instrumentation

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import java.io.File
import javax.inject.Inject

abstract class FlowGraphInstrumentationExtension @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val traceReads: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    /** Trace cold Flow collection boundaries and FlowCollector element delivery. */
    val traceColdFlows: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    /** Trace suspended MutableSharedFlow.emit() requests in addition to tryEmit(). */
    val traceSuspendingEmits: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    /** Trace source Compose function entry/recomposition boundaries. */
    val traceCompose: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    /** Capture top-level Android UI input so the IDE timeline can mark user interactions. */
    val traceUiInteractions: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * v0.11 default: instrument every eligible Flow/StateFlow/SharedFlow field in the debug APK once.
     * The IDE may switch between arbitrary existing flows later without rebuilding.
     */
    val instrumentAllEligibleFlows: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    /** Legacy targeted-mode fallback. Ignored while instrumentAllEligibleFlows=true. */
    val instrumentAllWhenTargetsMissing: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)
}

class FlowGraphInstrumentationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "flowGraphInstrumentation",
            FlowGraphInstrumentationExtension::class.java,
        )

        project.plugins.withId("com.android.application") {
            // The runtime is intentionally an explicit consumer dependency. This keeps the
            // Gradle plugin conventional and makes the app-side setup visible in build.gradle.kts:
            // debugImplementation("com.github.elivity:flow-graph:<same version>")

            project.afterEvaluate {
                val hasRuntime = project.configurations.findByName("debugImplementation")
                    ?.dependencies
                    ?.any { dependency ->
                        dependency.group == "com.github.elivity" && dependency.name == "flow-graph"
                    } == true
                if (!hasRuntime) {
                    project.logger.warn(
                        "Flow Graph: runtime dependency not found. Add " +
                            "debugImplementation(\"com.github.elivity:flow-graph:<same version as the plugin>\")",
                    )
                }
            }

            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
                if (!extension.enabled.get()) return@onVariants

                val globalMode = extension.instrumentAllEligibleFlows.get()
                // Important: in global mode do not feed .flowgraph/targets.txt into the transform
                // parameters at all. Otherwise merely selecting another Flow in the IDE would
                // change a Gradle task input and could invalidate instrumentation on the next Run.
                val targets = if (globalMode) {
                    emptyList()
                } else {
                    readTargets(findBuildRoot(project.layout.projectDirectory.asFile).resolve(".flowgraph/targets.txt"))
                }
                variant.instrumentation.excludes.add("com/oskiapps/flowgraph/runtime/**")
                variant.instrumentation.transformClassesWith(
                    FlowGraphClassVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { params ->
                    params.targets.set(targets)
                    params.traceReads.set(extension.traceReads)
                    params.traceColdFlows.set(extension.traceColdFlows)
                    params.traceSuspendingEmits.set(extension.traceSuspendingEmits)
                    params.traceCompose.set(extension.traceCompose)
                    params.traceUiInteractions.set(extension.traceUiInteractions)
                    params.instrumentAllEligibleFlows.set(extension.instrumentAllEligibleFlows)
                    params.instrumentAllWhenTargetsMissing.set(extension.instrumentAllWhenTargetsMissing)
                }
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
                )

                project.logger.lifecycle(
                    "FlowGraph: automatic runtime instrumentation enabled for ${variant.name}; " +
                        if (globalMode) {
                            "GLOBAL mode: all eligible Flow/StateFlow/SharedFlow fields are instrumented; " +
                                "switching inspected flows does not require a rebuild"
                        } else {
                            "targeted compatibility mode; ${targets.size} Analysis-API targets from .flowgraph/targets.txt"
                        },
                )
            }
        }
    }

    /**
     * Locate the consumer build root without touching another Gradle Project model.
     *
     * Gradle isolated-projects mode rejects cross-project file lookups through the root project model.
     * Walking the filesystem from the module directory keeps targeted compatibility mode
     * isolated-project safe while still finding the root-level .flowgraph directory.
     */
    private fun findBuildRoot(startDirectory: File): File {
        var current: File? = startDirectory
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile || File(current, "settings.gradle").isFile) {
                return current
            }
            current = current.parentFile
        }
        return startDirectory
    }

    private fun readTargets(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .distinct()
            .sorted()
    }
}

interface FlowGraphInstrumentationParameters : InstrumentationParameters {
    @get:Input
    val targets: ListProperty<String>

    @get:Input
    val traceReads: Property<Boolean>

    @get:Input
    val traceColdFlows: Property<Boolean>

    @get:Input
    val traceSuspendingEmits: Property<Boolean>

    @get:Input
    val traceCompose: Property<Boolean>

    @get:Input
    val traceUiInteractions: Property<Boolean>

    @get:Input
    val instrumentAllEligibleFlows: Property<Boolean>

    @get:Input
    val instrumentAllWhenTargetsMissing: Property<Boolean>
}

abstract class FlowGraphClassVisitorFactory :
    AsmClassVisitorFactory<FlowGraphInstrumentationParameters> {

    override fun isInstrumentable(classData: ClassData): Boolean =
        !classData.className.startsWith("com.oskiapps.flowgraph.runtime.")

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = FlowGraphClassVisitor(
        delegate = nextClassVisitor,
        targets = parameters.get().targets.get().toSet(),
        traceReads = parameters.get().traceReads.get(),
        traceColdFlows = parameters.get().traceColdFlows.get(),
        traceSuspendingEmits = parameters.get().traceSuspendingEmits.get(),
        traceCompose = parameters.get().traceCompose.get(),
        traceUiInteractions = parameters.get().traceUiInteractions.get(),
        instrumentAllEligibleFlows = parameters.get().instrumentAllEligibleFlows.get(),
        instrumentAllWhenTargetsMissing = parameters.get().instrumentAllWhenTargetsMissing.get(),
    )
}

private class FlowGraphClassVisitor(
    delegate: ClassVisitor,
    private val targets: Set<String>,
    private val traceReads: Boolean,
    private val traceColdFlows: Boolean,
    private val traceSuspendingEmits: Boolean,
    private val traceCompose: Boolean,
    private val traceUiInteractions: Boolean,
    private val instrumentAllEligibleFlows: Boolean,
    private val instrumentAllWhenTargetsMissing: Boolean,
) : ClassVisitor(Opcodes.ASM9, delegate) {

    private var classInternalName: String = "<unknown>"
    private var sourceFileName: String? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        classInternalName = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val base = super.visitMethod(access, name, descriptor, signature, exceptions)
        return FlowGraphMethodVisitor(
            methodVisitor = base,
            methodAccess = access,
            methodName = name,
            methodDescriptor = descriptor,
            classInternalName = classInternalName,
            targets = targets,
            traceReads = traceReads,
            traceColdFlows = traceColdFlows,
            traceSuspendingEmits = traceSuspendingEmits,
            traceCompose = traceCompose,
            traceUiInteractions = traceUiInteractions,
            sourceFileName = sourceFileName,
            instrumentAllEligibleFlows = instrumentAllEligibleFlows,
            instrumentAllWhenTargetsMissing = instrumentAllWhenTargetsMissing,
        )
    }
}

private class FlowGraphMethodVisitor(
    methodVisitor: MethodVisitor,
    private val methodAccess: Int,
    private val methodName: String,
    private val methodDescriptor: String,
    private val classInternalName: String,
    private val targets: Set<String>,
    private val traceReads: Boolean,
    private val traceColdFlows: Boolean,
    private val traceSuspendingEmits: Boolean,
    private val traceCompose: Boolean,
    private val traceUiInteractions: Boolean,
    private val sourceFileName: String?,
    private val instrumentAllEligibleFlows: Boolean,
    private val instrumentAllWhenTargetsMissing: Boolean,
) : GeneratorAdapter(Opcodes.ASM9, methodVisitor, methodAccess, methodName, methodDescriptor) {

    private var currentLine: Int = -1
    /** Inject the live-composition registration once, at this composable function\'s outer group. */
    private var composeInspectionInjected: Boolean = false
    /** True when this source composable's generated body took Composer.skipToGroupEnd(). */
    private var composeSkippedLocal: Int = -1

    override fun visitCode() {
        super.visitCode()
        if (traceCompose && isSourceComposableMethod()) {
            // A generated @Composable JVM method can be *entered* because its parent recomposed and
            // still have its source body skipped by Composer.skipToGroupEnd(). Counting method entry
            // therefore wildly over-reports recomposition activity. Track the compiler's skip path
            // and emit only when this invocation actually executed the composable body.
            composeSkippedLocal = newLocal(Type.BOOLEAN_TYPE)
            push(false)
            storeLocal(composeSkippedLocal)

            // Do NOT register CompositionData here. visitCode() runs before the Compose compiler's
            // startRestartGroup/startReplaceGroup call, so the current slot-table group is still
            // the caller/parent. Registration is done after the function's outer group starts.
        }
    }

    override fun visitLineNumber(line: Int, start: Label) {
        currentLine = line
        super.visitLineNumber(line, start)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        if (!isTraceableFlowDescriptor(descriptor) || !shouldRegisterField(owner, name)) {
            super.visitFieldInsn(opcode, owner, name, descriptor)
            return
        }

        val runtimeKey = runtimeKey(owner, name)
        when (opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> {
                super.visitFieldInsn(opcode, owner, name, descriptor)
                dup()
                push(runtimeKey)
                invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            }

            Opcodes.PUTFIELD -> {
                val flowType = Type.getType(descriptor)
                val flowLocal = newLocal(flowType)
                val ownerLocal = newLocal(Type.getType(Object::class.java))
                storeLocal(flowLocal)
                storeLocal(ownerLocal)
                loadLocal(ownerLocal)
                loadLocal(flowLocal)
                super.visitFieldInsn(opcode, owner, name, descriptor)
                loadLocal(flowLocal)
                push(runtimeKey)
                invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            }

            Opcodes.PUTSTATIC -> {
                val flowType = Type.getType(descriptor)
                val flowLocal = newLocal(flowType)
                storeLocal(flowLocal)
                loadLocal(flowLocal)
                super.visitFieldInsn(opcode, owner, name, descriptor)
                loadLocal(flowLocal)
                push(runtimeKey)
                invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            }

            else -> super.visitFieldInsn(opcode, owner, name, descriptor)
        }
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val site = site()

        // Compose's compiler-generated skipToGroupEnd() is the authoritative signal that this
        // invocation did not execute the source composable body. Mark it before forwarding the
        // original call; visitInsn() emits the runtime event only for non-skipped invocations.
        if (
            traceCompose &&
            composeSkippedLocal >= 0 &&
            owner == "androidx/compose/runtime/Composer" &&
            name == "skipToGroupEnd" &&
            descriptor == "()V"
        ) {
            push(true)
            storeLocal(composeSkippedLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        if (
            traceCompose &&
            !composeInspectionInjected &&
            isSourceComposableMethod() &&
            isComposeFunctionGroupStart(owner, name, descriptor)
        ) {
            instrumentComposeFunctionGroupStart(
                opcode = opcode,
                owner = owner,
                name = name,
                descriptor = descriptor,
                isInterface = isInterface,
                site = site,
            )
            composeInspectionInjected = true
            return
        }

        if (traceReads && name == "getValue" && descriptor == "()Ljava/lang/Object;" && isFlowOwner(owner)) {
            dup()
            push(site)
            invokeRuntime("recordRead", "(Ljava/lang/Object;Ljava/lang/String;)V")
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        // Kotlin delegated Compose state (`var value by remember { mutableStateOf(...) }`) normally
        // calls static operator helpers in SnapshotStateKt rather than invoking MutableState.getValue /
        // setValue directly from app bytecode. Those helpers live in the Compose runtime library, so
        // project-only instrumentation never sees the inner interface access. Trace the state object
        // at the call site before forwarding to the helper.
        if (traceReads && opcode == Opcodes.INVOKESTATIC && isComposeDelegatedStateRead(owner, name, descriptor)) {
            instrumentComposeDelegatedRead(
                opcode = opcode,
                owner = owner,
                name = name,
                descriptor = descriptor,
                isInterface = isInterface,
                site = site,
            )
            return
        }

        if (opcode == Opcodes.INVOKESTATIC && isComposeDelegatedStateWrite(owner, name, descriptor)) {
            instrumentComposeDelegatedWrite(
                opcode = opcode,
                owner = owner,
                name = name,
                descriptor = descriptor,
                isInterface = isInterface,
                site = site,
            )
            return
        }

        // Compose runtime has both generic State/MutableState accessors and specialized primitive
        // accessors (IntState, LongState, FloatState, DoubleState). It can also devirtualize calls to
        // concrete Snapshot* implementations, so matching only the two public interfaces misses a
        // large fraction of real Compose state activity.
        if (traceReads && isComposeStateReadAccessor(owner, name, descriptor)) {
            dup()
            push(site)
            invokeRuntime("recordRead", "(Ljava/lang/Object;Ljava/lang/String;)V")
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        if (name == "setValue" && descriptor == "(Ljava/lang/Object;)V" && isMutableFlowOwner(owner)) {
            val valueLocal = newLocal(Type.getType(Object::class.java))
            val flowLocal = newLocal(Type.getType(Object::class.java))
            storeLocal(valueLocal)
            storeLocal(flowLocal)
            loadLocal(flowLocal)
            push("setValue")
            push(site)
            invokeRuntime("beforeWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            loadLocal(flowLocal)
            loadLocal(valueLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            loadLocal(flowLocal)
            push("setValue")
            push(site)
            invokeRuntime("afterWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            return
        }

        if (isComposeStateWriteAccessor(owner, name, descriptor)) {
            val argumentType = Type.getArgumentTypes(descriptor).single()
            val valueLocal = newLocal(argumentType)
            val stateLocal = newLocal(Type.getType(Object::class.java))
            storeLocal(valueLocal)
            storeLocal(stateLocal)
            loadLocal(stateLocal)
            push(name)
            push(site)
            invokeRuntime("beforeWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            loadLocal(stateLocal)
            loadLocal(valueLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            loadLocal(stateLocal)
            push(name)
            push(site)
            invokeRuntime("afterWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            return
        }

        if (name == "compareAndSet" && descriptor == "(Ljava/lang/Object;Ljava/lang/Object;)Z" && isMutableFlowOwner(owner)) {
            val newValueLocal = newLocal(Type.getType(Object::class.java))
            val expectedLocal = newLocal(Type.getType(Object::class.java))
            val flowLocal = newLocal(Type.getType(Object::class.java))
            val resultLocal = newLocal(Type.BOOLEAN_TYPE)
            storeLocal(newValueLocal)
            storeLocal(expectedLocal)
            storeLocal(flowLocal)
            loadLocal(flowLocal)
            push("compareAndSet")
            push(site)
            invokeRuntime("beforeWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            loadLocal(flowLocal)
            loadLocal(expectedLocal)
            loadLocal(newValueLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            storeLocal(resultLocal)
            loadLocal(flowLocal)
            push("compareAndSet")
            push(site)
            invokeRuntime("afterWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            loadLocal(resultLocal)
            return
        }

        // Deep cold-flow tracing: bind the collector to the Flow being collected. Every later
        // FlowCollector.emit(value, continuation) can then be attributed back to that Flow, even
        // through library operators such as map/filter/combine/flatMapLatest.
        if (traceColdFlows && name == "collect" && descriptor == FLOW_COLLECT_DESCRIPTOR) {
            val continuationLocal = newLocal(Type.getType("Lkotlin/coroutines/Continuation;"))
            val collectorLocal = newLocal(Type.getType("Lkotlinx/coroutines/flow/FlowCollector;"))
            val flowLocal = newLocal(Type.getType(Object::class.java))
            storeLocal(continuationLocal)
            storeLocal(collectorLocal)
            storeLocal(flowLocal)
            loadLocal(flowLocal)
            loadLocal(collectorLocal)
            push(site)
            invokeRuntime("bindCollector", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V")
            loadLocal(flowLocal)
            loadLocal(collectorLocal)
            loadLocal(continuationLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        // Any cold Flow ultimately delivers elements through FlowCollector.emit(). Because collect()
        // above binds collector identity to a Flow property, this gives us runtime values for arbitrary
        // cold-flow builders and operator pipelines, not only StateFlow writes.
        if (traceColdFlows && name == "emit" && descriptor == FLOW_COLLECTOR_EMIT_DESCRIPTOR && isFlowCollectorOwner(owner)) {
            val continuationLocal = newLocal(Type.getType("Lkotlin/coroutines/Continuation;"))
            val valueLocal = newLocal(Type.getType(Object::class.java))
            val collectorLocal = newLocal(Type.getType(Object::class.java))
            storeLocal(continuationLocal)
            storeLocal(valueLocal)
            storeLocal(collectorLocal)
            loadLocal(collectorLocal)
            loadLocal(valueLocal)
            push(site)
            invokeRuntime("recordCollectorEmission", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V")
            loadLocal(collectorLocal)
            loadLocal(valueLocal)
            loadLocal(continuationLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        // Suspended SharedFlow.emit(value) cannot be treated like tryEmit(): the call may return
        // COROUTINE_SUSPENDED and finish later. Record the request immediately, and record an
        // accepted emission too when it completes synchronously. Downstream collector-delivery events
        // provide the exact observed propagation even for genuinely suspended calls.
        if (traceSuspendingEmits && name == "emit" && descriptor == SUSPENDING_EMIT_DESCRIPTOR && isMutableSharedFlowOwner(owner)) {
            val continuationLocal = newLocal(Type.getType("Lkotlin/coroutines/Continuation;"))
            val valueLocal = newLocal(Type.getType(Object::class.java))
            val flowLocal = newLocal(Type.getType(Object::class.java))
            val resultLocal = newLocal(Type.getType(Object::class.java))
            storeLocal(continuationLocal)
            storeLocal(valueLocal)
            storeLocal(flowLocal)

            loadLocal(flowLocal)
            loadLocal(valueLocal)
            push("emit")
            push(site)
            invokeRuntime("recordEmissionRequest", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")

            loadLocal(flowLocal)
            loadLocal(valueLocal)
            loadLocal(continuationLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            storeLocal(resultLocal)

            val suspended = newLabel()
            loadLocal(resultLocal)
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/coroutines/intrinsics/IntrinsicsKt",
                "getCOROUTINE_SUSPENDED",
                "()Ljava/lang/Object;",
                false,
            )
            ifCmp(Type.getType(Object::class.java), EQ, suspended)
            loadLocal(flowLocal)
            loadLocal(valueLocal)
            push("emit")
            push(site)
            invokeRuntime("recordEmission", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            mark(suspended)
            loadLocal(resultLocal)
            return
        }

        if (name == "tryEmit" && descriptor == "(Ljava/lang/Object;)Z" && isMutableSharedFlowOwner(owner)) {
            val valueLocal = newLocal(Type.getType(Object::class.java))
            val flowLocal = newLocal(Type.getType(Object::class.java))
            val resultLocal = newLocal(Type.BOOLEAN_TYPE)
            storeLocal(valueLocal)
            storeLocal(flowLocal)
            loadLocal(flowLocal)
            loadLocal(valueLocal)
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            storeLocal(resultLocal)
            loadLocal(resultLocal)
            loadLocal(flowLocal)
            loadLocal(valueLocal)
            push("tryEmit")
            push(site)
            invokeRuntime(
                "recordEmissionIf",
                "(ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V",
            )
            loadLocal(resultLocal)
            return
        }

        // Local/delegated Compose MutableState often has no State-typed JVM field. Register the
        // object at its factory/call site so live setValue/getValue events can still map back to the
        // source property by line number.
        if (traceCompose && isComposeStateReturnType(descriptor)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            dup()
            push(dynamicComposeStateKey(name))
            invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            return
        }

        // Register returned Flow objects at the call site too. This gives intermediate cold-flow
        // operators (including locals that are never stored in a field) a synthetic runtime identity.
        // The IDE maps @flowop keys back to operator/collector nodes by source line + operator name.
        if (traceColdFlows && isFlowReturnType(descriptor)) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            dup()
            push(dynamicFlowKey(name))
            invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            return
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInsn(opcode: Int) {
        // GeneratorAdapter deliberately does not try to emulate the JVM operand stack. This is
        // important for Kotlin/Compose generated methods: AdviceAdapter's stack model can underflow
        // when instrumentation is injected around PUTFIELD/PUTSTATIC even though the emitted bytecode
        // itself is valid. AGP recomputes frames for every instrumented method.
        val isExit = opcode in Opcodes.IRETURN..Opcodes.RETURN || opcode == Opcodes.ATHROW
        if (isExit) {
            // Install one Window.Callback wrapper after Activity-style onCreate(Bundle) completes.
            // We intentionally do not require a compile-time Android dependency here: the runtime
            // verifies `this` reflectively and is a no-op for unrelated onCreate(Bundle) methods.
            // This captures real top-level touch/key/scroll input for the profiler timeline without
            // changing application event dispatch semantics.
            if (opcode == Opcodes.RETURN && traceUiInteractions && isActivityOnCreateSignature()) {
                loadThis()
                invokeRuntime("installUiInteractionCapture", "(Ljava/lang/Object;)V")
            }

            // Report actual source-body execution, not raw JVM method entry. Parent recomposition can
            // invoke a child composable method only for the child to immediately skip its group;
            // those invocations must not look like child recompositions in Flow Graph.
            if (opcode != Opcodes.ATHROW && traceCompose && composeSkippedLocal >= 0) {
                val skipped = newLabel()
                loadLocal(composeSkippedLocal)
                ifZCmp(NE, skipped)
                push(composeRuntimeKey())
                push("compose-body:${sourceFileName ?: classInternalName}:$methodName")
                invokeRuntime("recordCompose", "(Ljava/lang/String;Ljava/lang/String;)V")
                mark(skipped)
            }
            // Flow-valued computed/delegated Kotlin properties have no Flow-typed backing field.
            // ARETURN already has the returned Flow on the real JVM stack. DUP leaves one copy for
            // ARETURN and feeds the other copy to registerFlow().
            if (traceColdFlows && opcode == Opcodes.ARETURN && isFlowReturnType(methodDescriptor) &&
                methodName.startsWith("get") && methodName.length > 3
            ) {
                dup()
                push(runtimeKey(classInternalName, getterPropertyName(methodName)))
                invokeRuntime("registerFlow", "(Ljava/lang/Object;Ljava/lang/String;)V")
            }

            // Robust fallback for inline MutableStateFlow.update {}. Keep any method return value
            // already on the operand stack underneath the injected arguments; INVOKESTATIC consumes
            // only its own arguments and leaves that return value untouched for the original return.
            if (opcode != Opcodes.ATHROW && classInternalName.endsWith("/StateFlowImpl") &&
                (methodName == "setValue" || methodName == "compareAndSet")
            ) {
                loadThis()
                push("StateFlowImpl.$methodName")
                push(site())
                invokeRuntime("afterWrite", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V")
            }
        }
        super.visitInsn(opcode)
    }

    private fun isComposeFunctionGroupStart(owner: String, name: String, descriptor: String): Boolean {
        if (owner != "androidx/compose/runtime/Composer") return false
        val args = Type.getArgumentTypes(descriptor)
        if (args.isEmpty() || args[0] != Type.INT_TYPE) return false
        return when (name) {
            "startRestartGroup" ->
                Type.getReturnType(descriptor).internalName == "androidx/compose/runtime/Composer"
            "startReplaceGroup", "startReplaceableGroup" ->
                Type.getReturnType(descriptor) == Type.VOID_TYPE
            else -> false
        }
    }

    /**
     * Capture the compiler-generated group key without guessing it from bytecode constants.
     *
     * At an instance Composer call the operand stack is:
     *   ..., composer, key [, sourceInformation]
     *
     * We temporarily spill the arguments, execute the original group-start call unchanged, then
     * register the exact key together with CompositionData. For restart groups we also spill and
     * restore the returned Composer so the transformed bytecode has exactly the original stack.
     */
    private fun instrumentComposeFunctionGroupStart(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
        site: String,
    ) {
        val args = Type.getArgumentTypes(descriptor)
        val sourceInfoArg = args.getOrNull(1)?.takeIf {
            it.sort == Type.OBJECT && it.internalName == "java/lang/String"
        }

        val sourceInfoLocal = if (sourceInfoArg != null) newLocal(sourceInfoArg) else -1
        if (sourceInfoLocal >= 0) storeLocal(sourceInfoLocal)

        val keyLocal = newLocal(Type.INT_TYPE)
        storeLocal(keyLocal)

        // The receiver Composer remains on the operand stack.
        loadLocal(keyLocal)
        if (sourceInfoLocal >= 0) loadLocal(sourceInfoLocal)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

        val returnType = Type.getReturnType(descriptor)
        val composerForRuntimeLocal: Int
        if (returnType.sort == Type.OBJECT &&
            returnType.internalName == "androidx/compose/runtime/Composer"
        ) {
            composerForRuntimeLocal = newLocal(returnType)
            storeLocal(composerForRuntimeLocal)
        } else {
            val composerArg = composerArgumentIndex()
            if (composerArg < 0) return
            composerForRuntimeLocal = newLocal(Type.getObjectType("androidx/compose/runtime/Composer"))
            loadArg(composerArg)
            storeLocal(composerForRuntimeLocal)
        }

        push(composeRuntimeKey())
        loadLocal(composerForRuntimeLocal)
        push("compose-group:${sourceFileName ?: classInternalName}:$methodName@$site")
        loadLocal(keyLocal)
        invokeRuntime(
            "recordComposeInspection",
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;I)V",
        )

        // Restore the original restart-group return value for the compiler-generated ASTORE.
        if (returnType.sort == Type.OBJECT) {
            loadLocal(composerForRuntimeLocal)
        }
    }

    private fun isActivityOnCreateSignature(): Boolean =
        methodName == "onCreate" &&
            methodDescriptor == "(Landroid/os/Bundle;)V" &&
            (methodAccess and Opcodes.ACC_STATIC) == 0

    private fun isSourceComposableMethod(): Boolean {
        if (methodName == "<init>" || methodName == "<clinit>" || methodName == "invoke" || methodName == "invokeSuspend") return false
        if (methodName.startsWith("access$") || methodName.startsWith("$")) return false
        if (classInternalName.startsWith("androidx/") || classInternalName.startsWith("kotlin/") ||
            classInternalName.startsWith("kotlinx/") || classInternalName.startsWith("java/") ||
            classInternalName.startsWith("javax/") || classInternalName.startsWith("com/oskiapps/flowgraph/runtime/")) return false
        return Type.getArgumentTypes(methodDescriptor).any { arg ->
            arg.sort == Type.OBJECT && arg.internalName == "androidx/compose/runtime/Composer"
        }
    }


    private fun composerArgumentIndex(): Int =
        Type.getArgumentTypes(methodDescriptor).indexOfFirst { arg ->
            arg.sort == Type.OBJECT && arg.internalName == "androidx/compose/runtime/Composer"
        }

    private fun composeRuntimeKey(): String {
        val packageName = classInternalName.substringBeforeLast('/', "").replace('/', '.')
        val fileName = sourceFileName ?: (classInternalName.substringAfterLast('/').substringBefore('$').removeSuffix("Kt") + ".kt")
        val sourceName = methodName.substringBefore('-')
        return "@compose|$packageName|$fileName|$sourceName"
    }

    private fun invokeRuntime(name: String, descriptor: String) {
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            RUNTIME_INTERNAL_NAME,
            name,
            descriptor,
            false,
        )
    }

    private fun shouldRegisterField(owner: String, fieldName: String): Boolean {
        if (instrumentAllEligibleFlows) return true
        if (targets.isEmpty()) return instrumentAllWhenTargetsMissing
        val candidate = runtimeKey(owner, fieldName)
        if (candidate in targets) return true
        val candidatePackage = sourcePackage(candidate)
        val sameField = targets.filter { target ->
            target.substringAfterLast('.') == fieldName && sourcePackage(target) == candidatePackage
        }
        if (sameField.size == 1) return true
        return false
    }

    private fun sourcePackage(runtimeKey: String): String {
        val withoutField = runtimeKey.substringBeforeLast('.', "")
        return withoutField.substringBeforeLast('.', withoutField)
    }

    private fun runtimeKey(owner: String, fieldName: String): String {
        val dotted = owner.replace('/', '.')
        val packageName = dotted.substringBeforeLast('.', "")
        val binarySimple = dotted.substringAfterLast('.')
        val sourceOwner = binarySimple.substringAfterLast('$')
        return when {
            binarySimple.endsWith("Kt") && !binarySimple.contains('$') ->
                if (packageName.isEmpty()) fieldName else "$packageName.$fieldName"
            packageName.isEmpty() -> "$sourceOwner.$fieldName"
            else -> "$packageName.$sourceOwner.$fieldName"
        }
    }

    private fun site(): String {
        val className = classInternalName.replace('/', '.')
        return if (currentLine > 0) "$className.$methodName:$currentLine" else "$className.$methodName"
    }

    private fun dynamicFlowKey(callName: String): String =
        "@flowop|${classInternalName.replace('/', '.')}|$currentLine|$callName"

    /**
     * Runtime identity for a source Compose-State factory call.
     *
     * Source file + line + factory alone is not sufficient with Kotlin inline/composable lowering:
     * synthetic/inlined code can inherit the caller's line table and make an unrelated MutableState
     * look as if it came from the same source declaration. Keep the source function and bytecode owner
     * in the wire key. The IDE uses the source function as part of the exact match and keeps the owner
     * as diagnostic provenance.
     */
    private fun dynamicComposeStateKey(callName: String): String =
        "@composestate2|${sourceFileName.orEmpty()}|$currentLine|$callName|${composeSourceFunctionName()}|${classInternalName.replace('/', '.')}"

    private fun composeSourceFunctionName(): String {
        if (methodName != "invoke" && methodName != "invokeSuspend") return methodName

        // Kotlin-generated lambda/coroutine classes normally encode the enclosing source function:
        //   AppScrollbarsKt$scrollbarThumbColor$1.invokeSuspend
        // Recover that name so it still matches the PSI source declaration.
        val parts = classInternalName.substringAfterLast('/').split('$').drop(1)
        return parts.asReversed().firstOrNull { part ->
            part.isNotBlank() && !part.all { it.isDigit() } &&
                part !in setOf("Companion", "DefaultImpls")
        } ?: methodName
    }

    private fun getterPropertyName(getter: String): String {
        val raw = getter.removePrefix("get")
        if (raw.isEmpty()) return getter
        return raw.substring(0, 1).lowercase() + raw.substring(1)
    }

    private fun isFlowReturnType(descriptor: String): Boolean =
        Type.getReturnType(descriptor).descriptor in FLOW_DESCRIPTORS

    private fun isComposeStateReturnType(descriptor: String): Boolean =
        Type.getReturnType(descriptor).descriptor in COMPOSE_STATE_DESCRIPTORS

    private fun isTraceableFlowDescriptor(descriptor: String): Boolean =
        descriptor in FLOW_DESCRIPTORS || descriptor in COMPOSE_STATE_DESCRIPTORS

    private fun isFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("StateFlow") || owner.contains("SharedFlow"))

    private fun isMutableFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("MutableStateFlow") || owner.contains("StateFlowImpl"))

    private fun isMutableSharedFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("MutableSharedFlow") || owner.contains("SharedFlowImpl"))

    private fun isFlowCollectorOwner(owner: String): Boolean =
        owner == "kotlinx/coroutines/flow/FlowCollector" ||
            owner.startsWith("kotlinx/coroutines/flow/") && owner.contains("Collector")

    private fun instrumentComposeDelegatedRead(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
        site: String,
    ) {
        val argumentTypes = Type.getArgumentTypes(descriptor)
        val locals = IntArray(argumentTypes.size)
        for (index in argumentTypes.indices.reversed()) {
            locals[index] = newLocal(argumentTypes[index])
            storeLocal(locals[index])
        }

        // Do not fall back to a broad source-property identity. A local name such as `state` is
        // extremely common inside inlined Compose/library code. The exact source access line plus
        // read/write kind is what lets the IDE prove that this runtime delegate belongs to the PSI
        // property it is displaying.
        delegatedPropertyArgumentIndex(argumentTypes)?.let { propertyIndex ->
            loadLocal(locals[0])
            loadLocal(locals[propertyIndex])
            push(sourceFileName.orEmpty())
            push(composeSourceFunctionName())
            push(currentLine)
            push(site)
            invokeRuntime(
                "recordComposeDelegatedRead",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
            )
        }

        for (index in argumentTypes.indices) loadLocal(locals[index])
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    private fun instrumentComposeDelegatedWrite(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
        site: String,
    ) {
        val argumentTypes = Type.getArgumentTypes(descriptor)
        val locals = IntArray(argumentTypes.size)
        for (index in argumentTypes.indices.reversed()) {
            locals[index] = newLocal(argumentTypes[index])
            storeLocal(locals[index])
        }

        val propertyIndex = delegatedPropertyArgumentIndex(argumentTypes)
        if (propertyIndex != null) {
            loadLocal(locals[0])
            loadLocal(locals[propertyIndex])
            push(sourceFileName.orEmpty())
            push(composeSourceFunctionName())
            push(currentLine)
            push(site)
            invokeRuntime(
                "beforeComposeDelegatedWrite",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
            )
        }

        for (index in argumentTypes.indices) loadLocal(locals[index])
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

        if (propertyIndex != null) {
            loadLocal(locals[0])
            loadLocal(locals[propertyIndex])
            push(sourceFileName.orEmpty())
            push(composeSourceFunctionName())
            push(currentLine)
            push(site)
            invokeRuntime(
                "afterComposeDelegatedWrite",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
            )
        }
    }

    private fun delegatedPropertyArgumentIndex(argumentTypes: Array<Type>): Int? =
        argumentTypes.indexOfFirst { type ->
            type.sort == Type.OBJECT &&
                (type.internalName == "kotlin/reflect/KProperty" ||
                    type.internalName.startsWith("kotlin/reflect/KProperty"))
        }.takeIf { it >= 0 }

    private fun isComposeDelegatedStateRead(owner: String, name: String, descriptor: String): Boolean {
        if (!owner.startsWith("androidx/compose/runtime/") || name != "getValue") return false
        val args = Type.getArgumentTypes(descriptor)
        if (args.size < 2 || args[0].descriptor !in COMPOSE_STATE_DESCRIPTORS) return false
        return Type.getReturnType(descriptor) != Type.VOID_TYPE
    }

    private fun isComposeDelegatedStateWrite(owner: String, name: String, descriptor: String): Boolean {
        if (!owner.startsWith("androidx/compose/runtime/") || name != "setValue") return false
        val args = Type.getArgumentTypes(descriptor)
        if (args.size < 2 || args[0].descriptor !in COMPOSE_STATE_DESCRIPTORS) return false
        return Type.getReturnType(descriptor) == Type.VOID_TYPE
    }

    private fun isComposeRuntimeStateOwner(owner: String): Boolean =
        owner.startsWith("androidx/compose/runtime/") &&
            (owner.contains("State") || owner.contains("Snapshot"))

    private fun isComposeStateReadAccessor(owner: String, name: String, descriptor: String): Boolean {
        if (!isComposeRuntimeStateOwner(owner) || Type.getArgumentTypes(descriptor).isNotEmpty()) return false
        return when (name) {
            "getValue" -> descriptor == "()Ljava/lang/Object;"
            "getIntValue" -> descriptor == "()I"
            "getLongValue" -> descriptor == "()J"
            "getFloatValue" -> descriptor == "()F"
            "getDoubleValue" -> descriptor == "()D"
            else -> false
        }
    }

    private fun isComposeStateWriteAccessor(owner: String, name: String, descriptor: String): Boolean {
        if (!isComposeRuntimeStateOwner(owner) || Type.getReturnType(descriptor) != Type.VOID_TYPE) return false
        if (Type.getArgumentTypes(descriptor).size != 1) return false
        return when (name) {
            "setValue" -> descriptor == "(Ljava/lang/Object;)V"
            "setIntValue" -> descriptor == "(I)V"
            "setLongValue" -> descriptor == "(J)V"
            "setFloatValue" -> descriptor == "(F)V"
            "setDoubleValue" -> descriptor == "(D)V"
            else -> false
        }
    }

    companion object {
        private const val RUNTIME_INTERNAL_NAME = "com/oskiapps/flowgraph/runtime/FlowGraphAutoRuntime"
        private const val FLOW_COLLECT_DESCRIPTOR =
            "(Lkotlinx/coroutines/flow/FlowCollector;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        private const val FLOW_COLLECTOR_EMIT_DESCRIPTOR =
            "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        private const val SUSPENDING_EMIT_DESCRIPTOR = FLOW_COLLECTOR_EMIT_DESCRIPTOR
        private val FLOW_DESCRIPTORS = setOf(
            "Lkotlinx/coroutines/flow/Flow;",
            "Lkotlinx/coroutines/flow/StateFlow;",
            "Lkotlinx/coroutines/flow/MutableStateFlow;",
            "Lkotlinx/coroutines/flow/SharedFlow;",
            "Lkotlinx/coroutines/flow/MutableSharedFlow;",
        )
        private val COMPOSE_STATE_DESCRIPTORS = setOf(
            "Landroidx/compose/runtime/State;",
            "Landroidx/compose/runtime/MutableState;",
            "Landroidx/compose/runtime/IntState;",
            "Landroidx/compose/runtime/MutableIntState;",
            "Landroidx/compose/runtime/LongState;",
            "Landroidx/compose/runtime/MutableLongState;",
            "Landroidx/compose/runtime/FloatState;",
            "Landroidx/compose/runtime/MutableFloatState;",
            "Landroidx/compose/runtime/DoubleState;",
            "Landroidx/compose/runtime/MutableDoubleState;",
        )
    }
}
