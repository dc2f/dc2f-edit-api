import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Shared with dc2f.kt and dc2f-common: this module is pinned to the others at
// its own `$version`, so a release only resolves if all three carry the same
// number. 0.3.0 is the first one ahead of the 0.2.1 that has been on Central
// since 2019. A release overrides it: -Pversion=0.3.0.
val dc2fVersion = "0.3.0-SNAPSHOT"

group = "com.dc2f"
if (version == "unspecified") {
    version = dc2fVersion
}

val jacksonVersion = "2.9.9"


plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin on the JVM.
    id("org.jetbrains.kotlin.jvm").version("2.4.10")
    // The io.ratpack.ratpack-java plugin is gone: every release of it, up to
    // and including 2.0.0-rc-1, calls the `compile` configuration internally,
    // which Gradle removed in 7.0. It only worked this long because the Kotlin
    // plugin re-registered `compile`, and Kotlin 1.9 stopped. ratpack-core is
    // declared directly below instead.

    // Apply the application plugin to add support for building a CLI application.
    application
    // Provides api(), which replaces the compile() configuration that the
    // Kotlin plugin used to register and dropped in 1.9.
    `java-library`
    `maven-publish`
    signing
}

tasks.withType<KotlinCompile> {
    // kotlinOptions was removed in Kotlin 2.2; compilerOptions replaces it.
    // -Xjvm-default likewise became -jvm-default, where "no-compatibility"
    // corresponds to the old "all".
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

// Pins both the Java and Kotlin targets together. Setting only
// compilerOptions.jvmTarget lets compileJava follow whatever JDK is running and
// Gradle then rejects the mismatch. Gradle 9 requires JDK 17 to run anyway.
kotlin {
    jvmToolchain(17)
}

repositories {
    // jcenter() dropped: shut down in 2021 and removed in Gradle 9.
    mavenCentral()
    // Was oss.sonatype.org's public group, which 404s since OSSRH's EOL. Its
    // half that mavenCentral() does not already cover is the snapshot repo.
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    // Was supplied by the io.ratpack.ratpack-java plugin, which had to go (see
    // the plugins block). api, not implementation: RatpackServer and friends
    // appear in this module's public API.
    // 1.7.3, matching the version the removed plugin applied -- not the newest
    // 1.x. Ratpack drags in its own Jackson, and 1.9.0 pulled one new enough to
    // change YAMLGenerator._writeScalar's signature out from under
    // Dc2fYAMLFactory. Bumping ratpack is a separate change from this migration.
    api("io.ratpack:ratpack-core:1.7.3")

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.3.1")

//
//    // Ktor
//    implementation("io.ktor:ktor-server-core:$ktorVersion")
//    implementation("io.ktor:ktor-server-netty:$ktorVersion")
//    implementation("io.ktor:ktor-jackson:$ktorVersion")
//    implementation("io.ktor:ktor-websockets:$ktorVersion")

    // logging
    api("io.github.microutils:kotlin-logging:1.4.9")
    api("org.slf4j:jul-to-slf4j:1.7.25")
    api("ch.qos.logback:logback-classic:1.2.1")

    // dc2f
    implementation("com.dc2f:dc2f:$version")
//    implementation("app.anlage.site:finalyzer-dc2f-site:0.0.1-SNAPSHOT")

    // yaml deserialize
    api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    constraints {
        api("org.yaml:snakeyaml:1.24")
        implementation("org.yaml:snakeyaml:1.24")
    }

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
//
//allprojects {
//    dependencies {
//        constraints {
//            compile("org.yaml:snakeyaml:1.24")
//            implementation("org.yaml:snakeyaml:1.24")
//        }
//
//    }
//}


application {
    // Define the main class for the application.
    // mainClassName was removed in Gradle 8.
    mainClass.set("com.dc2f.api.edit.AppKt")
}







val secretConfig = file("../dc2f.kt/_tools/secrets/build_secrets.gradle.kts")
if (secretConfig.exists()) {
    apply { from(secretConfig) }
    allprojects {
        extra["signing.secretKeyRingFile"] = "../dc2f.kt/" + extra["signing.secretKeyRingFile"]
    }
} else {
    println("Warning: Secrets do not exist, maven publish will not be possible.")
}

tasks.register<Jar>("sourcesJar") {
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}
tasks.register<Jar>("javadocJar") {
    from(tasks.javadoc)
    archiveClassifier.set("javadoc")
}

val repoName = "dc2f-edit-api"
val projectName = repoName

publishing {
    publications {
        create<MavenPublication>("mavenCentralJava") {
            from(components["java"])
            artifact(tasks["sourcesJar"])
//            artifact(sourcesJar.get())
            artifact(tasks["javadocJar"])


            pom {
                name.set(projectName)
                description.set("Type safe static website generator")
                url.set("https://github.com/dc2f/$repoName/")
//                properties.set(mapOf(
//                    "myProp" to "value",
//                    "prop.with.dots" to "anotherValue"
//                ))
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("hpoul")
                        name.set("Herbert Poul")
                        email.set("herbert@poul.at")
                    }
                }
                scm {
                    connection.set("scm:git:http://github.com/dc2f/$repoName.git")
                    developerConnection.set("scm:git:ssh://github.com/dc2f/$repoName.git")
                    url.set("https://github.com/dc2f/$repoName")
                }
            }

        }
    }
    repositories {
        maven {
            name = "central"
            // OSSRH was decommissioned on 2025-06-30; this is Sonatype's
            // replacement for a maven-publish setup -- the same deploy layout
            // in front of the Central Portal, authenticated with a Portal user
            // token. See dc2f.kt's build.gradle.kts and _tools/central-publish.sh:
            // a release staged here is invisible in the Portal until that script
            // posts it, from the same IP that uploaded.
            val releasesRepoUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotsRepoUrl = "https://central.sonatype.com/repository/maven-snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            println("using $url")
            credentials {
                username = (project.findProperty("centralUsername")
                    ?: System.getenv("CENTRAL_TOKEN_USERNAME")
                    ?: project.findProperty("ossrhUsername")) as? String
                password = (project.findProperty("centralPassword")
                    ?: System.getenv("CENTRAL_TOKEN_PASSWORD")
                    ?: project.findProperty("ossrhPassword")) as? String
            }

        }
//        maven {
//            name = "github"
//            url = uri("https://maven.pkg.github.com/dc2f")
//            credentials {
//                username = project.properties["dc2f.github.username"] as? String
//                password = project.properties["dc2f.github.password"] as? String
//            }
//        }
    }
}

signing {
    // Away from this checkout there is no gpg keyring and no decrypted
    // build_secrets.gradle.kts next door; the armoured key arrives in
    // SIGNING_KEY instead.
    val signingKey = System.getenv("SIGNING_KEY")
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, System.getenv("SIGNING_PASSWORD"))
    }
    sign(publishing.publications["mavenCentralJava"])
}

