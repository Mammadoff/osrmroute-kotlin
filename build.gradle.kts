plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "2.2.0"
}
group = "com.osrmroute"
version = "0.1.0"
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test { maxParallelForks = 1 }

java { withSourcesJar() }
val documentationJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
}
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            artifact(documentationJar)
            artifactId = "osrmroute-kotlin"
            pom {
                name.set("OSRMRoute Kotlin SDK")
                description.set("Typed asynchronous OSRMRoute geographic API client for Android and JVM")
                url.set("https://github.com/Mammadoff/osrmroute-kotlin")
                licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit/") } }
                developers { developer { id.set("osrmroute"); name.set("OSRMRoute"); url.set("https://osrmroute.com") } }
                scm {
                    url.set("https://github.com/Mammadoff/osrmroute-kotlin")
                    connection.set("scm:git:https://github.com/Mammadoff/osrmroute-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Mammadoff/osrmroute-kotlin.git")
                }
            }
        }
    }
    repositories {
        maven { name = "staging"; url = uri(layout.buildDirectory.dir("central-staging")) }
    }
}
// Publishing credentials never belong in source or Gradle project properties.
val signingKey = providers.environmentVariable("OSRM_SIGNING_KEY")
if (signingKey.isPresent) {
    signing {
        useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("OSRM_SIGNING_PASSWORD").orNull)
        sign(publishing.publications["sdk"])
    }
}
