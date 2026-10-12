package com.oskiapps.flowgraph.instrumentation

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlowGraphRuntimeDependencyTest {
    @Test
    fun `injects exactly one version-matched runtime only into debug`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("debugImplementation")
        project.configurations.create("releaseImplementation")
        val coordinates = FlowGraphRuntimeDependency.notation()
        FlowGraphRuntimeDependency.install(project)
        FlowGraphRuntimeDependency.install(project)
        val injected = project.configurations.getByName("debugImplementation").dependencies
        assertEquals(1, injected.size)
        val dependency = injected.single()
        assertEquals(coordinates, "${dependency.group}:${dependency.name}:${dependency.version}")
        assertTrue(project.configurations.getByName("releaseImplementation").dependencies.isEmpty())
    }

    @Test
    fun `rejects legacy single-artifact runtime to prevent duplicate classes`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("debugImplementation")
        project.dependencies.add("debugImplementation", "com.github.elivity:flow-graph:0.20.7")
        assertFailsWith<GradleException> { FlowGraphRuntimeDependency.install(project) }
    }

    @Test
    fun `rejects mismatched explicitly configured runtime version`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("debugImplementation")
        val parts = FlowGraphRuntimeDependency.notation().split(':')
        project.dependencies.add("debugImplementation", "${parts[0]}:${parts[1]}:0.0.1")
        assertFailsWith<GradleException> { FlowGraphRuntimeDependency.install(project) }
    }
}
