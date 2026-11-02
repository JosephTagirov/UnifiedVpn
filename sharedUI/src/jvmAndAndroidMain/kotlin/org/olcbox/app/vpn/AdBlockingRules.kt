package org.olcbox.app.vpn

import org.jetbrains.compose.resources.ExperimentalResourceApi
import unifiedvpn.sharedui.generated.resources.Res
import java.io.File
import java.security.MessageDigest

internal object AdBlockingRules {
    const val SHA256 = "6c62f367faf262b65561199a2e654f34bf491d63ac9c03b1c91d36d86706ba50"
    const val DOMAIN_COUNT = 78564

    @OptIn(ExperimentalResourceApi::class)
    suspend fun install(directory: File): File {
        val target = File(directory, "ads-$SHA256.json")
        if (target.isFile && digest(target.readBytes()) == SHA256) return target
        val bytes = Res.readBytes("files/adblocking/hagezi-light.json")
        return installBytes(directory, bytes)
    }

    internal fun installBytes(directory: File, bytes: ByteArray): File {
        require(digest(bytes) == SHA256) { "Ad blocking list integrity check failed" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare ad blocking directory" }
        val target = File(directory, "ads-$SHA256.json")
        if (target.exists() && digest(target.readBytes()) != SHA256) {
            check(target.delete()) { "Cannot replace damaged ad blocking list" }
        }
        val temporary = File.createTempFile("ads-", ".json.tmp", directory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                check(target.isFile && digest(target.readBytes()) == SHA256) { "Cannot install ad blocking list" }
            }
        } finally { temporary.delete() }
        return target
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
