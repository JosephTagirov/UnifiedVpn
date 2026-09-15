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
import org.gradle.work.InputChanges
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import groovy.json.JsonSlurper
import java.net.URI
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

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
        outputs.upToDateWhen { hasValidCachedArchive() }
        onlyIf("download archive is not cached") {
            !hasValidCachedArchive()
        }
    }

    private fun hasValidCachedArchive(): Boolean {
        val cached = outputFile.orNull?.asFile ?: return false
        return cached.isFile && runCatching { ZipFile(cached).use { it.size() > 0 } }.getOrDefault(false)
    }

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        val source = URI(sourceUrl.get()).toURL()
        require(source.protocol == "https") { "Build dependencies require HTTPS" }
        val connection = source.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        val partial = Files.createTempFile(output.parentFile.toPath(), "dependency-", ".part")
        try {
            require(connection.responseCode in 200..299) { "Dependency download failed with HTTP ${connection.responseCode}" }
            require(connection.url.protocol == "https") { "Dependency redirect must use HTTPS" }
            connection.inputStream.use { input ->
                Files.newOutputStream(partial).use { outputStream ->
                    val copied = input.copyTo(outputStream)
                    val expected = connection.contentLengthLong
                    require(expected < 0L || copied == expected) { "Dependency download is incomplete" }
                }
            }
            ZipFile(partial.toFile()).use { require(it.size() > 0) { "Dependency archive is empty" } }
            Files.move(partial, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
            connection.disconnect()
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
        val expected = requiredPaths.get().toSet()
        val missing = expected
            .map { root.resolve(it) }
            .filterNot { file ->
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() > 0L
            }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }

        val rootPath = root.toPath().toAbsolutePath().normalize()
        val unexpected = Files.walk(rootPath).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .map { path -> rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/') }
                .filter { relative -> relative !in expected }
                .sorted()
                .toList()
        }
        require(unexpected.isEmpty()) {
            "Unexpected desktop native resources:\n" +
                unexpected.joinToString(separator = "\n") { "- $it" }
        }

        val links = Files.walk(rootPath).use { paths ->
            paths
                .filter(Files::isSymbolicLink)
                .map { path -> rootPath.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }
        require(links.isEmpty()) {
            "Symbolic links are not allowed in desktop native resources:\n" +
                links.joinToString(separator = "\n") { "- $it" }
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
        val markerFile = temporaryDir.resolve(
            "unifiedvpn-native-assets-${System.nanoTime()}.ok"
        )
        markerFile.delete()
        val process = ProcessBuilder(launcher.absolutePath, "--verify-native-assets")
            .directory(appImage)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .also { builder ->
                builder.environment()["APPDATA"] = smokeRoot.resolve("Roaming").absolutePath
                builder.environment()["LOCALAPPDATA"] = smokeRoot.resolve("Local").absolutePath
                builder.environment()["JPACKAGE_DEBUG"] = "true"
                builder.environment()["UNIFIEDVPN_NATIVE_VERIFY_MARKER"] = markerFile.absolutePath
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
        val marker = markerFile.takeIf { it.isFile }?.readText().orEmpty().trim()
        check(marker.lineSequence().any { line ->
            line.startsWith("Verified ") && line.endsWith(" desktop native assets")
        }) {
            "Windows launcher exited without creating its native-assets verification marker" +
                output.takeIf { it.isNotBlank() }?.let { ":\n$it" }.orEmpty()
        }
        logger.lifecycle("Verified Windows JVM launch and bundled native assets in ${appImage.name}")
    }
}

abstract class BuildAwareWindowsInstallerTask @Inject constructor(format: TargetFormat) :
    AbstractJPackageTask(format) {
    @get:Input
    abstract val unifiedVpnBuild: Property<Long>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val installerVerifier: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val packagingJmod: File
        get() = File(javaHome.get(), "jmods/jdk.jpackage.jmod")

    fun productCode(): String = UUID.nameUUIDFromBytes(
        listOf(
            "UnifiedVPN.WindowsInstaller.ProductCode.v1",
            packageVendor.getOrElse("Unknown"),
            packageName.get(),
            packageVersion.get(),
            unifiedVpnBuild.get().toString()
        ).map { requireNotNull(it) { "Windows installer identity is incomplete" } }
            .onEach { require('\u0000' !in it) { "Installer identity contains an invalid character" } }
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
    ).toString().uppercase()

    override fun prepareWorkingDir(inputChanges: InputChanges) {
        super.prepareWorkingDir(inputChanges)
        // Compose clears this directory in super; install the override afterwards.
        val template = Files.newInputStream(packagingJmod.toPath()).use { input ->
            check(input.readNBytes(4).contentEquals(byteArrayOf(0x4a, 0x4d, 0x01, 0x00))) {
                "The packaging JDK does not contain a supported jpackage JMOD"
            }
            ZipInputStream(input).use { archive ->
                var source: ByteArray? = null
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.name == "classes/jdk/jpackage/internal/resources/main.wxs") {
                        check(source == null) { "Duplicate jpackage main.wxs resource" }
                        val bytes = archive.readNBytes(256 * 1024 + 1)
                        check(bytes.size <= 256 * 1024) { "Unexpectedly large jpackage main.wxs" }
                        source = bytes
                    }
                }
                checkNotNull(source) { "The packaging JDK does not provide the WiX main.wxs template" }
            }
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(template))
        val namespace = "http://schemas.microsoft.com/wix/2006/wi"
        check(document.documentElement.localName == "Wix" && document.documentElement.namespaceURI == namespace) {
            "Unsupported jpackage WiX schema; installer template needs review"
        }
        fun single(parent: Element, name: String): Element {
            val matches = (0 until parent.childNodes.length)
                .map { parent.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.localName == name && it.namespaceURI == namespace }
            check(matches.size == 1) { "Expected one direct WiX $name element" }
            return matches.single()
        }
        fun expect(element: Element, attribute: String, expected: String) {
            check(element.getAttribute(attribute) == expected) {
                "Unexpected WiX ${element.localName}/@$attribute; installer template needs review"
            }
        }
        val product = single(document.documentElement, "Product")
        expect(product, "Id", "\$(var.JpProductCode)")
        expect(product, "Name", "\$(var.JpAppName)")
        expect(product, "Version", "\$(var.JpAppVersion)")
        expect(product, "Manufacturer", "\$(var.JpAppVendor)")
        expect(product, "UpgradeCode", "\$(var.JpProductUpgradeCode)")
        check(document.getElementsByTagNameNS(namespace, "MajorUpgrade").length == 0) {
            "Unexpected competing WiX major-upgrade policy"
        }
        val upgrade = single(product, "Upgrade")
        expect(upgrade, "Id", "\$(var.JpProductUpgradeCode)")
        val ranges = (0 until upgrade.childNodes.length)
            .map { upgrade.childNodes.item(it) }
            .filterIsInstance<Element>()
        check(ranges.size == 2 && ranges.all { it.localName == "UpgradeVersion" && it.namespaceURI == namespace }) {
            "Unexpected WiX upgrade ranges; installer template needs review"
        }
        val upgradeRange = ranges.singleOrNull { it.getAttribute("Property") == "JP_UPGRADABLE_FOUND" }
            ?: error("Missing unique WiX upgrade range")
        val downgradeRange = ranges.singleOrNull { it.getAttribute("Property") == "JP_DOWNGRADABLE_FOUND" }
            ?: error("Missing unique WiX downgrade range")
        expect(upgradeRange, "Maximum", "\$(var.JpAppVersion)")
        expect(upgradeRange, "Minimum", "")
        expect(upgradeRange, "OnlyDetect", "\$(var.JpUpgradeVersionOnlyDetectUpgrade)")
        expect(upgradeRange, "IncludeMaximum", "\$(var.JpUpgradeVersionOnlyDetectUpgrade)")
        expect(downgradeRange, "Minimum", "\$(var.JpAppVersion)")
        expect(downgradeRange, "Maximum", "")
        expect(downgradeRange, "OnlyDetect", "\$(var.JpUpgradeVersionOnlyDetectDowngrade)")
        expect(downgradeRange, "IncludeMinimum", "\$(var.JpUpgradeVersionOnlyDetectDowngrade)")
        val removeExisting = single(single(product, "InstallExecuteSequence"), "RemoveExistingProducts")
        expect(removeExisting, "Before", "CostInitialize")
        expect(removeExisting, "After", "")
        check(unifiedVpnBuild.get() > 0L) { "Windows installer requires a positive build number" }
        product.setAttribute("Id", productCode())
        upgradeRange.setAttribute("IncludeMaximum", "yes")
        downgradeRange.setAttribute("IncludeMinimum", "no")

        val output = jpackageResources.get().asFile.resolve("main.wxs")
        output.parentFile.mkdirs()
        val transformer = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer()
        Files.newOutputStream(output.toPath()).use { stream ->
            transformer.transform(DOMSource(document), StreamResult(stream))
        }
        logger.lifecycle("Prepared Windows installer ${packageVersion.get()} build ${unifiedVpnBuild.get()} ProductCode=${productCode()}")
    }

    fun verifyInstallerPolicy() {
        val extension = when (targetFormat) {
            TargetFormat.Exe -> "exe"
            TargetFormat.Msi -> "msi"
            else -> error("Windows installer policy verification requires EXE or MSI")
        }
        val installers = destinationDir.get().asFile.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
        check(installers.size == 1) { "Expected exactly one compiled Windows installer" }
        val powershell = File(
            requireNotNull(System.getenv("SystemRoot")) { "Windows system directory is unavailable" },
            "System32/WindowsPowerShell/v1.0/powershell.exe"
        )
        val log = temporaryDir.resolve("installer-policy-verification.log")
        val process = ProcessBuilder(
            powershell.absolutePath, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", installerVerifier.get().asFile.absolutePath,
            "-InstallerPath", installers.single().absolutePath,
            "-ExpectedVersion", requireNotNull(packageVersion.get()),
            "-ExpectedBuild", unifiedVpnBuild.get().toString(),
            "-ExpectedName", requireNotNull(packageName.get()),
            "-ExpectedVendor", requireNotNull(packageVendor.getOrElse("Unknown")),
            "-ExpectedUpgradeCode", requireNotNull(winUpgradeUuid.get())
        ).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(120L, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("Windows installer policy verification timed out")
        }
        val output = log.readText().trim()
        check(process.exitValue() == 0) { "Windows installer policy verification failed:\n$output" }
        logger.lifecycle("Verified compiled Windows installer build identity and same-version upgrade policy")
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
val desktopBuildNumber = providers.gradleProperty("olcbox.build").orElse("1").get()
val tun2SocksVersion = "2.6.0"
val wintunVersion = "0.14.1"
val xrayVersion = providers.gradleProperty("olcbox.xrayVersion").orElse("26.3.27").get()
val expectedAwgCoreCommit = providers.gradleProperty("olcbox.awgCoreSha")
val expectedXrayCommit = providers.gradleProperty("olcbox.xraySha")
val openFluxArtifactDirectory = rootProject.layout.projectDirectory.dir(".downloads/openflux/artifacts")
val openFluxManifest = openFluxArtifactDirectory.file("manifest.json")
val expectedOpenFluxUpstream = "4f1bdb554c262f3ae9adbfe317a092c6b929ba7d"
val expectedOpenFluxProtocol = "unified-openflux-aesgcm-v1"
val singBoxAwgRepoDir = providers.environmentVariable("SING_BOX_AWG_REPO")
    .map { rootProject.file(it) }
    .orElse(rootProject.layout.projectDirectory.dir(".downloads/sing-box-awg/source").asFile)
val xrayRepoDir = providers.environmentVariable("XRAY_REPO")
    .map { rootProject.file(it) }
    .orElse(rootProject.layout.projectDirectory.dir(".downloads/xray/source-v$xrayVersion").asFile)
val currentBuildTargetFormats = when {
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

fun verifyOpenFluxWindowsArtifact(binary: File) {
    check(binary.isFile && binary.length() > 0L && !Files.isSymbolicLink(binary.toPath())) {
        "OpenFlux Windows artifact is missing or invalid. Run tools/openflux/build.ps1 first."
    }
    val manifest = JsonSlurper().parse(openFluxManifest.asFile) as? Map<*, *>
        ?: error("Invalid OpenFlux artifact manifest")
    check(
        manifest["schema"] == 1 &&
            manifest["upstream"] == expectedOpenFluxUpstream &&
            manifest["protocol"] == expectedOpenFluxProtocol &&
            manifest["version_text"] == "unified-openflux 1 upstream=$expectedOpenFluxUpstream protocol=$expectedOpenFluxProtocol"
    ) { "OpenFlux artifact manifest does not match the pinned encrypted engine" }
    val expectedSha = ((manifest["files"] as? Map<*, *>)?.get("openflux-windows-amd64.exe") as? String)?.lowercase()
    check(expectedSha?.matches(Regex("[0-9a-f]{64}")) == true) {
        "OpenFlux artifact manifest has no Windows SHA-256"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    binary.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    check(actualSha == expectedSha) { "OpenFlux Windows SHA-256 does not match its artifact manifest" }
    binary.inputStream().use { input ->
        check(input.read() == 'M'.code && input.read() == 'Z'.code) { "OpenFlux Windows artifact is not a PE executable" }
    }
}

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

fun verifyPinnedGitSource(sourceDir: File, expectedCommit: String, label: String) {
    require(sourceDir.isDirectory) { "$label source directory is missing: ${sourceDir.absolutePath}" }
    fun gitOutput(vararg arguments: String): String {
        val process = ProcessBuilder("git", "-C", sourceDir.absolutePath, *arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        require(process.waitFor() == 0) { "$label git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }

    val actualCommit = gitOutput("rev-parse", "HEAD").lowercase()
    val changes = gitOutput("status", "--porcelain", "--untracked-files=no")
    require(actualCommit == expectedCommit.trim().lowercase() && changes.isBlank()) {
        "$label source must be clean and pinned to $expectedCommit; " +
            "found commit=$actualCommit modified=${changes.isNotBlank()}"
    }
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
    buildOlcRtcWindowsAmd64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64,
    copyOlcRtcDataAssets
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>(copyOlcRtcDataAssets)

when {
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

    val singBoxAwgLinuxOutput = generatedNativeResources.map {
        it.file("native/sing-box-awg-linux-$hostDesktopArch")
    }
    val xrayLinuxOutput = generatedNativeResources.map {
        it.file("native/xray-linux-$hostDesktopArch")
    }
    val buildSingBoxAwgLinux = tasks.register<Exec>("buildSingBoxAwgLinux") {
        val sourceDir = singBoxAwgRepoDir.get()
        val output = singBoxAwgLinuxOutput.get().asFile
        val tagsFile = sourceDir.resolve("release/DEFAULT_BUILD_TAGS_OTHERS")
        val baseLdflagsFile = sourceDir.resolve("release/LDFLAGS")

        inputs.files(fileTree(sourceDir) { exclude(".git/**") })
        inputs.files(sourceDir.resolve("go.mod"), sourceDir.resolve("go.sum"), tagsFile, baseLdflagsFile)
        inputs.property("awgCoreCommit", expectedAwgCoreCommit)
        outputs.file(singBoxAwgLinuxOutput)
        workingDir = sourceDir
        environment("GOOS", "linux")
        environment("GOARCH", hostDesktopArch)
        environment("CGO_ENABLED", "0")
        environment("GOTOOLCHAIN", "local")

        doFirst {
            verifyPinnedGitSource(sourceDir, expectedAwgCoreCommit.get(), "AWG sing-box")
            output.parentFile.mkdirs()
            val tags = tagsFile.readText().trim()
            val ldflags = buildString {
                append("-X github.com/sagernet/sing-box/constant.Version=")
                append(desktopPackageVersion)
                append(' ')
                append(baseLdflagsFile.readText().trim())
                append(" -s -w -buildid=")
            }
            commandLine(
                "go", "build", "-trimpath", "-tags", tags,
                "-ldflags", ldflags,
                "-o", output.absolutePath,
                "./cmd/sing-box"
            )
        }
    }
    val buildXrayLinux = tasks.register<Exec>("buildXrayLinux") {
        val sourceDir = xrayRepoDir.get()
        val output = xrayLinuxOutput.get().asFile

        inputs.files(fileTree(sourceDir) { exclude(".git/**") })
        inputs.files(sourceDir.resolve("go.mod"), sourceDir.resolve("go.sum"))
        inputs.property("xrayCommit", expectedXrayCommit)
        outputs.file(xrayLinuxOutput)
        workingDir = sourceDir
        environment("GOOS", "linux")
        environment("GOARCH", hostDesktopArch)
        environment("CGO_ENABLED", "0")
        environment("GOTOOLCHAIN", "local")

        doFirst {
            verifyPinnedGitSource(sourceDir, expectedXrayCommit.get(), "Xray")
            output.parentFile.mkdirs()
        }
        commandLine(
            "go", "build", "-trimpath", "-ldflags", "-s -w -buildid=",
            "-o", output.absolutePath,
            "./main"
        )
    }
    desktopNativeAssetTasks.add(buildSingBoxAwgLinux)
    desktopNativeAssetTasks.add(buildXrayLinux)
    hostDesktopNativeAssetTasks.add(buildSingBoxAwgLinux)
    hostDesktopNativeAssetTasks.add(buildXrayLinux)
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
    val openFluxWindowsSource = providers.environmentVariable("OPENFLUX_BINARY")
        .map { rootProject.file(it) }
        .orElse(openFluxArtifactDirectory.file("openflux-windows-amd64.exe").asFile)
    val openFluxWindowsOutput = generatedNativeResources.map {
        it.file("native/openflux-windows-amd64.exe")
    }

    val copyOpenFluxWindowsAmd64 = tasks.register<Copy>("copyOpenFluxWindowsAmd64") {
        from(openFluxWindowsSource)
        into(openFluxWindowsOutput.map { it.asFile.parentFile })
        rename { "openflux-windows-amd64.exe" }
        inputs.file(openFluxWindowsSource)
        inputs.file(openFluxManifest)
        inputs.property("openFluxUpstream", expectedOpenFluxUpstream)
        inputs.property("openFluxProtocol", expectedOpenFluxProtocol)
        outputs.file(openFluxWindowsOutput)
        doFirst { verifyOpenFluxWindowsArtifact(openFluxWindowsSource.get()) }
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
    desktopNativeAssetTasks.add(copyOpenFluxWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    hostDesktopNativeAssetTasks.add(copySingBoxAwgWindowsAmd64)
    hostDesktopNativeAssetTasks.add(copyXrayWindowsAmd64)
    hostDesktopNativeAssetTasks.add(copyOpenFluxWindowsAmd64)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    add("olcrtc-data/names")
    add("olcrtc-data/surnames")
    when {
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-amd64.exe")
            // add("native/olcrtc-windows-amd64.dll")
            add("native/tun2socks-windows-amd64.exe")
            add("native/wintun.dll")
            add("native/sing-box-awg-windows-amd64.exe")
            add("native/xray-windows-amd64.exe")
            add("native/openflux-windows-amd64.exe")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
            add("native/sing-box-awg-linux-$hostDesktopArch")
            add("native/xray-linux-$hostDesktopArch")
        }
    }
}

val stagedHostNativeResources = layout.buildDirectory.dir("generated/hostDesktopNativeResources")
val stageHostDesktopNativeResources = tasks.register<Sync>("stageHostDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    from(generatedNativeResources) {
        include(requiredHostNativeResourcePaths())
    }
    into(stagedHostNativeResources)
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(stageHostDesktopNativeResources)
    resourcesDir.set(stagedHostNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
}

sourceSets {
    main {
        resources.srcDir(stagedHostNativeResources)
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
            inputs.property("unifiedVpnBuildNumber", desktopBuildNumber)
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

    afterEvaluate {
        listOf("Exe" to TargetFormat.Exe, "Msi" to TargetFormat.Msi).forEach { (suffix, format) ->
            val original = tasks.named<AbstractJPackageTask>("packageRelease$suffix").get()
            val inheritedDependencies = original.dependsOn.toList()
            val outputDirectory = original.destinationDir.get()
            val replacement = tasks.register(
                "packageBuildAwareRelease$suffix",
                BuildAwareWindowsInstallerTask::class.java,
                format
            )
            replacement.configure {
                group = "distribution"
                description = "Builds a Windows installer that replaces earlier builds of the same version."
                dependsOn(inheritedDependencies)
                unifiedVpnBuild.set(desktopBuildNumber.toLong())
                installerVerifier.set(rootProject.layout.projectDirectory.file("tools/Verify-WindowsInstaller.ps1"))
                javaHome.set(original.javaHome)
                files.from(original.files)
                mangleJarFilesNames.set(original.mangleJarFilesNames)
                packageFromUberJar.set(original.packageFromUberJar)
                wixToolsetDir.set(original.wixToolsetDir)
                installationPath.set(original.installationPath)
                licenseFile.set(original.licenseFile)
                iconFile.set(original.iconFile)
                launcherMainClass.set(original.launcherMainClass)
                launcherMainJar.set(original.launcherMainJar)
                launcherArgs.set(original.launcherArgs)
                launcherJvmArgs.set(original.launcherJvmArgs)
                packageName.set(original.packageName)
                packageDescription.set(original.packageDescription)
                packageCopyright.set(original.packageCopyright)
                packageVendor.set(original.packageVendor)
                packageVersion.set(original.packageVersion)
                packageBuildVersion.set(original.packageBuildVersion)
                winConsole.set(original.winConsole)
                winDirChooser.set(original.winDirChooser)
                winPerUserInstall.set(original.winPerUserInstall)
                winShortcut.set(original.winShortcut)
                winMenu.set(original.winMenu)
                winMenuGroup.set(original.winMenuGroup)
                winUpgradeUuid.set(original.winUpgradeUuid)
                runtimeImage.set(original.runtimeImage)
                appImage.set(desktopAppImageDir)
                javaRuntimePropertiesFile.set(original.javaRuntimePropertiesFile)
                appResourcesDir.set(original.appResourcesDir)
                freeArgs.set(original.freeArgs)
                // Snapshot the output value, not its producer, to avoid an alias dependency cycle.
                destinationDir.set(outputDirectory)
                doLast { verifyInstallerPolicy() }
            }
            // Keep Compose's public task names, but never run their output-directory cleanup.
            original.enabled = false
            original.dependsOn(replacement)
        }
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
            inputs.property("unifiedVpnBuildNumber", desktopBuildNumber)
        }
    }

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set(
            "$desktopPackageName-$desktopPackageVersion-build.$desktopBuildNumber-portable-manual.zip"
        )
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }

    val windowsUpdateOutputDir = layout.buildDirectory.dir(
        "compose/binaries/main-release/update"
    )
    val windowsUpdateInstallerName =
        "$desktopPackageName-$desktopPackageVersion-build.$desktopBuildNumber-windows-amd64-installer.exe"
    val cleanReleaseUpdateBundle = tasks.register<Delete>("cleanReleaseUpdateBundle") {
        delete(windowsUpdateOutputDir)
    }
    tasks.matching { it.name == "packageReleaseExe" }.configureEach {
        dependsOn(cleanReleaseUpdateBundle)
    }

    tasks.register<Sync>("packageReleaseUpdateBundle") {
        group = "distribution"
        description = "Collects the installer-only Windows update asset without publishing it."
        dependsOn("packageReleaseExe")
        inputs.property("unifiedVpnPackageVersion", desktopPackageVersion)
        inputs.property("unifiedVpnBuildNumber", desktopBuildNumber)

        from(layout.buildDirectory.dir("compose/binaries/main-release/exe")) {
            include("*.exe")
            rename { windowsUpdateInstallerName }
        }
        into(windowsUpdateOutputDir)

        doLast {
            val files = windowsUpdateOutputDir.get().asFile
                .listFiles()
                .orEmpty()
                .filter { it.isFile }
            check(files.size == 1 && files.single().name == windowsUpdateInstallerName) {
                "Windows update bundle must contain exactly $windowsUpdateInstallerName"
            }
            check(files.single().length() > 0L) {
                "Windows update installer is empty"
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
    "packageReleaseAppImage",
    "packageReleasePortableZip",
    "packageReleaseUpdateBundle"
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
        }
    }
}

if (currentBuildOs.isLinux) {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/" +
            "$desktopPackageName-$desktopPackageVersion-build.$desktopBuildNumber-linux-$hostDesktopArch.AppImage"
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

    val verifyReleaseLinuxAppImage = tasks.register<Exec>("verifyReleaseLinuxAppImage") {
        group = "verification"
        description = "Launches the Linux AppImage in extract mode and verifies the JVM and native assets."

        dependsOn(packageReleaseLinuxAppImage)
        inputs.file(appImageFile)
        environment("APPIMAGE_EXTRACT_AND_RUN", "1")
        environment("HOME", layout.buildDirectory.dir("tmp/linux-smoke-home").get().asFile.absolutePath)
        commandLine(appImageFile.get().asFile.absolutePath, "--verify-native-assets")
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(verifyReleaseLinuxAppImage)
    }
}
