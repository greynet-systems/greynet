import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.zip.GZIPInputStream
import java.io.BufferedInputStream

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    iosArm64()
    iosSimulatorArm64()
    
    jvm()
    
    js {
        browser()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.register("downloadByedpi") {
    description = "Download pre-built ByeDPI binaries from GitHub Releases"
    group = "byedpi"

    val version = "17.3"
    val baseUrl = "https://github.com/hufrea/byedpi/releases/download/v0.$version"
    val binaryName = "ciadpi"
    val cacheDir = layout.buildDirectory.dir("byedpi-cache")
    val projectDir = layout.projectDirectory

    // artifact archive name -> destination path for the extracted binary
    val downloads = listOf(
        "byedpi-$version-x86_64.tar.gz" to "src/jvmMain/resources/byedpi/x86_64/byedpi-x86_64",
        "byedpi-$version-aarch64.tar.gz" to "src/androidMain/assets/byedpi/arm64-v8a/byedpi-aarch64",
        "byedpi-$version-armv7l.tar.gz" to "src/androidMain/assets/byedpi/armeabi-v7a/byedpi-armv7l",
        "byedpi-$version-x86_64.tar.gz" to "src/androidMain/assets/byedpi/x86_64/byedpi-x86_64",
    )

    doLast {
        val cacheDirFile = cacheDir.get().asFile
        cacheDirFile.mkdirs()

        downloads.forEach { (archive, destPath) ->
            val destFile = projectDir.file(destPath).asFile
            if (destFile.exists()) {
                logger.lifecycle("Already exists: $destPath")
                return@forEach
            }
            destFile.parentFile.mkdirs()

            // Download archive to cache (reuse if already downloaded)
            val cachedArchive = File(cacheDirFile, archive)
            if (!cachedArchive.exists()) {
                val url = URI("$baseUrl/$archive").toURL()
                logger.lifecycle("Downloading $url")
                url.openStream().use { input ->
                    cachedArchive.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Extract the byedpi binary from tar.gz
            GZIPInputStream(cachedArchive.inputStream()).use { gzip ->
                BufferedInputStream(gzip).use { buffered ->
                    // Simple tar extraction: read 512-byte headers
                    val header = ByteArray(512)
                    while (true) {
                        val bytesRead = buffered.readNBytes(header, 0, 512)
                        if (bytesRead < 512 || header.all { it == 0.toByte() }) break

                        val name = String(header, 0, 100).trim('\u0000', ' ')
                        val sizeStr = String(header, 124, 12).trim('\u0000', ' ')
                        val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
                        val paddedSize = if (size % 512 == 0L) size else (size / 512 + 1) * 512

                        if (name.startsWith(binaryName) && size > 0) {
                            val data = buffered.readNBytes(size.toInt())
                            destFile.writeBytes(data)
                            destFile.setExecutable(true)
                            logger.lifecycle("Extracted $name -> $destPath ($size bytes)")
                            return@forEach
                        } else if (paddedSize > 0) {
                            buffered.readNBytes(paddedSize.toInt())
                        }
                    }
                    throw GradleException("Binary '$binaryName' not found in archive $archive")
                }
            }
        }
    }
}

android {
    namespace = "systems.greynet.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
