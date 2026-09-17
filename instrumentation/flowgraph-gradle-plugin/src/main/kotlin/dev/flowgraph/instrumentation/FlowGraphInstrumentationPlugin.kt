package dev.flowgraph.instrumentation

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
            // The included instrumentation build also contains :flowgraph-runtime. Gradle composite
            // dependency substitution resolves this coordinate to that project automatically.
            project.dependencies.add("debugImplementation", "dev.flowgraph:flowgraph-runtime:0.11.3")

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
                    readTargets(project.rootProject.file(".flowgraph/targets.txt"))
                }
                variant.instrumentation.excludes.add("dev/flowgraph/runtime/**")
                variant.instrumentation.transformClassesWith(
                    FlowGraphClassVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { params ->
                    params.targets.set(targets)
                    params.traceReads.set(extension.traceReads)
                    params.traceColdFlows.set(extension.traceColdFlows)
                    params.traceSuspendingEmits.set(extension.traceSuspendingEmits)
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
    val instrumentAllEligibleFlows: Property<Boolean>

    @get:Input
    val instrumentAllWhenTargetsMissing: Property<Boolean>
}

abstract class FlowGraphClassVisitorFactory :
    AsmClassVisitorFactory<FlowGraphInstrumentationParameters> {

    override fun isInstrumentable(classData: ClassData): Boolean =
        !classData.className.startsWith("dev.flowgraph.runtime.")

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = FlowGraphClassVisitor(
        delegate = nextClassVisitor,
        targets = parameters.get().targets.get().toSet(),
        traceReads = parameters.get().traceReads.get(),
        traceColdFlows = parameters.get().traceColdFlows.get(),
        traceSuspendingEmits = parameters.get().traceSuspendingEmits.get(),
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
    private val instrumentAllEligibleFlows: Boolean,
    private val instrumentAllWhenTargetsMissing: Boolean,
) : ClassVisitor(Opcodes.ASM9, delegate) {

    private var classInternalName: String = "<unknown>"

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
            access = access,
            methodName = name,
            methodDescriptor = descriptor,
            classInternalName = classInternalName,
            targets = targets,
            traceReads = traceReads,
            traceColdFlows = traceColdFlows,
            traceSuspendingEmits = traceSuspendingEmits,
            instrumentAllEligibleFlows = instrumentAllEligibleFlows,
            instrumentAllWhenTargetsMissing = instrumentAllWhenTargetsMissing,
        )
    }
}

private class FlowGraphMethodVisitor(
    methodVisitor: MethodVisitor,
    access: Int,
    private val methodName: String,
    private val methodDescriptor: String,
    private val classInternalName: String,
    private val targets: Set<String>,
    private val traceReads: Boolean,
    private val traceColdFlows: Boolean,
    private val traceSuspendingEmits: Boolean,
    private val instrumentAllEligibleFlows: Boolean,
    private val instrumentAllWhenTargetsMissing: Boolean,
) : GeneratorAdapter(Opcodes.ASM9, methodVisitor, access, methodName, methodDescriptor) {

    private var currentLine: Int = -1

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

        if (traceReads && name == "getValue" && descriptor == "()Ljava/lang/Object;" && isFlowOwner(owner)) {
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

    private fun getterPropertyName(getter: String): String {
        val raw = getter.removePrefix("get")
        if (raw.isEmpty()) return getter
        return raw.substring(0, 1).lowercase() + raw.substring(1)
    }

    private fun isFlowReturnType(descriptor: String): Boolean =
        Type.getReturnType(descriptor).descriptor in TRACEABLE_FLOW_DESCRIPTORS

    private fun isTraceableFlowDescriptor(descriptor: String): Boolean = descriptor in TRACEABLE_FLOW_DESCRIPTORS

    private fun isFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("StateFlow") || owner.contains("SharedFlow"))

    private fun isMutableFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("MutableStateFlow") || owner.contains("StateFlowImpl"))

    private fun isMutableSharedFlowOwner(owner: String): Boolean = owner.startsWith("kotlinx/coroutines/flow/") &&
        (owner.contains("MutableSharedFlow") || owner.contains("SharedFlowImpl"))

    private fun isFlowCollectorOwner(owner: String): Boolean =
        owner == "kotlinx/coroutines/flow/FlowCollector" ||
            owner.startsWith("kotlinx/coroutines/flow/") && owner.contains("Collector")

    companion object {
        private const val RUNTIME_INTERNAL_NAME = "dev/flowgraph/runtime/FlowGraphAutoRuntime"
        private const val FLOW_COLLECT_DESCRIPTOR =
            "(Lkotlinx/coroutines/flow/FlowCollector;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        private const val FLOW_COLLECTOR_EMIT_DESCRIPTOR =
            "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        private const val SUSPENDING_EMIT_DESCRIPTOR = FLOW_COLLECTOR_EMIT_DESCRIPTOR
        private val TRACEABLE_FLOW_DESCRIPTORS = setOf(
            "Lkotlinx/coroutines/flow/Flow;",
            "Lkotlinx/coroutines/flow/StateFlow;",
            "Lkotlinx/coroutines/flow/MutableStateFlow;",
            "Lkotlinx/coroutines/flow/SharedFlow;",
            "Lkotlinx/coroutines/flow/MutableSharedFlow;",
        )
    }
}
