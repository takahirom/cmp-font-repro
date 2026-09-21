plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.compose") version "1.13.0-alpha01"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    testImplementation(kotlin("test-junit"))
}
tasks.test {
    useJUnit()
    // skTextStylesCache is a file-level global, so one scenario would poison the next.
    forkEvery = 1
    testLogging { showStandardStreams = true; events("passed", "failed") }
}
