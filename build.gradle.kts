plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.flowgraph"
version = "0.14.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    testImplementation(kotlin("test"))

    intellijPlatform {
        // Target the same Android Studio platform as AI-253.32098.37
        // (Panda 4 / 2025.3.4). This is intentional: the Kotlin Analysis API
        // is supplied by Android Studio's bundled Kotlin plugin and must match
        // the IDE generation where the plugin will actually run.
        androidStudio("2025.3.4.7")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
    }
}

// IntelliJ Platform 2025.3 / Android Studio Panda uses Java 21.
kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "Flow Graph (Analysis API Prototype)"
        version = project.version.toString()
        ideaVersion {
            // The user's IDE is AI-253.32098.37. Do not advertise support for
            // earlier 253 builds because we compile against the 253.32098 API.
            sinceBuild = "253.32098"
            untilBuild = "261.*"
        }
        description = "K2 Analysis API Flow/StateFlow impact graph with symbolic simulation plus deep debug runtime tracing for StateFlow, SharedFlow, cold Flow collection and live overlays."
        vendor { name = "Prototype" }
    }
}
