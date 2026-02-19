plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val ktorVersion = "3.1.1"

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("ch.qos.logback:logback-classic:1.5.16")
            }
        }
    }
}

tasks.register<JavaExec>("run") {
    mainClass.set("systems.greynet.app.MainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles
}
