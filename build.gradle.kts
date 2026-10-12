import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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
        // Compile against the oldest supported IDE to retain Panda compatibility.
        // Verify the packaged plugin against Rabbit 1 (2026.2.1) below.
        androidStudio("2025.3.4.7")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
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
            // Android Studio Rabbit 1 2026.2.1 uses IntelliJ build 262.9437.185.
            untilBuild = "262.*"
        }
        description = "K2 Analysis API Flow/StateFlow impact graph with Compose propagation, live runtime tracing, UI interaction markers, timeline scrubbing, and live Compose inspection."
        vendor { name = "OskiApps" }
    }

    pluginVerification {
        ides {
            // Verify the artifact built against Panda on the new stable Rabbit 1 IDE.
            create(IntelliJPlatformType.AndroidStudio, "2026.2.1.8")
        }
    }

    // After the first Marketplace upload, releases can be published with:
    //   ./gradlew publishPlugin -PintellijPlatformPublishingToken=...
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}
