plugins {
    // Lets the Gradle Java toolchain auto-download a JDK 21 if the host
    // doesn't already have one, so builds are reproducible across machines.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "order-router"
