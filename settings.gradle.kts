plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KoloMITM"

includeBuild("network")
includeBuild("protocol")