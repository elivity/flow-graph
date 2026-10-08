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

