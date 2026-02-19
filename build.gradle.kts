plugins {
    kotlin("multiplatform") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
}

allprojects {
    group = "systems.greynet"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
