import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktorVersion = "1.2.0"


plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin on the JVM.
    id("org.jetbrains.kotlin.jvm")
}


repositories {
    mavenCentral()
    // Use jcenter for resolving your dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    implementation(project(":"))
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Ktor
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-jackson:$ktorVersion")
    implementation("io.ktor:ktor-websockets:$ktorVersion")

    // logging
    compile("io.github.microutils:kotlin-logging:1.4.9")
    compile("org.slf4j:jul-to-slf4j:1.7.25")
    compile("ch.qos.logback:logback-classic:1.2.1")

    implementation("com.dc2f:dc2f:[0.1.3-SNAPSHOT")
}
