package com.oskiapps.flowgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards against accidentally reintroducing the pre-refactor implementations. */
class RefactorIntegrityTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/oskiapps/flowgraph")

    @Test
    fun `legacy numbered implementation fragments are absent`() {
        val legacy = listOf(
            "AnalysisOperations01.kt", "AnalysisOperations02.kt",
            "FlowGraphOperations01.kt", "FlowGraphOperations02.kt"
        )
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .map { it.fileName.toString() }.toList()
        }
        assertTrue(legacy.none { it in files },
            "Legacy implementations duplicate the responsibility-based modules")
    }

    @Test
    fun `extracted analyzer entrypoints have one implementation`() {
        val analysis = sourceRoot.resolve("analysis")
        listOf("analyzeFrom", "analyzeAllProjectFlows", "buildGraph",
            "discoverProjectSymbols", "propertyId", "sourceLocation")
            .forEach { name ->
                val declarations = Files.walk(analysis).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .map { file ->
                            val pattern = Regex(
                                "fun\\s+AnalysisApiFlowAnalyzer\\.$name\\s*\\("
                            )
                            file to pattern.findAll(Files.readString(file)).count()
                        }.filter { it.second > 0 }.toList()
                }
                // buildGraph intentionally has a single-root and a multi-root overload.
                val expected = if (name == "buildGraph") 2 else 1
                assertEquals(expected, declarations.sumOf { it.second },
                    "Unexpected duplicate or missing $name declarations: $declarations")
            }
    }

    @Test
    fun `graph lookup entrypoints are not duplicated`() {
        val model = sourceRoot.resolve("model")
        listOf("node", "incomingEdges", "fieldsForState", "nodeByRuntimeKey")
            .forEach { name ->
                val pattern = Regex("fun\\s+FlowGraph\\.$name\\s*\\(")
                val count = Files.walk(model).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .mapToInt { pattern.findAll(Files.readString(it)).count() }.sum()
                }
                assertEquals(1, count, "Unexpected duplicate or missing $name")
            }
    }
}
