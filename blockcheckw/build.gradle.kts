plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    linuxX64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheckw.main" } }
    }
    linuxArm64 {
        binaries { executable { entryPoint = "systems.greynet.blockcheckw.main" } }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.arrow.core)
        }
    }
}

data class DockerTestConfig(
    val target: String,
    val platform: String,
    val dockerfile: String,
    val linkTaskName: String,
    val binaryPath: String,
    val enabledInDockerTests: Boolean,
    val dockerTaskName: String = "dockerTest${target.replaceFirstChar { it.uppercase() }}",
)

val dockerTestConfigs = listOf(
    DockerTestConfig(
        target = "linuxX64",
        platform = "linux/amd64",
        dockerfile = "docker/Dockerfile.linux",
        linkTaskName = "linkDebugExecutableLinuxX64",
        binaryPath = "build/bin/linuxX64/debugExecutable/blockcheckw.kexe",
        enabledInDockerTests = true,
    ),
    DockerTestConfig(
        target = "linuxArm64",
        platform = "linux/arm64",
        dockerfile = "docker/Dockerfile.linux",
        linkTaskName = "linkDebugExecutableLinuxArm64",
        binaryPath = "build/bin/linuxArm64/debugExecutable/blockcheckw.kexe",
        enabledInDockerTests = true,
    ),
    // TODO: androidNative — Bionic libc, пока только компиляция
    DockerTestConfig(
        target = "androidNativeArm64",
        platform = "linux/arm64",
        dockerfile = "docker/Dockerfile.android-bionic",
        linkTaskName = "linkDebugExecutableAndroidNativeArm64",
        binaryPath = "build/bin/androidNativeArm64/debugExecutable/blockcheckw.kexe",
        enabledInDockerTests = false, // FIXME: исключён из прогона тестов
    ),
    DockerTestConfig(
        target = "androidNativeX64",
        platform = "linux/amd64",
        dockerfile = "docker/Dockerfile.android-bionic",
        linkTaskName = "linkDebugExecutableAndroidNativeX64",
        binaryPath = "build/bin/androidNativeX64/debugExecutable/blockcheckw.kexe",
        enabledInDockerTests = false, // FIXME: исключён из прогона тестов
    ),
    // TODO: mingwX64 — Wine, долго и flaky
    DockerTestConfig(
        target = "mingwX64",
        platform = "linux/amd64",
        dockerfile = "docker/Dockerfile.mingw-wine",
        linkTaskName = "linkDebugExecutableMingwX64",
        binaryPath = "build/bin/mingwX64/debugExecutable/blockcheckw.exe",
        enabledInDockerTests = false, // FIXME: исключён из прогона тестов
    ),
)

val dockerImageName = "blockcheckw-test"

val registerQemuBinfmt = tasks.register<Exec>("registerQemuBinfmt") {
    group = "verification"
    description = "Registers QEMU binfmt handlers for cross-platform Docker tests"

    onlyIf {
        !File("/proc/sys/fs/binfmt_misc/qemu-aarch64").exists()
    }

    commandLine("docker", "run", "--rm", "--privileged", "multiarch/qemu-user-static", "--reset", "-p", "yes")
}

dockerTestConfigs.forEach { config ->
    tasks.register<Exec>(name = config.dockerTaskName) {
        group = "verification"
        description = "Build and run blockcheckw in Docker for ${config.target}"
        dependsOn(config.linkTaskName)

        if (config.platform.contains("arm64")) {
            dependsOn(registerQemuBinfmt)
        }

        val binaryFile = layout.projectDirectory.file(config.binaryPath)
        val imageName = "$dockerImageName:${config.target}"

        doFirst {
            check(binaryFile.asFile.exists()) {
                "Binary not found: ${binaryFile.asFile.absolutePath}"
            }
        }

        workingDir = layout.projectDirectory.asFile
        commandLine(
            "bash", "-c", """
                set -euo pipefail
                docker build \
                    --platform ${config.platform} \
                    --build-arg BINARY_PATH=${config.binaryPath} \
                    -f ${config.dockerfile} \
                    -t $imageName \
                    . \
                && output=${'$'}(docker run --rm --platform ${config.platform} $imageName) \
                && echo "${'$'}output" \
                && echo "${'$'}output" | grep -q "blockcheckw: ${config.target} - OK" \
                && echo "Docker-test PASSED: ${config.target}"
            """.trimIndent()
        )
    }
}

tasks.register("dockerTestAllEnabled") {
    group = "verification"
    description = "Run Docker integration tests for all enabled platforms"
    dependsOn(dockerTestConfigs.filter { it.enabledInDockerTests }.map { it.dockerTaskName })
}
