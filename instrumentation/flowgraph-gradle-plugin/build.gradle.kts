import java.util.Properties
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradle.plugin-publish") version "2.2.1"
    `maven-publish`
}

// Plugin Portal uses the canonical ID namespace. JitPack needs the repository-qualified
// Maven group shared by the runtime and the plugin implementation.
val jitpackGroup = "com.github.elivity.flow-graph"
val jitpackBuild = providers.gradleProperty("flowGraphJitPack")
    .map(String::toBoolean).getOrElse(false)
group = if (jitpackBuild) jitpackGroup else "com.oskiapps.flowgraph"

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
    // Only APIs are needed while compiling the Gradle plugin. The plugin adds the
    // matching flowgraph-runtime artifact to debugImplementation automatically.
    compileOnly("com.android.tools.build:gradle-api:9.3.2")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// Include the release's runtime coordinates in the plugin JAR, not the app's
// project.version (which belongs to the consuming Android application).
val runtimeDependency = "$jitpackGroup:flowgraph-runtime:$flowGraphVersion"
tasks.processResources {
    inputs.property("flowGraphRuntimeDependency", runtimeDependency)
    filesMatching("flowgraph-instrumentation.properties") {
        expand(mapOf("runtimeDependency" to runtimeDependency))
    }
}


gradlePlugin {
    website = projectUrl
    vcsUrl = scmUrl

    plugins {
        create("flowGraphInstrumentation") {
            id = "com.oskiapps.flowgraph.instrumentation"
            implementationClass = "com.oskiapps.flowgraph.instrumentation.FlowGraphInstrumentationPlugin"
            displayName = "Flow Graph Runtime Instrumentation"
            description = "Debug-only Android bytecode instrumentation for Flow Graph runtime tracing."
            tags.set(listOf("android", "kotlin", "flow", "stateflow", "compose", "profiling"))
        }
    }
}
