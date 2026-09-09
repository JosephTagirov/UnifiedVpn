package org.olcbox.app.update

internal object UpdateDownloadSecurity {
    const val MAX_UPDATE_BYTES = 1_000_000_000L

    fun requireHttpsUrl(value: String): String {
        val normalized = value.trim()
        require(normalized.startsWith("https://", ignoreCase = true)) {
            "Update download requires HTTPS"
        }
        return normalized
    }

    fun normalizeGithubSha256Digest(value: String?): String {
        require(!value.isNullOrBlank()) {
            "The update does not provide a SHA-256 digest; automatic installation was refused"
        }
        val parts = value.trim().split(':', limit = 2)
        require(parts.size == 2 && parts[0].equals("sha256", ignoreCase = true)) {
            "Unsupported update digest: ${parts.firstOrNull().orEmpty()}"
        }
        val digest = parts[1].lowercase()
        require(SHA256_PATTERN.matches(digest)) {
            "The update provides an invalid SHA-256 digest"
        }
        return digest
    }

    fun validateMetadataSize(sizeBytes: Long?) {
        sizeBytes ?: return
        require(sizeBytes in 1..MAX_UPDATE_BYTES) {
            "Update metadata contains an invalid file size"
        }
    }

    fun knownContentLength(contentLength: Long): Long? {
        if (contentLength <= 0L) return null
        require(contentLength <= MAX_UPDATE_BYTES) {
            "Update download is larger than the allowed limit"
        }
        return contentLength
    }

    fun addDownloadedBytes(current: Long, read: Int): Long {
        require(current >= 0L && read >= 0) { "Invalid update download byte count" }
        require(current <= MAX_UPDATE_BYTES - read.toLong()) {
            "Update download is larger than the allowed limit"
        }
        return current + read
    }

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
