plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.compose") version "1.13.0-alpha01"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test-junit"))
}
tasks.test { useJUnit(); testLogging { showStandardStreams = true; events("passed","failed") } }
