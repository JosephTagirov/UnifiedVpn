import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

abstract class StageOpenFluxAndroidLibrariesTask : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

abstract class VerifyOlcRtcBindingsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val requiredAbis: ListProperty<String>

    @get:Input
    abstract val forbiddenBuildPathPrefixes: ListProperty<String>

    @TaskAction
    fun verifyBindings() {
        val apkFiles = apkDirectory.get().asFile
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        check(apkFiles.isNotEmpty()) {
            "No APK files found in ${apkDirectory.get().asFile}"
        }

        apkFiles.forEach { apk ->
            val expectedNativeLibraries = requiredAbis.get()
                .flatMap { abi -> EXPECTED_NATIVE_LIBRARIES.map { library -> "lib/$abi/$library" } }
                .toSet()
            val archiveEntries = ZipFile(apk).use { archive ->
                archive.entries().asSequence().toList()
            }
            val nativeLibraries = archiveEntries
                .filter { entry -> !entry.isDirectory && NATIVE_LIBRARY_ENTRY.matches(entry.name) }
                .associateBy { entry -> entry.name }
            val missingNativeLibraries = expectedNativeLibraries.filter { path ->
                nativeLibraries[path]?.size?.let { size -> size > 0L } != true
            }
            val unexpectedNativeLibraries = nativeLibraries.keys - expectedNativeLibraries
            check(missingNativeLibraries.isEmpty()) {
                "${apk.name} is missing native libraries: ${missingNativeLibraries.sorted().joinToString()}."
            }
            check(unexpectedNativeLibraries.isEmpty()) {
                "${apk.name} contains unexpected native libraries or ABIs: " +
                    unexpectedNativeLibraries.sorted().joinToString()
            }

            val forbiddenEntries = archiveEntries
                .map { entry -> entry.name.replace('\\', '/') }
                .filter(::isForbiddenArchiveEntry)
            check(forbiddenEntries.isEmpty()) {
                "${apk.name} contains forbidden private build inputs: ${forbiddenEntries.sorted().joinToString()}."
            }

            val forbiddenNativeLibraries = ZipFile(apk).use { archive ->
                nativeLibraries.keys.mapNotNull { path ->
                    val containsLocalPath = archive.getInputStream(archive.getEntry(path)).use { input ->
                        val bytes = input.readBytes()
                        forbiddenBuildPathPrefixes.get().any { prefix ->
                            bytes.containsAsciiIgnoreCase(prefix.encodeToByteArray())
                        }
                    }
                    path.takeIf { containsLocalPath }
                }
            }
            check(forbiddenNativeLibraries.isEmpty()) {
                "${apk.name} contains absolute local build paths in native libraries: " +
                    forbiddenNativeLibraries.sorted().joinToString()
            }

            val definedClasses = ZipFile(apk).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> DEX_ENTRY.matches(entry.name) }
                    .flatMap { entry ->
                        archive.getInputStream(entry).use { input ->
                            readDefinedClasses(input.readBytes()).asSequence()
                        }
                    }
                    .toSet()
            }
            val missingClasses = REQUIRED_BINDINGS - definedClasses

            check(missingClasses.isEmpty()) {
                "${apk.name} is missing olcRTC bindings: ${missingClasses.sorted().joinToString()}. " +
                    "The generated olcrtc.aar must be packaged directly into androidApp."
            }
            logger.lifecycle("Verified olcRTC bindings and native VPN engines in ${apk.name}")
        }
    }

    private fun isForbiddenArchiveEntry(path: String): Boolean {
        val normalized = path.lowercase().trimStart('/')
        val fileName = normalized.substringAfterLast('/')
        return normalized.contains("private-test-profiles/") ||
            normalized.endsWith("/locations_v4.json") ||
            normalized == "locations_v4.json" ||
            fileName == "keystore.properties" ||
            fileName == "local.properties" ||
            fileName == ".env"
    }

    private fun ByteArray.containsAsciiIgnoreCase(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false

        val lastStart = size - needle.size
        for (start in 0..lastStart) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset].asciiLowercase() != needle[offset].asciiLowercase()) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun Byte.asciiLowercase(): Byte {
        val value = toInt() and 0xff
        return if (value in 'A'.code..'Z'.code) {
            (value + ('a'.code - 'A'.code)).toByte()
        } else {
            this
        }
    }

    private fun readDefinedClasses(dex: ByteArray): Set<String> {
        check(dex.size >= DEX_HEADER_SIZE && dex.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) {
            "Invalid DEX file in APK"
        }

        val stringIdsSize = readUInt32(dex, STRING_IDS_SIZE_OFFSET)
        val stringIdsOffset = readUInt32(dex, STRING_IDS_OFFSET_OFFSET)
        val typeIdsSize = readUInt32(dex, TYPE_IDS_SIZE_OFFSET)
        val typeIdsOffset = readUInt32(dex, TYPE_IDS_OFFSET_OFFSET)
        val classDefsSize = readUInt32(dex, CLASS_DEFS_SIZE_OFFSET)
        val classDefsOffset = readUInt32(dex, CLASS_DEFS_OFFSET_OFFSET)

        requireSection(dex, stringIdsOffset, stringIdsSize, UINT_SIZE, "string_ids")
        requireSection(dex, typeIdsOffset, typeIdsSize, UINT_SIZE, "type_ids")
        requireSection(dex, classDefsOffset, classDefsSize, CLASS_DEF_SIZE, "class_defs")

        return buildSet {
            repeat(classDefsSize) { index ->
                val classIndex = readUInt32(dex, classDefsOffset + index * CLASS_DEF_SIZE)
                check(classIndex < typeIdsSize) { "Invalid class type index in DEX" }

                val descriptorIndex = readUInt32(dex, typeIdsOffset + classIndex * UINT_SIZE)
                check(descriptorIndex < stringIdsSize) { "Invalid class descriptor index in DEX" }

                val stringDataOffset = readUInt32(dex, stringIdsOffset + descriptorIndex * UINT_SIZE)
                add(readStringData(dex, stringDataOffset))
            }
        }
    }

    private fun readStringData(dex: ByteArray, offset: Int): String {
        check(offset in dex.indices) { "Invalid string data offset in DEX" }

        var cursor = offset
        repeat(MAX_ULEB128_BYTES) {
            check(cursor < dex.size) { "Truncated string length in DEX" }
            val value = dex[cursor++].toInt() and 0xff
            if (value and 0x80 == 0) {
                var end = cursor
                while (end < dex.size && dex[end].toInt() != 0) {
                    end++
                }
                check(end < dex.size) { "Unterminated string data in DEX" }
                return String(dex, cursor, end - cursor, StandardCharsets.UTF_8)
            }
        }
        error("Invalid ULEB128 string length in DEX")
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
        check(offset >= 0 && offset.toLong() + UINT_SIZE <= bytes.size.toLong()) {
            "Invalid uint32 offset in DEX"
        }
        val value =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        check(value <= Int.MAX_VALUE) { "DEX value exceeds supported size" }
        return value.toInt()
    }

    private fun requireSection(
        dex: ByteArray,
        offset: Int,
        count: Int,
        itemSize: Int,
        name: String,
    ) {
        val end = offset.toLong() + count.toLong() * itemSize
        check(offset >= 0 && count >= 0 && end <= dex.size.toLong()) {
            "Invalid $name section in DEX"
        }
    }

    companion object {
        private val DEX_ENTRY = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
        private val NATIVE_LIBRARY_ENTRY = Regex("lib/[^/]+/[^/]+\\.so")
        private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
        private val REQUIRED_BINDINGS = setOf(
            "Lmobile/Mobile;",
            "Lmobile/Runtime;",
            "Lmobile/SocketProtector;",
        )
        private val EXPECTED_NATIVE_LIBRARIES = listOf(
            "libandroidx.graphics.path.so",
            "libdatastore_shared_counter.so",
            "libgojni.so",
            "libhev-socks5-tunnel.so",
            "libimage_processing_util_jni.so",
            "libolcbox_tun2socks.so",
            "libopenflux.so",
            "libsing-box.so",
            "libsurface_util_jni.so",
            "libxray.so",
        )

        private const val UINT_SIZE = 4
        private const val DEX_HEADER_SIZE = 0x70
        private const val STRING_IDS_SIZE_OFFSET = 0x38
        private const val STRING_IDS_OFFSET_OFFSET = 0x3c
        private const val TYPE_IDS_SIZE_OFFSET = 0x40
        private const val TYPE_IDS_OFFSET_OFFSET = 0x44
        private const val CLASS_DEFS_SIZE_OFFSET = 0x60
        private const val CLASS_DEFS_OFFSET_OFFSET = 0x64
        private const val CLASS_DEF_SIZE = 32
        private const val MAX_ULEB128_BYTES = 5
    }
}

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("0.0.10")
val olcboxBuild = providers.gradleProperty("olcbox.build")
    .map { it.toInt() }
    .orElse(1)
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(olcboxBuild)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)
val openFluxArtifactDirectory = rootProject.layout.projectDirectory.dir(".downloads/openflux/artifacts")
val openFluxManifest = openFluxArtifactDirectory.file("manifest.json")
val generatedOpenFluxJniLibs = layout.buildDirectory.dir("generated/openfluxJniLibs")
val expectedOpenFluxUpstream = "4f1bdb554c262f3ae9adbfe317a092c6b929ba7d"
val expectedOpenFluxProtocol = "unified-openflux-aesgcm-v1"

fun verifyOpenFluxAndroidArtifact(binary: File, artifactName: String, manifestFile: File, abi: String) {
    check(binary.isFile && binary.length() > 0L && !Files.isSymbolicLink(binary.toPath())) {
        "OpenFlux Android artifact is missing or invalid: $artifactName. Run tools/openflux/build.ps1 first."
    }
    val manifest = JsonSlurper().parse(manifestFile) as? Map<*, *>
        ?: error("Invalid OpenFlux artifact manifest")
    check(
        manifest["schema"] == 1 &&
            manifest["upstream"] == expectedOpenFluxUpstream &&
            manifest["protocol"] == expectedOpenFluxProtocol &&
            manifest["version_text"] == "unified-openflux 1 upstream=$expectedOpenFluxUpstream protocol=$expectedOpenFluxProtocol"
    ) { "OpenFlux artifact manifest does not match the pinned encrypted engine" }
    val expectedSha = ((manifest["files"] as? Map<*, *>)?.get(artifactName) as? String)?.lowercase()
    check(expectedSha?.matches(Regex("[0-9a-f]{64}")) == true) {
        "OpenFlux artifact manifest has no SHA-256 for $artifactName"
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
    check(actualSha == expectedSha) { "OpenFlux SHA-256 mismatch for $artifactName" }
    val header = binary.inputStream().use { it.readNBytes(20) }
    val expectedMachine = when (abi) {
        "armeabi-v7a" -> 40
        "arm64-v8a" -> 183
        "x86_64" -> 62
        else -> error("Unsupported OpenFlux Android ABI: $abi")
    }
    check(
        header.size == 20 &&
            header.take(4) == listOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) &&
            header[4].toInt() == (if (abi == "armeabi-v7a") 1 else 2) &&
            header[5].toInt() == 1 &&
            ((header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)) == expectedMachine
    ) { "OpenFlux ELF architecture does not match $abi" }
}

val stageOpenFluxAndroidLibraries = tasks.register<StageOpenFluxAndroidLibrariesTask>("stageOpenFluxAndroidLibraries") {
    group = "build"
    description = "Stages manifest-verified encrypted OpenFlux executables for Android packaging."
    inputs.file(openFluxManifest)
    inputs.property("openFluxUpstream", expectedOpenFluxUpstream)
    inputs.property("openFluxProtocol", expectedOpenFluxProtocol)
    inputs.property("openFluxAbis", androidAbiFilters)
    androidAbiFilters.forEach { abi ->
        inputs.file(openFluxArtifactDirectory.file("android/$abi/libopenflux.so"))
    }
    from(openFluxArtifactDirectory.dir("android")) {
        include(androidAbiFilters.map { "$it/libopenflux.so" })
    }
    outputDirectory.set(generatedOpenFluxJniLibs)
    into(outputDirectory)
    doFirst {
        check(openFluxManifest.asFile.isFile) {
            "OpenFlux artifact manifest is missing. Run tools/openflux/build.ps1 first."
        }
        androidAbiFilters.forEach { abi ->
            val artifactName = "android/$abi/libopenflux.so"
            verifyOpenFluxAndroidArtifact(
                openFluxArtifactDirectory.file(artifactName).asFile,
                artifactName,
                openFluxManifest.asFile,
                abi
            )
        }
    }
}
val forbiddenNativeBuildPathPrefixes = providers.provider {
    val projectDirectory = rootProject.layout.projectDirectory.asFile.canonicalFile
    val userHome = File(System.getProperty("user.home")).canonicalFile
    val olcrtcDirectory = providers.gradleProperty("OLCRTC_REPO")
        .orElse(providers.environmentVariable("OLCRTC_REPO"))
        .orElse(projectDirectory.parentFile.resolve("olcrtc").absolutePath)
        .get()
        .let(::File)
        .canonicalFile

    val absolutePaths = listOf(projectDirectory, userHome, olcrtcDirectory)
        .flatMap { directory ->
            val path = directory.absolutePath
            listOf(path, path.replace('\\', '/'))
        }
        .filter { it.length >= 3 }

    (absolutePaths + listOf(".downloads/", ".downloads\\"))
        .distinct()
}

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 23
        targetSdk = 35

        applicationId = "app.unifiedvpn.local"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val olcrtcAndroidAar = files(
    rootProject.file("sharedUI/build/generated/olcrtc/olcrtc.aar")
)

dependencies {
    implementation(project(":sharedUI"))
    implementation(olcrtcAndroidAar)
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)
}

tasks.matching { task -> task.name == "preBuild" }.configureEach {
    dependsOn(":sharedUI:buildOlcrtcAndroidAar")
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(
        stageOpenFluxAndroidLibraries,
        StageOpenFluxAndroidLibrariesTask::outputDirectory
    )
}

listOf("debug", "release").forEach { buildType ->
    val buildTypeName = buildType.replaceFirstChar { character -> character.uppercase() }
    val verifyTask = tasks.register<VerifyOlcRtcBindingsTask>("verify${buildTypeName}OlcRtcBindings") {
        group = "verification"
        description = "Verifies that the $buildType APK contains the olcRTC gomobile bindings."
        apkDirectory.set(layout.buildDirectory.dir("outputs/apk/$buildType"))
        requiredAbis.set(androidAbiFilters)
        forbiddenBuildPathPrefixes.set(forbiddenNativeBuildPathPrefixes)
    }

    tasks.matching { task -> task.name == "assemble$buildTypeName" }.configureEach {
        finalizedBy(verifyTask)
    }
}

val androidUpdateOutputDir = layout.buildDirectory.dir("outputs/update")
val releaseUpdateApkName = providers.provider {
    "UnifiedVPN-${olcboxVersion.get()}-build.${olcboxBuild.get()}-android-universal.apk"
}
val debugUpdateApkName = providers.provider {
    "UnifiedVPN-${olcboxVersion.get()}-build.${olcboxBuild.get()}-android-universal-debug.apk"
}

tasks.register<Sync>("packageReleaseUpdateApk") {
    group = "distribution"
    description = "Copies the signed release APK to the build-aware GitHub update filename."
    dependsOn("assembleRelease")
    inputs.property("unifiedVpnVersion", olcboxVersion)
    inputs.property("unifiedVpnBuildNumber", olcboxBuild)

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { releaseUpdateApkName.get() }
    }
    into(androidUpdateOutputDir)

    doFirst {
        require(hasReleaseKeystore) {
            "A signed GitHub update APK requires keystore.properties; refusing to package an unsigned release."
        }
        val releaseDirectory = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val releaseApks = releaseDirectory
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
        require(releaseApks.size == 1 && "unsigned" !in releaseApks.single().name.lowercase()) {
            "Expected exactly one release APK in ${releaseDirectory.absolutePath}; found ${releaseApks.size}"
        }
    }

    doLast {
        val files = androidUpdateOutputDir.get().asFile.listFiles().orEmpty().filter { it.isFile }
        check(files.size == 1 && files.single().name == releaseUpdateApkName.get()) {
            "Android release update directory must contain exactly ${releaseUpdateApkName.get()}"
        }
        check(files.single().length() > 0L) { "Android release update APK is empty" }
    }
}

tasks.register<Sync>("packageDebugUpdateApk") {
    group = "distribution"
    description = "Copies the locally installable debug APK to a build-aware test filename."
    dependsOn("assembleDebug")
    inputs.property("unifiedVpnVersion", olcboxVersion)
    inputs.property("unifiedVpnBuildNumber", olcboxBuild)

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { debugUpdateApkName.get() }
    }
    into(androidUpdateOutputDir)

    doFirst {
        val debugDirectory = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val debugApks = debugDirectory
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
        require(debugApks.size == 1) {
            "Expected exactly one debug APK in ${debugDirectory.absolutePath}; found ${debugApks.size}"
        }
    }


    doLast {
        val files = androidUpdateOutputDir.get().asFile.listFiles().orEmpty().filter { it.isFile }
        check(files.size == 1 && files.single().name == debugUpdateApkName.get()) {
            "Android debug update directory must contain exactly ${debugUpdateApkName.get()}"
        }
        check(files.single().length() > 0L) { "Android debug update APK is empty" }
    }
}
