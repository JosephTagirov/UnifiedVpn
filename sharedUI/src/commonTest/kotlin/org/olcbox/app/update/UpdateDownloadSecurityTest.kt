package org.olcbox.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateDownloadSecurityTest {
    @Test
    fun automaticUpdatesRequireGithubSha256Digest() {
        assertEquals(
            "a".repeat(64),
            UpdateDownloadSecurity.normalizeGithubSha256Digest("SHA256:${"A".repeat(64)}")
        )
        listOf(null, "", "sha1:${"a".repeat(40)}", "sha256:not-a-digest").forEach { digest ->
            assertFailsWith<IllegalArgumentException> {
                UpdateDownloadSecurity.normalizeGithubSha256Digest(digest)
            }
        }
    }

    @Test
    fun automaticUpdatesRequireHttps() {
        assertEquals(
            "HTTPS://example.test/app.apk",
            UpdateDownloadSecurity.requireHttpsUrl(" HTTPS://example.test/app.apk ")
        )
        listOf(
            "http://example.test/app.apk",
            "file:///tmp/app.apk",
            "//example.test/app.apk",
            "app.apk"
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException> {
                UpdateDownloadSecurity.requireHttpsUrl(url)
            }
        }
    }

    @Test
    fun metadataAndContentLengthCannotExceedOneGigabyte() {
        UpdateDownloadSecurity.validateMetadataSize(null)
        UpdateDownloadSecurity.validateMetadataSize(1L)
        UpdateDownloadSecurity.validateMetadataSize(UpdateDownloadSecurity.MAX_UPDATE_BYTES)
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.validateMetadataSize(0L)
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.validateMetadataSize(UpdateDownloadSecurity.MAX_UPDATE_BYTES + 1L)
        }

        assertNull(UpdateDownloadSecurity.knownContentLength(-1L))
        assertNull(UpdateDownloadSecurity.knownContentLength(0L))
        assertEquals(
            UpdateDownloadSecurity.MAX_UPDATE_BYTES,
            UpdateDownloadSecurity.knownContentLength(UpdateDownloadSecurity.MAX_UPDATE_BYTES)
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.knownContentLength(UpdateDownloadSecurity.MAX_UPDATE_BYTES + 1L)
        }
    }

    @Test
    fun streamingCounterRejectsTheFirstByteOverTheLimit() {
        assertEquals(
            UpdateDownloadSecurity.MAX_UPDATE_BYTES,
            UpdateDownloadSecurity.addDownloadedBytes(
                UpdateDownloadSecurity.MAX_UPDATE_BYTES - 1L,
                1
            )
        )
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.addDownloadedBytes(
                UpdateDownloadSecurity.MAX_UPDATE_BYTES,
                1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.addDownloadedBytes(-1L, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateDownloadSecurity.addDownloadedBytes(0L, -1)
        }
    }
}
