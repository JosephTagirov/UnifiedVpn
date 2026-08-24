import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

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

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder(
                listOf("git", "-C", repository.absolutePath) + arguments
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
