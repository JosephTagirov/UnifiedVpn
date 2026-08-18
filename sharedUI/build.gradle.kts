import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
val olcrtcIosXcframework = layout.buildDirectory.dir("generated/olcrtc/ios/OlcRtcMobile.xcframework")
val olcrtcIosXcframeworkDir = olcrtcIosXcframework.get().asFile
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("0.0.4")
val olcboxVersionValue = olcboxVersion.get()
val generatedAppInfoDir = layout.buildDirectory.dir("generated/source/olcboxAppInfo/commonMain")

abstract class GenerateAppInfoTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val olcrtcSha: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("org/olcbox/app")
        packageDir.mkdirs()
        val escapedVersion = version.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedOlcrtcSha = olcrtcSha.get().replace("\\", "\\\\").replace("\"", "\\\"")
        packageDir.resolve("GeneratedAppInfo.kt").writeText(
            """
            package org.olcbox.app

            internal object GeneratedAppInfo {
                const val NAME: String = "unified-vpn"
                const val VERSION: String = "$escapedVersion"
                const val SOURCE_ATTRIBUTION: String = "Based on Olcbox from GitHub"
                const val OLCRTC_SHA: String = "$escapedOlcrtcSha"
            }
            """.trimIndent() + "\n"
        )
    }
}

olcrtcAndroidAarFile.parentFile.mkdirs()

val buildOlcrtcAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc Android AAR from OLCRTC_REPO using gomobile."

    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.file(olcrtcAndroidAar)

    workingDir = olcrtcRepoDir
    commandLine(
        "gomobile",
        "bind",
        "-target=android/arm,android/arm64,android/amd64",
        "-androidapi",
        "21",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcAndroidAarFile.absolutePath,
        "./mobile"
    )
}

val olcrtcAndroidAarDependency = files(olcrtcAndroidAarFile).builtBy(buildOlcrtcAndroidAar)

val buildOlcrtcIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc iOS XCFramework from OLCRTC_REPO using gomobile."

    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.dir(olcrtcIosXcframework)

    workingDir = olcrtcRepoDir

    doFirst {
        delete(olcrtcIosXcframeworkDir)
        olcrtcIosXcframeworkDir.parentFile.mkdirs()
    }

    commandLine(
        "gomobile",
        "bind",
        "-target=ios",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcIosXcframeworkDir.absolutePath,
        "./mobile"
    )
}

val generateAppInfo by tasks.registering(GenerateAppInfoTask::class) {
    version.set(olcboxVersionValue)
    olcrtcSha.set(olcrtcCommitSha)
    outputDir.set(generatedAppInfoDir)
}

kotlin {
    android {
        namespace = "org.olcbox.app.sharedui"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

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

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }

        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }
    }

    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach {
            binaries {
                framework {
                    baseName = "SharedUI"
                    isStatic = true
                }
            }
        }
}
