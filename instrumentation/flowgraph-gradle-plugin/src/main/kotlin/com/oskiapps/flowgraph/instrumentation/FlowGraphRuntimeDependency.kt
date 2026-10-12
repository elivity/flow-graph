package com.oskiapps.flowgraph.instrumentation

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Properties

/** The plugin release carries a compatible runtime; never use the consumer project's version. */
internal object FlowGraphRuntimeDependency {
    private const val CONFIGURATION = "debugImplementation"
    private const val LEGACY_GROUP = "com.github.elivity"
    private const val LEGACY_ARTIFACT = "flow-graph"

    fun notation(): String {
        val properties = Properties()
        val resource = javaClass.getResourceAsStream("/flowgraph-instrumentation.properties")
            ?: throw GradleException("Flow Graph: missing packaged runtime coordinates")
        resource.use { properties.load(it) }
        return properties.getProperty("runtimeDependency")
            ?.takeIf { it.split(':').size == 3 && !it.contains('$') }
            ?: throw GradleException("Flow Graph: invalid packaged runtime coordinates")
    }

    fun install(project: Project) {
        val coordinates = notation()
        val (group, artifact, version) = coordinates.split(':')
        val configuration = project.configurations.findByName(CONFIGURATION)
            ?: throw GradleException("Flow Graph: Android debugImplementation configuration not found")
        val dependencies = configuration.dependencies.toList()
        if (dependencies.any { it.group == LEGACY_GROUP && it.name == LEGACY_ARTIFACT }) {
            throw GradleException(
                "Flow Graph: remove the old debugImplementation(\"$LEGACY_GROUP:$LEGACY_ARTIFACT:...\") " +
                    "dependency; the instrumentation plugin now adds the runtime automatically.",
            )
        }
        val existing = dependencies.filter { it.group == group && it.name == artifact }
        if (existing.any { it.version != version }) {
            throw GradleException(
                "Flow Graph: runtime version mismatch. Expected $coordinates; remove the manual " +
                    "runtime dependency and let the plugin select the matching version.",
            )
        }
        if (existing.isEmpty()) {
            project.dependencies.add(CONFIGURATION, coordinates)
            project.logger.info("Flow Graph: adding debug-only runtime $coordinates")
        }
    }
}
