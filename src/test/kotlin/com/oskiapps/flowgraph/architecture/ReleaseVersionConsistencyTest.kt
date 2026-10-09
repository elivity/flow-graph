package com.oskiapps.flowgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseVersionConsistencyTest {
    @Test
    fun `release documentation uses the root project version`() {
        val properties = Properties().apply {
            Files.newInputStream(Path.of("gradle.properties")).use(::load)
        }
        val version = requireNotNull(properties.getProperty("flowGraphVersion"))
        val releaseDocs = listOf(
            "README.md", "PUBLISHING.md", "docs/INSTALL.md",
            "instrumentation/README.md"
        )
        releaseDocs.forEach { filename ->
            val text = Files.readString(Path.of(filename))
            assertTrue(version in text, "$filename must mention release $version")
            // The full release references must not lag behind the project version.
            val oldVersions = Regex("0\\.20\\.\\d+").findAll(text).map { it.value }.toSet() - version
            assertTrue(oldVersions.isEmpty(), "$filename has stale version references: $oldVersions")
        }
    }

    @Test
    fun `ci obtains version from root properties`() {
        for (filename in listOf(".github/workflows/build.yml", ".github/workflows/release.yml")) {
            val text = Files.readString(Path.of(filename))
            assertTrue("flowGraphVersion" in text, "$filename must read flowGraphVersion")
            assertFalse(Regex("-Pversion=0\\.20\\.\\d+").containsMatchIn(text),
                "$filename must not hardcode a publication version")
        }
    }
}
