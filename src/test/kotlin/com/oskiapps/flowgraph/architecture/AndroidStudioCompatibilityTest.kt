package com.oskiapps.flowgraph.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidStudioCompatibilityTest {
    @Test
    fun `Rabbit compatibility is declared and binary verification is configured`() {
        val gradle = Files.readString(Path.of("build.gradle.kts"))
        assertTrue("androidStudio(\"2025.3.4.7\")" in gradle,
            "Keep the oldest supported Android Studio as the compilation target")
        assertTrue("sinceBuild = \"253.32098\"" in gradle)
        assertTrue("untilBuild = \"262.*\"" in gradle)
        assertTrue("pluginVerifier()" in gradle)
        assertTrue("create(IntelliJPlatformType.AndroidStudio, \"2026.2.1.8\")" in gradle)
    }

    @Test
    fun `build and release workflows check Rabbit compatibility`() {
        for (workflow in listOf("build.yml", "release.yml")) {
            val file = Path.of(".github/workflows", workflow)
            assertTrue("gradle verifyPlugin" in Files.readString(file),
                "$workflow should verify Rabbit compatibility before publishing")
        }
    }
}
