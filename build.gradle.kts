plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.oskiapps.flowgraph"
version = providers.gradleProperty("flowGraphVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    testImplementation(kotlin("test"))

    intellijPlatform {
        // Android Studio Panda / 253 platform used by the current plugin.
        androidStudio("2025.3.4.7")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = "Flow Graph"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "253.32098"
            untilBuild = "261.*"
        }
        description = "K2 Analysis API Flow/StateFlow impact graph with Compose propagation, live runtime tracing, UI interaction markers, timeline scrubbing, and live Compose inspection."
        vendor { name = "OskiApps" }
    }

    // After the first Marketplace upload, releases can be published with:
    //   ./gradlew publishPlugin -PintellijPlatformPublishingToken=...
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}
