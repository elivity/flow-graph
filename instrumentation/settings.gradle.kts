pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// All dependency repositories for this included build live here.
// Subprojects must not declare repositories because FAIL_ON_PROJECT_REPOS is intentional.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flowgraph-instrumentation"
include(":flowgraph-runtime")
include(":flowgraph-gradle-plugin")
