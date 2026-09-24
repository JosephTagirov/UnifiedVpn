import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.multiplatform).apply(false)
    alias(libs.plugins.kotlin.android).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.kotlinx.serialization).apply(false)
    alias(libs.plugins.metro).apply(false)
}

abstract class VerifyOlcRtcSourceTask : DefaultTask() {
    @get:Input
    abstract val repositoryPath: Property<String>

    @get:Input
    abstract val expectedCommit: Property<String>

    @TaskAction
    fun verify() {
        val repository = project.file(repositoryPath.get()).absoluteFile.normalize()
        require(repository.resolve("go.mod").isFile && repository.resolve("mobile").isDirectory) {
            "OLCRTC_REPO is not an olcRTC source tree: ${repository.absolutePath}"
        }
        require(repository.resolve(".git").isDirectory) {
            "OLCRTC_REPO must be a standalone Git clone with its own .git directory: " +
                "${repository.absolutePath}. Nested Git worktrees can make Go stamp the parent " +
                "repository revision into olcRTC binaries."
        }

        fun git(vararg arguments: String): String {
            val safeRepository = repository.absolutePath.replace('\\', '/')
            val process = ProcessBuilder(
                listOf(
                    "git",
                    "-c",
                    "safe.directory=$safeRepository",
                    "-C",
                    repository.absolutePath
                ) + arguments
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.waitFor() == 0) {
                "Cannot verify olcRTC source at ${repository.absolutePath}: $output"
            }
            return output
        }

        val expected = expectedCommit.get().trim().lowercase()
        require(expected.matches(Regex("[0-9a-f]{40}"))) {
            "olcbox.olcrtcSha must be a full 40-character Git commit, got: $expected"
        }
        val actual = git("rev-parse", "HEAD").lowercase()
        check(actual == expected) {
            "olcRTC source mismatch: expected $expected, found $actual at ${repository.absolutePath}"
        }
        check(git("status", "--porcelain", "--untracked-files=no").isBlank()) {
            "olcRTC source has tracked changes: ${repository.absolutePath}"
        }
        logger.lifecycle("Verified olcRTC source commit $actual")
    }
}

val olcrtcRepositoryPath = providers.gradleProperty("OLCRTC_REPO")
    .orElse(providers.environmentVariable("OLCRTC_REPO"))
    .orElse(layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)

tasks.register<VerifyOlcRtcSourceTask>("verifyOlcRtcSource") {
    group = "verification"
    description = "Verifies that OLCRTC_REPO is the exact clean commit pinned by Unified VPN."
    repositoryPath.set(olcrtcRepositoryPath)
    expectedCommit.set(
        providers.gradleProperty("olcbox.olcrtcSha")
            .orElse(providers.environmentVariable("OLCBOX_OLCRTC_SHA"))
    )
}

abstract class VerifyAwgBinaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    @get:Input
    abstract val expectedCommit: Property<String>

    @get:Input
    abstract val expectedWireguardCommit: Property<String>

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val targetArch: Property<String>

    @TaskAction
    fun verify() {
        val expected = expectedCommit.get()
        val wireguardCommit = expectedWireguardCommit.get()
        require(listOf(expected, wireguardCommit).all { it.matches(Regex("[0-9a-f]{40}")) }) {
            "AWG source and WireGuard pins must be full Git commits"
        }
        val process = ProcessBuilder("go", "version", "-m", "-json", binary.get().asFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Cannot inspect AWG build metadata: $output" }
        val info = JsonSlurper().parseText(output) as Map<*, *>
        val main = info["Main"] as? Map<*, *>
        check(info["Path"] == "github.com/sagernet/sing-box/cmd/sing-box" &&
            main?.get("Path") == "github.com/sagernet/sing-box" && main["Replace"] == null) {
            "AWG binary must be built from the pinned sing-box command"
        }
        val settings = (info["Settings"] as? List<*>)?.associate { entry ->
            val setting = entry as Map<*, *>
            setting["Key"] to setting["Value"]
        }.orEmpty()
        check(settings["vcs.revision"] == expected && settings["vcs.modified"] == "false") {
            "AWG binary is stale or built from modified sources; expected $expected"
        }
        check(settings["GOOS"] == targetOs.get() && settings["GOARCH"] == targetArch.get()) {
            "AWG binary target does not match ${targetOs.get()}/${targetArch.get()}"
        }
        check(settings["-trimpath"] == "true") { "AWG binaries must use -trimpath" }
        val tags = (settings["-tags"] as? String).orEmpty().split(',').toSet()
        check(tags.containsAll(listOf("with_wireguard", "with_gvisor", "with_quic", "with_utls"))) {
            "AWG binary is missing required VPN build tags"
        }
        val wireguard = (info["Deps"] as? List<*>)?.map { it as Map<*, *> }
            ?.singleOrNull { it["Path"] == "github.com/sagernet/wireguard-go" }
        val replacement = wireguard?.get("Replace") as? Map<*, *>
        check(replacement?.get("Path") == "github.com/throneproj/wireguard-go" &&
            (replacement["Version"] as? String)?.endsWith("-${wireguardCommit.take(12)}") == true &&
            (replacement["Sum"] as? String)?.startsWith("h1:") == true) {
            "AWG binary does not contain the pinned AmneziaWG implementation"
        }
        if (targetOs.get() == "android") {
            check(settings["CGO_ENABLED"] == "1") {
                "Android AWG must be linked with the NDK"
            }
        }
        logger.lifecycle("Verified AWG ${targetOs.get()}/${targetArch.get()} at $expected")
    }
}

val awgCommit = providers.gradleProperty("olcbox.awgCoreSha")
val awgWireguardCommit = providers.gradleProperty("olcbox.awgWireguardSha")
tasks.register<VerifyAwgBinaryTask>("verifyAwgWindowsBinary") {
    group = "verification"
    binary.set(layout.file(providers.environmentVariable("SING_BOX_AWG_BINARY")
        .map { file(it) }
        .orElse(layout.projectDirectory.file(".downloads/sing-box-awg/bin/sing-box-windows-amd64.exe").asFile)))
    expectedCommit.set(awgCommit)
    expectedWireguardCommit.set(awgWireguardCommit)
    targetOs.set("windows")
    targetArch.set("amd64")
}

val androidAwgTargets = mapOf("armeabi-v7a" to "arm", "arm64-v8a" to "arm64", "x86_64" to "amd64")
val selectedAndroidAbis = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(androidAwgTargets.keys.toList())
val androidAwgChecks = selectedAndroidAbis.map { abi ->
    require(abi in androidAwgTargets) { "Unsupported Android AWG ABI: $abi" }
    tasks.register<VerifyAwgBinaryTask>("verifyAwgAndroid${abi.replace('-', '_')}") {
        group = "verification"
        binary.set(layout.projectDirectory.file("androidApp/src/main/jniLibs/$abi/libsing-box.so"))
        expectedCommit.set(awgCommit)
        expectedWireguardCommit.set(awgWireguardCommit)
        targetOs.set("android")
        targetArch.set(androidAwgTargets.getValue(abi))
    }
}
tasks.register("verifyAwgAndroidBinaries") {
    group = "verification"
    dependsOn(androidAwgChecks)
}
