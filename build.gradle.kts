import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "com.dc2f"
version = "0.0.1-SNAPSHOT"

val jacksonVersion = "2.9.9"

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin on the JVM.
    id("org.jetbrains.kotlin.jvm").version("1.3.20")
    id("io.ratpack.ratpack-java").version("1.7.3")

    // Apply the application plugin to add support for building a CLI application.
    application
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

repositories {
    mavenCentral()
    // Use jcenter for resolving your dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation(kotlin("reflect"))
//
//    // Ktor
//    implementation("io.ktor:ktor-server-core:$ktorVersion")
//    implementation("io.ktor:ktor-server-netty:$ktorVersion")
//    implementation("io.ktor:ktor-jackson:$ktorVersion")
//    implementation("io.ktor:ktor-websockets:$ktorVersion")

    // logging
    compile("io.github.microutils:kotlin-logging:1.4.9")
    compile("org.slf4j:jul-to-slf4j:1.7.25")
    compile("ch.qos.logback:logback-classic:1.2.1")

    // dc2f
    implementation("com.dc2f:dc2f:0.1.3-SNAPSHOT")
//    implementation("app.anlage.site:finalyzer-dc2f-site:0.0.1-SNAPSHOT")

    // yaml deserialize
    compile("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    compile("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    constraints {
        compile("org.yaml:snakeyaml:1.24")
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
    mainClassName = "com.dc2f.api.edit.AppKt"
}
