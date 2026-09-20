import java.util.Properties
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "dev.flowgraph"

val releasePropertiesFile = layout.projectDirectory.file("../../gradle.properties").asFile
val releaseProperties = Properties().apply {
    releasePropertiesFile.inputStream().use { input ->
        load(input)
    }
}
val flowGraphVersion = releaseProperties.getProperty("flowGraphVersion")
    ?: error("flowGraphVersion missing from ../../gradle.properties")
val projectUrl = releaseProperties.getProperty("flowGraphProjectUrl")
    ?: error("flowGraphProjectUrl missing from ../../gradle.properties")
val scmUrl = releaseProperties.getProperty("flowGraphScmUrl")
    ?: error("flowGraphScmUrl missing from ../../gradle.properties")

version = flowGraphVersion

dependencies {
    // Only APIs are needed while compiling the Gradle plugin. The consuming Android app
    // declares flowgraph-runtime explicitly as debugImplementation.
    compileOnly("com.android.tools.build:gradle-api:9.3.2")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    website = projectUrl
    vcsUrl = scmUrl

    plugins {
        create("flowGraphInstrumentation") {
            id = "dev.flowgraph.instrumentation"
            implementationClass = "dev.flowgraph.instrumentation.FlowGraphInstrumentationPlugin"
            displayName = "Flow Graph Runtime Instrumentation"
            description = "Debug-only Android bytecode instrumentation for Flow Graph runtime tracing."
            tags.set(listOf("android", "kotlin", "flow", "stateflow", "compose", "profiling"))
        }
    }
}
