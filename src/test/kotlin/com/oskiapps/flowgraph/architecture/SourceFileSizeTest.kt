package com.oskiapps.flowgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SourceFileSizeTest {
    @Test
    fun `non runtime Kotlin sources stay within 1000 lines`() {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))
        val tooLarge = roots.filter { Files.exists(it) }.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .filter { "runtime" !in it.iterator().asSequence().map(Path::toString).toList() }
                    .map { path -> path to Files.lines(path).use { it.count() } }
                    .filter { it.second > 1000L }
                    .toList()
            }
        }
        assertTrue(tooLarge.isEmpty(), "Files exceeding 1000 lines: $tooLarge")
    }
}
