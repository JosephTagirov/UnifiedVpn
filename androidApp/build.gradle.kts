import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.io.FileInputStream
import java.util.zip.ZipFile

abstract class VerifyOlcRtcBindingsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val requiredAbis: ListProperty<String>

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
            val missingNativeEngines = ZipFile(apk).use { archive ->
                requiredAbis.get().flatMap { abi ->
                    REQUIRED_NATIVE_ENGINES.mapNotNull { library ->
                        val path = "lib/$abi/$library"
                        val entry = archive.getEntry(path)
                        path.takeIf { entry == null || entry.size <= 0L }
                    }
                }
            }
            check(missingNativeEngines.isEmpty()) {
                "${apk.name} is missing native VPN engines: ${missingNativeEngines.sorted().joinToString()}."
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
        private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
        private val REQUIRED_BINDINGS = setOf(
            "Lmobile/Mobile;",
            "Lmobile/Runtime;",
            "Lmobile/SocketProtector;",
        )
        private val REQUIRED_NATIVE_ENGINES = listOf(
            "libhev-socks5-tunnel.so",
            "libolcbox_tun2socks.so",
            "libsing-box.so",
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

listOf("debug", "release").forEach { buildType ->
    val buildTypeName = buildType.replaceFirstChar { character -> character.uppercase() }
    val verifyTask = tasks.register<VerifyOlcRtcBindingsTask>("verify${buildTypeName}OlcRtcBindings") {
        group = "verification"
        description = "Verifies that the $buildType APK contains the olcRTC gomobile bindings."
        apkDirectory.set(layout.buildDirectory.dir("outputs/apk/$buildType"))
        requiredAbis.set(androidAbiFilters)
    }

    tasks.matching { task -> task.name == "assemble$buildTypeName" }.configureEach {
        finalizedBy(verifyTask)
    }
}

tasks.register<Copy>("packageReleaseUpdateApk") {
    group = "distribution"
    description = "Copies the signed release APK to the build-aware GitHub update filename."
    dependsOn("assembleRelease")
    inputs.property("unifiedVpnVersion", olcboxVersion)
    inputs.property("unifiedVpnBuildNumber", olcboxBuild)

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename {
            "UnifiedVPN-${olcboxVersion.get()}-build.${olcboxBuild.get()}-android-universal.apk"
        }
    }
    into(layout.buildDirectory.dir("outputs/update"))

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
}

tasks.register<Sync>("packageDebugUpdateApk") {
    group = "distribution"
    description = "Copies the locally installable debug APK to a build-aware test filename."
    dependsOn("assembleDebug")
    inputs.property("unifiedVpnVersion", olcboxVersion)
    inputs.property("unifiedVpnBuildNumber", olcboxBuild)

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename {
            "UnifiedVPN-${olcboxVersion.get()}-build.${olcboxBuild.get()}-android-universal-debug.apk"
        }
    }
    into(layout.buildDirectory.dir("outputs/update"))

    doFirst {
        val debugDirectory = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val debugApks = debugDirectory
            .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
            .orEmpty()
        require(debugApks.size == 1) {
            "Expected exactly one debug APK in ${debugDirectory.absolutePath}; found ${debugApks.size}"
        }
    }
}
