import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jna)
    implementation(libs.zxing.core)
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        onlyIf("download archive is not cached") {
            val cached = outputFile.orNull?.asFile
            cached == null || !cached.isFile || cached.length() == 0L
        }
    }

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        URI(sourceUrl.get())
            .toURL()
            .openStream()
            .use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
    }
}

abstract class ExtractZipEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = zipFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence()
                .firstOrNull { it.name.endsWith(entrySuffix.get()) }
                ?: error("${entrySuffix.get()} entry was not found in ${zip.absolutePath}")

            archive.getInputStream(entry).use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}

abstract class VerifyNativeResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val requiredPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = resourcesDir.get().asFile
        val missing = requiredPaths.get()
            .map { root.resolve(it) }
            .filterNot { it.isFile }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }
    }
}

abstract class VerifyDesktopPackageVersionTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val config = configFile.get().asFile
        val expectedOption = "java-options=-Djpackage.app-version=${expectedVersion.get()}"
        val hasExpectedVersion = config.useLines { lines ->
            lines.any { line -> line.trim() == expectedOption }
        }

        check(hasExpectedVersion) {
            "${config.absolutePath} does not contain the expected package version ${expectedVersion.get()}"
        }
        logger.lifecycle("Verified Windows package version ${expectedVersion.get()} in ${config.name}")
    }
}

abstract class VerifyDesktopAppImageTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appImageDir: DirectoryProperty

    @get:Input
    abstract val launcherName: Property<String>

    @get:Input
    abstract val timeoutSeconds: Property<Long>

    @TaskAction
    fun verify() {
        val appImage = appImageDir.get().asFile
        val launcher = appImage.resolve(launcherName.get())
        val requiredFiles = listOf(
            launcher,
            appImage.resolve("app/${launcherName.get().substringBeforeLast('.')}.cfg"),
            appImage.resolve("runtime/bin/java.dll"),
            appImage.resolve("runtime/bin/server/jvm.dll"),
        )
        val missing = requiredFiles.filterNot { it.isFile && it.length() > 0L }
        check(missing.isEmpty()) {
            "Incomplete Windows app image:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.absolutePath}" }
        }
        check(appImage.resolve("app").listFiles().orEmpty().any { it.isFile && it.extension == "jar" }) {
            "Windows app image does not contain application JARs: ${appImage.absolutePath}"
        }

        val smokeRoot = temporaryDir.resolve("isolated-user-data")
        val outputFile = temporaryDir.resolve("launcher-smoke.log")
        val process = ProcessBuilder(launcher.absolutePath, "--verify-native-assets")
            .directory(appImage)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .also { builder ->
                builder.environment()["APPDATA"] = smokeRoot.resolve("Roaming").absolutePath
                builder.environment()["LOCALAPPDATA"] = smokeRoot.resolve("Local").absolutePath
                builder.environment()["JPACKAGE_DEBUG"] = "true"
            }
            .start()

        if (!process.waitFor(timeoutSeconds.get(), TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("Windows launcher smoke test timed out after ${timeoutSeconds.get()} seconds")
        }
        val output = outputFile.takeIf { it.isFile }?.readText().orEmpty().trim()
        check(process.exitValue() == 0) {
            buildString {
                append("Windows launcher failed its JVM/native-assets smoke test with code ")
                append(process.exitValue())
                if (output.isNotBlank()) append(":\n").append(output)
            }
        }
        logger.lifecycle("Verified Windows JVM launch and bundled native assets in ${appImage.name}")
    }
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc")
    .absolutePath
val olcrtcRepo = providers.gradleProperty("OLCRTC_REPO")
    .orElse(providers.environmentVariable("OLCRTC_REPO"))
    .orElse(defaultOlcRtcRepo)
val olcrtcRepoDir = olcrtcRepo.map { rootProject.file(it) }
val expectedOlcrtcCommit = providers.gradleProperty("olcbox.olcrtcSha")
    .orElse(providers.environmentVariable("OLCBOX_OLCRTC_SHA"))
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
val desktopPackageName = "UnifiedVPN"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("0.0.10").get()
val tun2SocksVersion = "2.6.0"
val wintunVersion = "0.14.1"
val xrayVersion = providers.gradleProperty("olcbox.xrayVersion").orElse("26.3.27").get()
val currentBuildTargetFormats = when {
    currentBuildOs.isMacOsX -> arrayOf(TargetFormat.Dmg)
    currentBuildOs.isWindows -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
    currentBuildOs.isLinux -> arrayOf(TargetFormat.AppImage)
    else -> emptyArray()
}

fun desktopArchName(arch: String): String = when (arch.lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported desktop architecture: $arch")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

fun verifyOlcRtcBinaryVcsMetadata(binary: File) {
    val process = ProcessBuilder("go", "version", "-m", binary.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) {
        "Cannot read Go VCS metadata from ${binary.absolutePath}: ${output.trim()}"
    }
    val revision = Regex("""\bvcs\.revision=([0-9a-f]{40})\b""")
        .find(output)
        ?.groupValues
        ?.get(1)
    val modified = Regex("""\bvcs\.modified=(true|false)\b""")
        .find(output)
        ?.groupValues
        ?.get(1)
    val expected = expectedOlcrtcCommit.get().trim().lowercase()
    check(revision == expected && modified == "false") {
        "olcRTC binary VCS metadata mismatch for ${binary.absolutePath}: " +
            "expected revision=$expected modified=false, found revision=$revision modified=$modified"
    }
    logger.lifecycle("Verified olcRTC binary VCS metadata $expected in ${binary.name}")
}

fun registerOlcRtcBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    inputs.dir(olcrtcRepoDir.map { it.resolve("cmd/olcrtc") })
    inputs.dir(olcrtcRepoDir.map { it.resolve("internal") })
    inputs.dir(olcrtcRepoDir.map { it.resolve("pkg") })
    inputs.files(olcrtcRepoDir.map { it.resolve("go.mod") }, olcrtcRepoDir.map { it.resolve("go.sum") })
    inputs.property("olcrtcRepositoryPath", olcrtcRepoDir.map { it.canonicalPath })
    inputs.property("olcrtcCommit", expectedOlcrtcCommit)
    outputs.file(outputFile)
    dependsOn(":verifyOlcRtcSource")
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "0")
    environment("GIT_CONFIG_COUNT", "1")
    environment("GIT_CONFIG_KEY_0", "safe.directory")
    environment("GIT_CONFIG_VALUE_0", olcrtcRepoDir.get().canonicalPath.replace('\\', '/'))
    commandLine(
        "go",
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
    doLast {
        verifyOlcRtcBinaryVcsMetadata(outputFile.get().asFile)
    }
}

fun registerOlcRtcLibraryBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    inputs.dir(olcrtcRepoDir.map { it.resolve("cmd/olcrtc-cgo") })
    inputs.dir(olcrtcRepoDir.map { it.resolve("internal") })
    inputs.dir(olcrtcRepoDir.map { it.resolve("pkg") })
    inputs.files(olcrtcRepoDir.map { it.resolve("go.mod") }, olcrtcRepoDir.map { it.resolve("go.sum") })
    inputs.property("olcrtcRepositoryPath", olcrtcRepoDir.map { it.canonicalPath })
    inputs.property("olcrtcCommit", expectedOlcrtcCommit)
    outputs.file(outputFile)
    dependsOn(":verifyOlcRtcSource")
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    environment("GIT_CONFIG_COUNT", "1")
    environment("GIT_CONFIG_KEY_0", "safe.directory")
    environment("GIT_CONFIG_VALUE_0", olcrtcRepoDir.get().canonicalPath.replace('\\', '/'))
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc-cgo"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
    doLast {
        verifyOlcRtcBinaryVcsMetadata(outputFile.get().asFile)
    }
}

val buildOlcRtcDarwinArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "olcrtc-darwin-arm64"
)

val buildOlcRtcDarwinAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "olcrtc-darwin-amd64"
)

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.exe"
)

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "olcrtc-linux-arm64"
)

val buildOlcRtcLibDarwinArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "libolcrtc-darwin-arm64.dylib"
)

val buildOlcRtcLibDarwinAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "libolcrtc-darwin-amd64.dylib"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "libolcrtc-linux-arm64.so"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.dll"
)

val olcRtcRuntimeDataDir = olcrtcRepoDir.map { repository ->
    listOf(
        repository.resolve("data"),
        repository.resolve("internal/names/data"),
    ).firstOrNull { candidate ->
        candidate.resolve("names").isFile && candidate.resolve("surnames").isFile
    } ?: repository.resolve("internal/names/data")
}
val copyOlcRtcDataAssets = tasks.register<Sync>("copyOlcRtcDataAssets") {
    dependsOn(":verifyOlcRtcSource")
    from(olcRtcRuntimeDataDir) {
        include("names", "surnames")
    }
    into(generatedNativeResources.map { it.dir("olcrtc-data") })

    doFirst {
        val sourceDir = olcRtcRuntimeDataDir.get()
        val missing = listOf("names", "surnames")
            .map(sourceDir::resolve)
            .filterNot { it.isFile && it.length() > 0L }
        require(missing.isEmpty()) {
            "olcRTC runtime data is missing:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.absolutePath}" }
        }
    }
}

val desktopNativeAssetTasks = mutableListOf<Any>(
    buildOlcRtcDarwinArm64,
    buildOlcRtcDarwinAmd64,
    buildOlcRtcWindowsAmd64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    buildOlcRtcLibDarwinArm64,
    buildOlcRtcLibDarwinAmd64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64,
    copyOlcRtcDataAssets
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>(copyOlcRtcDataAssets)

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinArm64)
        }
    }
    currentBuildOs.isWindows -> {
        hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
        // hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)
    }
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
    }
}

if (currentBuildOs.isLinux) {
    val buildHevSocks5TunnelLinux = tasks.register<Exec>("buildHevSocks5TunnelLinux") {
        val outputFile = generatedNativeResources.map {
            it.file("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
        val output = outputFile.get().asFile

        outputs.file(outputFile)
        workingDir = hevSocks5TunnelSourceDir.asFile
        commandLine(
            "sh",
            "-c",
            "mkdir -p ${shellQuote(output.parentFile.absolutePath)} && make clean exec && install -m 0755 bin/hev-socks5-tunnel ${shellQuote(output.absolutePath)}"
        )
    }
    desktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
    hostDesktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
}

if (currentBuildOs.isWindows) {
    val tun2SocksWindowsOutput = generatedNativeResources.map {
        it.file("native/tun2socks-windows-amd64.exe")
    }
    val wintunWindowsOutput = generatedNativeResources.map {
        it.file("native/wintun.dll")
    }
    val singBoxAwgWindowsSource = providers.environmentVariable("SING_BOX_AWG_BINARY")
        .map { rootProject.file(it) }
        .orElse(
            rootProject.layout.projectDirectory.file(
                ".downloads/sing-box-awg/bin/sing-box-windows-amd64.exe"
            ).asFile
        )
    val singBoxAwgWindowsOutput = generatedNativeResources.map {
        it.file("native/sing-box-awg-windows-amd64.exe")
    }
    val xrayWindowsSource = providers.environmentVariable("XRAY_BINARY")
        .map { rootProject.file(it) }
        .orElse(
            rootProject.layout.projectDirectory.file(
                ".downloads/xray/v$xrayVersion/windows-64/xray.exe"
            ).asFile
        )
    val xrayWindowsOutput = generatedNativeResources.map {
        it.file("native/xray-windows-amd64.exe")
    }

    val copySingBoxAwgWindowsAmd64 = tasks.register<Copy>("copySingBoxAwgWindowsAmd64") {
        from(singBoxAwgWindowsSource)
        into(singBoxAwgWindowsOutput.map { it.asFile.parentFile })
        rename { "sing-box-awg-windows-amd64.exe" }
        inputs.file(singBoxAwgWindowsSource)
        outputs.file(singBoxAwgWindowsOutput)

        doFirst {
            require(singBoxAwgWindowsSource.get().isFile) {
                "AmneziaWG sing-box binary is missing: ${singBoxAwgWindowsSource.get().absolutePath}. " +
                    "Set SING_BOX_AWG_BINARY to the compatible Throne sing-box fork binary."
            }
        }
    }

    val copyXrayWindowsAmd64 = tasks.register<Copy>("copyXrayWindowsAmd64") {
        from(xrayWindowsSource)
        into(xrayWindowsOutput.map { it.asFile.parentFile })
        rename { "xray-windows-amd64.exe" }
        inputs.file(xrayWindowsSource)
        outputs.file(xrayWindowsOutput)

        doFirst {
            require(xrayWindowsSource.get().isFile) {
                "Xray binary is missing: ${xrayWindowsSource.get().absolutePath}. " +
                    "Set XRAY_BINARY to the official Windows amd64 Xray executable."
            }
        }
    }

    val downloadTun2SocksWindowsAmd64 = tasks.register<DownloadFileTask>("downloadTun2SocksWindowsAmd64") {
        sourceUrl.set("https://github.com/xjasonlyu/tun2socks/releases/download/v$tun2SocksVersion/tun2socks-windows-amd64.zip")
        outputFile.set(layout.buildDirectory.file("tmp/tun2socks/tun2socks-windows-amd64-$tun2SocksVersion.zip"))
    }

    val extractTun2SocksWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractTun2SocksWindowsAmd64") {
        zipFile.set(downloadTun2SocksWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("tun2socks-windows-amd64.exe")
        outputFile.set(tun2SocksWindowsOutput)
    }

    val downloadWintunWindowsAmd64 = tasks.register<DownloadFileTask>("downloadWintunWindowsAmd64") {
        sourceUrl.set("https://www.wintun.net/builds/wintun-$wintunVersion.zip")
        outputFile.set(layout.buildDirectory.file("tmp/wintun/wintun-$wintunVersion.zip"))
    }

    val extractWintunWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractWintunWindowsAmd64") {
        zipFile.set(downloadWintunWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("/bin/amd64/wintun.dll")
        outputFile.set(wintunWindowsOutput)
    }

    desktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    desktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    desktopNativeAssetTasks.add(copySingBoxAwgWindowsAmd64)
    desktopNativeAssetTasks.add(copyXrayWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    hostDesktopNativeAssetTasks.add(copySingBoxAwgWindowsAmd64)
    hostDesktopNativeAssetTasks.add(copyXrayWindowsAmd64)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    add("olcrtc-data/names")
    add("olcrtc-data/surnames")
    when {
        currentBuildOs.isMacOsX -> {
            add("native/olcrtc-darwin-$hostDesktopArch")
            add("native/libolcrtc-darwin-$hostDesktopArch.dylib")
        }
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-amd64.exe")
            // add("native/olcrtc-windows-amd64.dll")
            add("native/tun2socks-windows-amd64.exe")
            add("native/wintun.dll")
            add("native/sing-box-awg-windows-amd64.exe")
            add("native/xray-windows-amd64.exe")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
    }
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    resourcesDir.set(generatedNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
        resources.srcDir(layout.projectDirectory.dir("appIcons"))
    }
}

if (currentBuildOs.isWindows) {
    val jpackageAppRootDir = layout.buildDirectory.dir("compose/binaries/main-release/app")
    val desktopAppImageDir = jpackageAppRootDir.map { it.dir(desktopPackageName) }
    val desktopPackageConfigFile = jpackageAppRootDir.map { root ->
        root.file("$desktopPackageName/app/$desktopPackageName.cfg")
    }

    listOf("createRuntimeImage", "createReleaseDistributable").forEach { taskName ->
        tasks.matching { task -> task.name == taskName }.configureEach {
            inputs.property("unifiedVpnPackageVersion", desktopPackageVersion)
        }
    }

    val verifyDesktopPackageVersion = tasks.register<VerifyDesktopPackageVersionTask>(
        "verifyDesktopPackageVersion"
    ) {
        group = "verification"
        description = "Verifies the version embedded in the Windows jpackage app image."
        dependsOn("createReleaseDistributable")
        configFile.set(desktopPackageConfigFile)
        expectedVersion.set(desktopPackageVersion)
    }

    val verifyDesktopAppImage = tasks.register<VerifyDesktopAppImageTask>(
        "verifyDesktopAppImage"
    ) {
        group = "verification"
        description = "Launches the packaged Windows app with isolated data and verifies its JVM and native assets."
        dependsOn(verifyDesktopPackageVersion)
        appImageDir.set(desktopAppImageDir)
        launcherName.set("$desktopPackageName.exe")
        timeoutSeconds.set(30L)
    }

    tasks.withType<AbstractJPackageTask>()
        .matching { task -> task.name == "packageReleaseExe" || task.name == "packageReleaseMsi" }
        .configureEach {
            appImage.set(desktopAppImageDir)
            freeArgs.add("--verbose")
        }

    listOf(
        "packageReleaseDistributionForCurrentOS",
        "packageReleaseExe",
        "packageReleaseMsi",
        "packageReleasePortableZip",
    ).forEach { taskName ->
        tasks.matching { task -> task.name == taskName }.configureEach {
            dependsOn(verifyDesktopAppImage)
            inputs.property("unifiedVpnPackageVersion", desktopPackageVersion)
        }
    }

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set("$desktopPackageName-$desktopPackageVersion-windows-amd64-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn(verifyDesktopNativeResources)
}

listOf(
    "run",
    "createReleaseDistributable",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseExe",
    "packageReleaseMsi",
    "packageReleaseDmg",
    "packageReleaseAppImage",
    "packageReleasePortableZip"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyDesktopNativeResources)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(*currentBuildTargetFormats)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                menuGroup = "Unified VPN"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f0aaf78-dbed-4745-9d95-9e63f10a30de"
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"
            }
        }
    }
}

if (currentBuildOs.isLinux) {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/$desktopPackageName-$desktopPackageVersion-$hostDesktopArch.AppImage"
    )

    val prepareReleaseLinuxAppDir = tasks.register<Exec>("prepareReleaseLinuxAppDir") {
        group = "distribution"
        description = "Prepares the AppDir layout used by appimagetool."

        dependsOn("packageReleaseAppImage")
        inputs.dir(jpackageAppDir)
        inputs.file(linuxIconFile)
        outputs.dir(appDir)

        commandLine(
            "sh",
            "-c",
            """
            set -eu

            source_dir="${'$'}1"
            target_dir="${'$'}2"
            icon_file="${'$'}3"

            rm -rf "${'$'}target_dir"
            mkdir -p "${'$'}target_dir"
            cp -R "${'$'}source_dir/." "${'$'}target_dir/"

            cat > "${'$'}target_dir/AppRun" <<'APPRUN'
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/$desktopPackageName" "${'$'}@"
            APPRUN
            chmod +x "${'$'}target_dir/AppRun"

            cat > "${'$'}target_dir/org.olcbox.app.desktopApp.desktop" <<'DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=$desktopPackageName
            Exec=$desktopPackageName
            Icon=olcbox
            Categories=Network;Utility;
            Terminal=false
            DESKTOP

            cp "${'$'}icon_file" "${'$'}target_dir/olcbox.png"
            """.trimIndent(),
            "prepareReleaseLinuxAppDir",
            jpackageAppDir.get().asFile.absolutePath,
            appDir.get().asFile.absolutePath,
            linuxIconFile.asFile.absolutePath
        )
    }

    val packageReleaseLinuxAppImage = tasks.register<Exec>("packageReleaseLinuxAppImage") {
        group = "distribution"
        description = "Packages the Linux desktop app as a real .AppImage file."

        dependsOn(prepareReleaseLinuxAppDir)
        inputs.dir(appDir)
        outputs.file(appImageFile)

        commandLine(
            appImageTool.get(),
            appDir.get().asFile.absolutePath,
            appImageFile.get().asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(packageReleaseLinuxAppImage)
    }
}
