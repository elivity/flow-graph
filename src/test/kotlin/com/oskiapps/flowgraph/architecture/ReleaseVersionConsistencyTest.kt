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
            // A previous release may be named when describing when support began;
            // Release commands, plugin versions, and dependencies must be current.
            val oldVersions = staleReleaseVersions(text, version)
            assertTrue(oldVersions.isEmpty(),
                "$filename has stale version references: $oldVersions")
        }
    }

    @Test
    fun `historical compatibility notes do not mask stale installation versions`() {
        val current = "0.20.11"
        assertTrue(staleReleaseVersions(
            "Flow Graph `0.20.9` and later support Rabbit 1.", current
        ).isEmpty())
        assertTrue("0.20.9" in staleReleaseVersions(
            "id(\"com.oskiapps.flowgraph.instrumentation\") version \"0.20.9\"", current
        ))
        assertTrue("0.20.9" in staleReleaseVersions(
            "com.github.elivity.flow-graph:flowgraph-runtime:0.20.9", current
        ))
    }

    private fun staleReleaseVersions(text: String, current: String): Set<String> {
        // Documentation may legitimately explain that a feature arrived in an older
        // version (e.g. "Flow Graph `0.20.9` and later"). Exclude only this precise
        // historical form, not stale versions in installation or publishing snippets.
        val historicalNote = Regex("Flow Graph `0\\.20\\.\\d+` and later")
        val instructions = historicalNote.replace(text, "")
        return Regex("0\\.20\\.\\d+").findAll(instructions)
            .map { it.value }.toSet() - current
    }

    @Test
    fun `jitpack uses repository wrapper to publish both modules`() {
        val jitpack = Files.readString(Path.of("jitpack.yml"))
        assertTrue("./gradlew -p instrumentation" in jitpack,
            "JitPack should use the same pinned Gradle wrapper as the repository")
        assertTrue(":flowgraph-runtime:publishToMavenLocal" in jitpack)
        assertTrue(":flowgraph-gradle-plugin:publishToMavenLocal" in jitpack)
        assertTrue("-PflowGraphJitPack=true" in jitpack)
        assertFalse("sdk install gradle" in jitpack,
            "JitPack should not install a separate Gradle version")
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
