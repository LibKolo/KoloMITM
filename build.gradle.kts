plugins {
    `maven-publish`
    id("java")
    id("java-library")
    id("application")
    id("com.gradleup.shadow") version "9.2.2"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.3.0"
}

group = "io.github.mucute.qwq.kolomitm"
version = "1.0-SNAPSHOT"

application {
    applicationName = "KoloMITM"
    mainClass = "io.github.mucute.qwq.kolomitm.KoloMITM"
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "io.github.mucute.qwq.kolomitm.KoloMITM"
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven {
        name = "opencollabRepositoryMavenSnapshots"
        url = uri("https://repo.opencollab.dev/maven-snapshots")
    }
    maven {
        name = "opencollabRepositoryMavenReleases"
        url = uri("https://repo.opencollab.dev/maven-releases")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                packaging = "jar"
                url.set("https://github.com/mucute-qwq/KoloMITM")

                scm {
                    connection.set("scm:git:git://github.com/mucute-qwq/KoloMITM.git")
                    developerConnection.set("scm:git:ssh://github.com/mucute-qwq/KoloMITM.git")
                    url.set("https://github.com/mucute-qwq/KoloMITM")
                }

                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        name.set("mucute-qwq")
                    }
                }

                ciManagement {
                    system.set("GitHub Actions")
                    url.set("https://github.com/mucute-qwq/KoloMITM/actions")
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/mucute-qwq/KoloMITM/issues")
                }
            }
        }
    }
}

val networkIncludedBuild = gradle.includedBuild("network")
val protocolIncludedBuild = gradle.includedBuild("protocol")

tasks["publishMavenPublicationToMavenLocal"].dependsOn(
    networkIncludedBuild.task(":codec-query:publishMavenPublicationToMavenLocal"),
    networkIncludedBuild.task(":codec-rcon:publishMavenPublicationToMavenLocal"),
    networkIncludedBuild.task(":transport-raknet:publishMavenPublicationToMavenLocal"),
    protocolIncludedBuild.task(":adventure:publishMavenPublicationToMavenLocal"),
    protocolIncludedBuild.task(":bedrock-codec:publishMavenPublicationToMavenLocal"),
    protocolIncludedBuild.task(":common:publishMavenPublicationToMavenLocal"),
    protocolIncludedBuild.task(":bedrock-connection:publishMavenPublicationToMavenLocal")
)

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.adventure)
    api(libs.bedrock.codec)
    api(libs.common)
    api(libs.bedrock.connection)
    api(libs.transport.raknet)
    api(libs.kotlinx.coroutines)
    api(libs.net.raphimc.minecraftauth)
    api(libs.jackson.databind)
    api(platform(libs.log4j.bom))
    api(libs.log4j.api)
    api(libs.log4j.core)
    testApi(kotlin("test"))

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}