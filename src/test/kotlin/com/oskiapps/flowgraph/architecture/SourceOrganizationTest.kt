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

    private fun sourceFiles(): List<Path> = Files.walk(sourceRoot).use { walk ->
        walk.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .toList()
    }
}
