package com.oskiapps.flowgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SourceOrganizationTest {
    private val sourceRoot = Path.of("src/main/kotlin")

    @Test
    fun `source files are named for their responsibility`() {
        val numberedFragments = Regex(".*(?:Actions|Operations)\\d{2}\\.kt$")
        val offenders = sourceFiles().filter { numberedFragments.matches(it.fileName.toString()) }
        assertTrue(offenders.isEmpty(), "Avoid mechanical code fragments: $offenders")
    }

    @Test
    fun `expected independent modules are present`() {
        val names = sourceFiles().map { it.fileName.toString() }.toSet()
        assertTrue(setOf(
            "CircuitEdgeRouting.kt", "CircuitLayerLayout.kt",
            "GraphViewHistory.kt", "NodeRenderer.kt",
            "LiveEventController.kt", "TimelinePlaybackController.kt",
            "ComposeHierarchyAnalysis.kt", "StateTransitionAnalysis.kt"
        ).all { it in names })
    }


    @Test
    fun `obsolete manual runtimes and maintenance scripts are not shipped`() {
        val obsolete = listOf("legacy-runtime-helper", "runtime-helper", "scripts", "modify.py")
        assertTrue(obsolete.none { Files.exists(Path.of(it)) },
            "Remove obsolete manual tracing helpers and one-off migration scripts")
    }

    @Test
    fun `compose state is off by default without hiding composable screens`() {
        val panel = Files.readString(sourceRoot.resolve("com/oskiapps/flowgraph/ui/FlowGraphPanel.kt"))
        val composeState = panel.substringAfter("internal val showComposeState = JCheckBox")
            .substringBefore("internal val showCoroutineFlows")
        val composeViews = panel.substringAfter("internal val showComposeViews = JCheckBox")
            .substringBefore("internal val projectComposablesOnly")
        assertTrue("isSelected = false" in composeState)
        assertTrue("isSelected = true" in composeViews)
    }

    private fun sourceFiles(): List<Path> = Files.walk(sourceRoot).use { walk ->
        walk.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .toList()
    }
}
