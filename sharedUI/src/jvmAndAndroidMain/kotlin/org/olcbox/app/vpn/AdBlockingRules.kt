package org.olcbox.app.vpn

import org.jetbrains.compose.resources.ExperimentalResourceApi
import unifiedvpn.sharedui.generated.resources.Res
import java.io.File
import java.security.MessageDigest

internal object AdBlockingRules {
    const val SHA256 = "6c62f367faf262b65561199a2e654f34bf491d63ac9c03b1c91d36d86706ba50"
    const val DOMAIN_COUNT = 78564
    const val RESOURCE_PATH = "files/adblocking/hagezi-light.json"
    const val ANDROID_ASSET_PATH = "composeResources/unifiedvpn.sharedui.generated.resources/$RESOURCE_PATH"
    private const val BUNDLED_SIZE = 1_843_966L

    @OptIn(ExperimentalResourceApi::class)
    suspend fun install(directory: File): File = install(directory) { Res.readBytes(RESOURCE_PATH) }

    suspend fun install(directory: File, readBundled: suspend () -> ByteArray): File {
        val target = File(directory, "ads-$SHA256.json")
        if (matchesPinnedList(target)) return target
        return installBytes(directory, readBundled())
    }

    internal fun installBytes(directory: File, bytes: ByteArray): File {
        require(digest(bytes) == SHA256) { "Ad blocking list integrity check failed" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare ad blocking directory" }
        val target = File(directory, "ads-$SHA256.json")
        val temporary = File.createTempFile("ads-", ".json.tmp", directory)
        try {
            temporary.writeBytes(bytes)
            if (target.exists() && !matchesPinnedList(target)) {
                check(target.delete() || matchesPinnedList(target)) { "Cannot replace damaged ad blocking list" }
            }
            if (!temporary.renameTo(target)) {
                check(matchesPinnedList(target)) { "Cannot install ad blocking list" }
            }
        } finally { temporary.delete() }
        return target
    }

    private fun matchesPinnedList(file: File): Boolean =
        file.isFile && file.length() == BUNDLED_SIZE &&
            runCatching { digest(file.readBytes()) == SHA256 }.getOrDefault(false)

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
