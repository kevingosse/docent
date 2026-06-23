plugins {
    // Lets Gradle auto-provision a JDK 21 toolchain if the local one isn't suitable.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "code-review-docent"
