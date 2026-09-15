import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.metro)
}

val olcrtcRepoPath = providers.gradleProperty("OLCRTC_REPO")
    .orElse(providers.environmentVariable("OLCRTC_REPO"))
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = rootProject.file(olcrtcRepoPath.get())
val detectedOlcrtcSha = providers.exec {
    commandLine("git", "-C", olcrtcRepoDir.absolutePath, "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { output ->
    output.trim().ifBlank { "unknown" }
}
val olcrtcCommitSha = providers.gradleProperty("olcbox.olcrtcSha")
    .orElse(providers.environmentVariable("OLCBOX_OLCRTC_SHA"))
    .orElse(detectedOlcrtcSha)
val olcrtcAndroidAar = layout.buildDirectory.file("generated/olcrtc/olcrtc.aar")
val olcrtcAndroidAarFile = olcrtcAndroidAar.get().asFile
val olcrtcAndroidBindDir = rootProject.file("tools/olcrtc-android-bind")
val olcrtcAndroidModule = "github.com/openlibrecommunity/olcrtc"
val olcrtcAndroidModuleVersion = "v0.0.2-0.20260818184357-f616f57bb3a9"
val olcrtcAndroidModuleCommit = "f616f57bb3a90740f1755922ffeaa7acc5cfe4ed"
val olcrtcAndroidModuleSum = "h1:bv0Tec2WwlWvhoaP9T94SnSFKBw1VQURo5bofHzMj08="
val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { input -> load(input) }
}
val androidSdkPath = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.provider { localProperties.getProperty("sdk.dir").orEmpty() })
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("0.0.10")
val olcboxBuild = providers.gradleProperty("olcbox.build").orElse("1")
val awgCoreCommitSha = providers.gradleProperty("olcbox.awgCoreSha").orElse("unknown")
val xrayCoreVersion = providers.gradleProperty("olcbox.xrayVersion").orElse("unknown")
val xrayCoreCommitSha = providers.gradleProperty("olcbox.xraySha").orElse("unknown")
val openFluxCoreCommitSha = providers.gradleProperty("olcbox.openFluxSha").orElse("unknown")
val olcboxVersionValue = olcboxVersion.get()
val generatedAppInfoDir = layout.buildDirectory.dir("generated/source/olcboxAppInfo/commonMain")

abstract class GenerateAppInfoTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val build: Property<Long>

    @get:Input
    abstract val olcrtcSha: Property<String>

    @get:Input
    abstract val awgCoreSha: Property<String>

    @get:Input
    abstract val xrayVersion: Property<String>

    @get:Input
    abstract val xraySha: Property<String>

    @get:Input
    abstract val openFluxSha: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("org/olcbox/app")
        packageDir.mkdirs()
        val escapedVersion = version.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedOlcrtcSha = olcrtcSha.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedAwgCoreSha = awgCoreSha.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedXrayVersion = xrayVersion.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedXraySha = xraySha.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedOpenFluxSha = openFluxSha.get().replace("\\", "\\\\").replace("\"", "\\\"")
        packageDir.resolve("GeneratedAppInfo.kt").writeText(
            """
            package org.olcbox.app

            internal object GeneratedAppInfo {
                const val NAME: String = "unified-vpn"
                const val VERSION: String = "$escapedVersion"
                const val BUILD: Long = ${build.get()}L
                const val SOURCE_ATTRIBUTION: String = "Based on Olcbox from GitHub"
                const val OLCRTC_SHA: String = "$escapedOlcrtcSha"
                const val AWG_CORE_SHA: String = "$escapedAwgCoreSha"
                const val XRAY_VERSION: String = "$escapedXrayVersion"
                const val XRAY_SHA: String = "$escapedXraySha"
                const val OPENFLUX_SHA: String = "$escapedOpenFluxSha"
            }
            """.trimIndent() + "\n"
        )
    }
}

olcrtcAndroidAarFile.parentFile.mkdirs()

val buildOlcrtcAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pinned versioned olcrtc Android module using gomobile."
    dependsOn(":verifyOlcRtcSource")

    inputs.files(
        olcrtcAndroidBindDir.resolve("go.mod"),
        olcrtcAndroidBindDir.resolve("go.sum"),
        olcrtcAndroidBindDir.resolve("pin.go")
    )
    inputs.property("olcrtcCommit", olcrtcCommitSha)
    inputs.property("olcrtcModuleVersion", olcrtcAndroidModuleVersion)
    inputs.property("olcrtcModuleSum", olcrtcAndroidModuleSum)
    outputs.file(olcrtcAndroidAar)

    workingDir = olcrtcAndroidBindDir
    environment("ANDROID_HOME", androidSdkPath.get())
    environment("ANDROID_SDK_ROOT", androidSdkPath.get())
    environment("GOWORK", "off")
    environment("GOFLAGS", "")

    doFirst {
        val sdkDir = rootProject.file(androidSdkPath.get())
        require(androidSdkPath.get().isNotBlank() && sdkDir.isDirectory) {
            "Android SDK was not found. Set sdk.dir in local.properties or ANDROID_HOME."
        }
        check(olcrtcCommitSha.get().trim().lowercase() == olcrtcAndroidModuleCommit) {
            "The Android module pin must be updated with olcbox.olcrtcSha."
        }

        fun go(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("go") + arguments)
                .directory(olcrtcAndroidBindDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["GOWORK"] = "off"
                    environment()["GOFLAGS"] = ""
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.waitFor() == 0) { "Cannot verify the olcRTC Android module: $output" }
            return output
        }

        val manifest = JsonSlurper().parseText(go("mod", "edit", "-json")) as Map<*, *>
        check((manifest["Replace"] as? List<*>).isNullOrEmpty()) {
            "The Android bind module must not contain local or version replacements."
        }
        val module = JsonSlurper().parseText(
            go("list", "-mod=readonly", "-m", "-json", olcrtcAndroidModule)
        ) as Map<*, *>
        check(module["Version"] == olcrtcAndroidModuleVersion && module["Replace"] == null) {
            "The Android bind module does not select the pinned olcRTC version."
        }
        check(module["Sum"] == olcrtcAndroidModuleSum) {
            "The Android olcRTC module checksum does not match the verified pin."
        }
        go("mod", "verify")
        logger.lifecycle("Verified Android olcRTC module $olcrtcAndroidModuleVersion")
    }
    commandLine(
        "gomobile",
        "bind",
        "-target=android/arm,android/arm64,android/amd64",
        "-androidapi",
        "21",
        "-trimpath",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcAndroidAarFile.absolutePath,
        "$olcrtcAndroidModule/mobile"
    )
}

val olcrtcAndroidAarDependency = files(olcrtcAndroidAarFile).builtBy(buildOlcrtcAndroidAar)

val generateAppInfo by tasks.registering(GenerateAppInfoTask::class) {
    version.set(olcboxVersionValue)
    build.set(olcboxBuild.map { it.toLong() })
    olcrtcSha.set(olcrtcCommitSha)
    awgCoreSha.set(awgCoreCommitSha)
    xrayVersion.set(xrayCoreVersion)
    xraySha.set(xrayCoreCommitSha)
    openFluxSha.set(openFluxCoreCommitSha)
    outputDir.set(generatedAppInfoDir)
}

kotlin {
    android {
        namespace = "org.olcbox.app.sharedui"
        compileSdk = 37
        minSdk = 23

        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)

            implementation(compose.materialIconsExtended)
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatformSettings)
            implementation(libs.kstore)
            implementation(libs.materialKolor)
            implementation(libs.androidx.datastore.preferences)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                implementation(libs.androidx.activityCompose)
                implementation(libs.androidx.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kstore.file)
                implementation(libs.zxing.core)
                implementation(files("libs/jsch-2.28.6.jar"))
                implementation(files("libs/bcprov-jdk18on-1.79.jar"))
                implementation(olcrtcAndroidAarDependency)
            }
        }

        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kstore.file)
                implementation(libs.jna)
                implementation(files("libs/jsch-2.28.6.jar"))
                implementation(files("libs/bcprov-jdk18on-1.79.jar"))
            }
        }

    }
}

tasks.withType<Test>().configureEach {
    val isolatedRoot = temporaryDir.resolve("isolated-user-data")
    val isolatedHome = isolatedRoot.resolve("Home")
    val isolatedRoaming = isolatedRoot.resolve("Roaming")
    val isolatedLocal = isolatedRoot.resolve("Local")
    environment("APPDATA", isolatedRoaming.absolutePath)
    environment("LOCALAPPDATA", isolatedLocal.absolutePath)
    environment("UNIFIEDVPN_TEST_APPDATA_ROOT", isolatedRoot.absolutePath)
    systemProperty("user.home", isolatedHome.absolutePath)
    doFirst {
        val buildRoot = layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val target = isolatedRoot.toPath().toAbsolutePath().normalize()
        require(target.startsWith(buildRoot)) { "Refusing to clean test data outside the build directory" }
        project.delete(isolatedRoot)
        isolatedHome.mkdirs()
        isolatedRoaming.mkdirs()
        isolatedLocal.mkdirs()
    }
}
