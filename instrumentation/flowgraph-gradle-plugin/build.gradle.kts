plugins {
    kotlin("jvm") version "2.1.20"
    `java-gradle-plugin`
}


dependencies {
    // Compile against an older stable AGP API. The instrumentation API used here exists in 8.x
    // and remains available in AGP 9.x through Variant.instrumentation.
    compileOnly("com.android.tools.build:gradle-api:8.9.0")
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("flowGraphInstrumentation") {
            id = "dev.flowgraph.instrumentation"
            implementationClass = "dev.flowgraph.instrumentation.FlowGraphInstrumentationPlugin"
            displayName = "Flow Graph automatic runtime instrumentation"
            description = "Debug-only ASM instrumentation for Flow Graph StateFlow, SharedFlow and cold Flow runtime tracing."
        }
    }
}
