import java.util.Properties
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

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
val developerId = releaseProperties.getProperty("flowGraphDeveloperId")
    ?: error("flowGraphDeveloperId missing from ../../gradle.properties")
val developerName = releaseProperties.getProperty("flowGraphDeveloperName")
    ?: error("flowGraphDeveloperName missing from ../../gradle.properties")
val licenseName = releaseProperties.getProperty("flowGraphLicenseName")
    ?: error("flowGraphLicenseName missing from ../../gradle.properties")
val licenseUrl = releaseProperties.getProperty("flowGraphLicenseUrl")
    ?: error("flowGraphLicenseUrl missing from ../../gradle.properties")
// JitPack's multi-module coordinates: com.github.<owner>.<repo>:<module>:<tag>.
// Keep this independent of Gradle's -Pgroup/-Partifact injected parameters.
val jitpackGroup = releaseProperties.getProperty("flowGraphJitPackGroup")
    ?: error("flowGraphJitPackGroup missing from ../../gradle.properties")
val jitpackBuild = providers.gradleProperty("flowGraphJitPack")
    .map(String::toBoolean).getOrElse(false)

group = if (jitpackBuild) jitpackGroup else "com.oskiapps.flowgraph"
version = flowGraphVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

publishing {
    publications {
        create<MavenPublication>("jitpack") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "flowgraph-runtime"
            version = project.version.toString()

            pom {
                name.set("Flow Graph Runtime")
                description.set(
                    "Debug runtime used by Flow Graph to stream Kotlin Flow, StateFlow, " +
                        "Compose state, and UI interaction events from Android apps.",
                )
                url.set(projectUrl)
                licenses {
                    license {
                        name.set(licenseName)
                        url.set(licenseUrl)
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set(developerId)
                        name.set(developerName)
                    }
                }
                scm {
                    url.set(projectUrl)
                    connection.set("scm:git:$scmUrl")
                    developerConnection.set("scm:git:$scmUrl")
                }
            }
        }
    }
}
