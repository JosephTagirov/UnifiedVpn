package org.olcbox.app.vpn.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxClassicDocumentTest {
    @Test
    fun classicDocumentHandoffIsOptionalAndKeepsExactSourceIdentity() {
        val document = OpenFluxClassicDocument.parse(valid().toString(), URL, NOW)
        assertEquals(URL, (document.json["source_url"] as JsonPrimitive).content)
        val cookies = parseOpenFluxBrowserCookies("proof=synthetic", URL)
        assertTrue(openFluxBrowserResponse(ID, cookies, "SyntheticBrowser/1.0", document).contains("classic_document"))
        assertFalse(openFluxBrowserResponse(ID, cookies, "SyntheticBrowser/1.0").contains("classic_document"))
        assertFalse(document.toString().contains("synthetic-token"))
        assertFailsWith<OpenFluxBrowserException> { OpenFluxClassicDocument.parse(valid().toString(), "$URL&other=1", NOW) }
    }

    @Test
    fun fieldSetTypesAndPermissionsMustMatchExactly() {
        val fields = valid()
        fields.keys.forEach { key -> rejects(JsonObject(fields - key)) }
        rejects(JsonObject(fields + ("unknown" to JsonPrimitive("private"))))
        fields.keys.filter { it !in setOf("captured_at", "edit") }.forEach { key ->
            rejects(JsonObject(fields + (key to JsonPrimitive(1))))
        }
        listOf(JsonPrimitive("true"), JsonPrimitive(false), JsonPrimitive(1)).forEach { rejects(changed("edit", it)) }
        listOf(JsonPrimitive(NOW.toString()), JsonPrimitive(1.5)).forEach { rejects(changed("captured_at", it)) }
    }

    @Test
    fun timestampsHaveBoundedAgeAndClockSkew() {
        listOf(NOW - 120, NOW, NOW + 5).forEach {
            assertNotNull(OpenFluxClassicDocument.parse(changed("captured_at", JsonPrimitive(it)).toString(), URL, NOW))
        }
        listOf(NOW - 121, NOW + 6, Long.MIN_VALUE, Long.MAX_VALUE).forEach {
            rejects(changed("captured_at", JsonPrimitive(it)))
        }
    }

    @Test
    fun providerUrlsRequireStrictDnsHttpsAndAnOriginOnlyBalancer() {
        listOf("http://editor.yandex.ru", "https://yandex.ru", "https://yandex.net", "https://fake-yandex.ru",
            "https://editor.yandex.ru.attacker.invalid", "https://editor.yandex.ru.", "https://-editor.yandex.ru",
            "https://a..yandex.ru", "https://user@editor.yandex.ru",
            "https://editor.yandex.ru:444", "https://editor.yandex.ru:0443", "https://editor.yandex.ru:",
            "https://editor.yandex.ru\\private", "https://editor.yandex.ru/#", "https://editor.yandex.ru?",
            "https://editor.yandex.ru/", "https://editor.yandex.ru/path").forEach {
            rejects(changed("balancer_url", JsonPrimitive(it)))
        }
        listOf("https://a.yandex.ru", "https://a.yandex.net:443", "https://A.YANDEX.RU").forEach {
            assertNotNull(OpenFluxClassicDocument.parse(changed("balancer_url", JsonPrimitive(it)).toString(), URL, NOW))
        }
        listOf("https://yandex.ru/a", "https://a.yandex.net/#", "https://a.yandex.net/path#private",
            "https://a.yandex.net/path with space", "https://a.yandex.net/path\u0085value").forEach {
            rejects(changed("download_url", JsonPrimitive(it)))
        }
        assertNotNull(OpenFluxClassicDocument.parse(changed("download_url", JsonPrimitive("https://a.yandex.net/path?key=synthetic")).toString(), URL, NOW))
    }

    @Test
    fun underscoreExceptionIsRestrictedToClassicBalancerOrigins() {
        listOf("https://editor_1.yandex.ru", "https://_editor_.yandex.net:443", "https://_.yandex.ru").forEach {
            assertTrue(isOpenFluxClassicBalancerOrigin(it))
            assertNotNull(OpenFluxClassicDocument.parse(changed("balancer_url", JsonPrimitive(it)).toString(), URL, NOW))
            assertFalse(isOpenFluxClassicDownloadReference(it))
            assertFalse(isOpenFluxBrowserNavigationAllowed(it))
            assertFalse(isOpenFluxBrowserResourceAllowed(it))
        }
        listOf("https://_editor_.yandex.ru/", "https://_editor_.yandex.ru?", "https://_editor_.yandex.ru#",
            "https://_editor_.yandex.ru:0443", "https://_editor_.yandex.ru:", "https://_editor_.yandex.ru:444",
            "https://user@_editor_.yandex.ru", "https://_editor_.yandex.ru.", "https://_editor_.yandex.ru.attacker.invalid",
            "https://-editor_.yandex.ru", "https://editor_-.yandex.ru", "https://editor_..yandex.ru",
            "https://" + "_".repeat(64) + ".yandex.ru").forEach {
            assertFalse(isOpenFluxClassicBalancerOrigin(it), it)
        }
    }

    @Test
    fun metadataFailureStagesAreAllowlistedAndNeverIncludeRejectedValues() {
        listOf(
            Triple("balancer_url", "https://user@private.invalid", OpenFluxBrowserValidationStage.ClassicBalancerUrl),
            Triple("balancer_url", "https://editor.yandex.ru/private", OpenFluxBrowserValidationStage.ClassicBalancerOrigin),
            Triple("download_url", "http://private.invalid/token", OpenFluxBrowserValidationStage.ClassicDownload),
            Triple("token", "private secret", OpenFluxBrowserValidationStage.ClassicToken),
            Triple("document_key", "..", OpenFluxBrowserValidationStage.ClassicKey),
            Triple("file_type", "private/invalid", OpenFluxBrowserValidationStage.ClassicFileType),
            Triple("title", "private\nvalue", OpenFluxBrowserValidationStage.ClassicTitle)
        ).forEach { (field, rejected, stage) ->
            val failure = assertFailsWith<OpenFluxBrowserException> {
                OpenFluxClassicDocument.parse(changed(field, JsonPrimitive(rejected)).toString(), URL, NOW)
            }
            assertEquals(stage, failure.validationStage)
            assertEquals(OpenFluxBrowserFailure.Failed.diagnostic, failure.message)
            assertFalse(failure.toString().contains(rejected))
            assertEquals(stage, openFluxBrowserValidationStage(stage.code))
        }
        listOf(null, "", "classic_download private-token", "https://private.invalid", "unknown").forEach {
            assertNull(openFluxBrowserValidationStage(it))
        }
    }

    @Test
    fun fieldBoundsRejectOversizedOrUnsafeData() {
        mapOf("token" to 16_384, "document_key" to 512, "file_type" to 32).forEach { (key, limit) ->
            assertNotNull(OpenFluxClassicDocument.parse(changed(key, JsonPrimitive("a".repeat(limit))).toString(), URL, NOW))
            rejects(changed(key, JsonPrimitive("a".repeat(limit + 1))))
            rejects(changed(key, JsonPrimitive("")))
        }
        listOf(".", "..").forEach { rejects(changed("document_key", JsonPrimitive(it))) }
        listOf("a b", "a\tb", "a\u0080b").forEach {
            rejects(changed("token", JsonPrimitive(it)))
            rejects(changed("document_key", JsonPrimitive(it)))
        }
        rejects(changed("file_type", JsonPrimitive("doc/x")))
        rejects(changed("download_url", JsonPrimitive("https://a.yandex.net/" + "a".repeat(8192))))
        assertNotNull(OpenFluxClassicDocument.parse(changed("title", JsonPrimitive("")).toString(), URL, NOW))
        assertNotNull(OpenFluxClassicDocument.parse(changed("title", JsonPrimitive("\u00e9".repeat(512))).toString(), URL, NOW))
        rejects(changed("title", JsonPrimitive("\u00e9".repeat(513))))
        listOf("\u0000", "\n", "\u0085").forEach { rejects(changed("title", JsonPrimitive(it))) }
    }

    @Test
    fun urlBoundsCountUtf8BytesAndRejectSourceFragments() {
        val prefix = "https://files.yandex.net/"
        val exactLimit = prefix + "\u044f".repeat((8192 - prefix.length) / 2) + "x"
        assertEquals(8192, exactLimit.toByteArray(Charsets.UTF_8).size)
        assertNotNull(OpenFluxClassicDocument.parse(changed("download_url", JsonPrimitive(exactLimit)).toString(), URL, NOW))
        rejects(changed("download_url", JsonPrimitive(exactLimit + "x")))
        rejects(changed("download_url", JsonPrimitive(prefix + "\u044f".repeat(5000))))
        listOf("#", "#fragment").forEach { fragment ->
            val source = URL + fragment
            assertNull(openFluxBrowserDocumentHost(source))
            assertFailsWith<OpenFluxBrowserException> {
                OpenFluxClassicDocument.parse(changed("source_url", JsonPrimitive(source)).toString(), source, NOW)
            }
        }
    }

    @Test
    fun titleRequiresStrictUtf8ButAllowsValidSurrogatePairs() {
        listOf("\ud800", "\udfff", "before\ud800after", "\udfff\ud800").forEach {
            rejects(changed("title", JsonPrimitive(it)))
        }
        listOf("\\ud800", "\\udfff").forEach { escaped ->
            val raw = valid().toString().replace("\"Synthetic document\"", "\"$escaped\"")
            assertFailsWith<OpenFluxBrowserException> { OpenFluxClassicDocument.parse(raw, URL, NOW) }
        }
        val title = "\ud83d\ude00".repeat(256)
        assertNotNull(OpenFluxClassicDocument.parse(changed("title", JsonPrimitive(title)).toString(), URL, NOW))
        rejects(changed("title", JsonPrimitive(title + "x")))
    }

    @Test
    fun providerInternalDownloadReferenceIsOpaqueAndNeverAWebViewDestination() {
        val reference = "http://localhost:12701/disk/synthetic/file.docx?signature=synthetic%2Bproof"
        assertTrue(isOpenFluxClassicDownloadReference(reference))
        assertNotNull(OpenFluxClassicDocument.parse(changed("download_url", JsonPrimitive(reference)).toString(), URL, NOW))
        assertFalse(isOpenFluxBrowserNavigationAllowed(reference))
        assertFalse(isOpenFluxBrowserResourceAllowed(reference))
        val encodedPath = "http://localhost:12701/disk/%D1%84?signature=%ff"
        assertTrue(isOpenFluxClassicDownloadReference(encodedPath))
        val encodedDocument = OpenFluxClassicDocument.parse(changed("download_url", JsonPrimitive(encodedPath)).toString(), URL, NOW)
        assertEquals(encodedPath, (encodedDocument.json["download_url"] as JsonPrimitive).content)
        listOf(
            "http://localhost:12701/disk/", "http://localhost:12701/disk/file#", "http://localhost:12701/disk/file#fragment",
            "http://127.0.0.1:12701/disk/file", "http://LOCALHOST:12701/disk/file", "HTTP://localhost:12701/disk/file",
            "http://localhost:12702/disk/file", "http://localhost:012701/disk/file", "http://localhost/disk/file",
            "https://localhost:12701/disk/file", "http://user@localhost:12701/disk/file", "http://localhost:12701/other/file",
            "http://localhost:12701/disk/../file", "http://localhost:12701/disk/%2e/file", "http://localhost:12701/disk/%2E%2E/file",
            "http://localhost:12701/disk/%252e%252e/file", "http://localhost:12701/disk/a%2f..%2ffile",
            "http://localhost:12701/disk/file%5cprivate", "http://localhost:12701/disk/file%20private",
            "http://localhost:12701/disk/file%09private", "http://localhost:12701/disk/file%00private",
            "http://localhost:12701/disk/file%23private", "http://localhost:12701/disk/file%3fprivate",
            "http://localhost:12701/disk/file%", "http://localhost:12701/disk/file?bad=%zz",
            "http://localhost:12701/disk/%ff", "http://localhost:12701/disk/%c0%af",
            "http://localhost:12701/disk/%ed%a0%80", "http://localhost:12701/disk/%D1x%84"
        ).forEach { candidate ->
            assertFalse(isOpenFluxClassicDownloadReference(candidate), candidate)
            rejects(changed("download_url", JsonPrimitive(candidate)))
        }
    }

    @Test
    fun totalMetadataAndReplyLimitsApplyAfterSerialization() {
        val oversized = JsonObject(valid() + mapOf(
            "token" to JsonPrimitive("t".repeat(16_384)),
            "download_url" to JsonPrimitive("https://a.yandex.net/" + "a".repeat(8100)),
            "title" to JsonPrimitive("t".repeat(1024))
        ))
        rejects(oversized)
        val document = OpenFluxClassicDocument.parse(changed("token", JsonPrimitive("t".repeat(16_384))).toString(), URL, NOW)
        val cookies = parseOpenFluxBrowserCookies((1..4).joinToString("; ") { "p$it=" + "c".repeat(4000) }, URL)
        assertFailsWith<OpenFluxBrowserException> { openFluxBrowserResponse(ID, cookies, "u".repeat(1024), document) }
    }

    @Test
    fun browserCaptureAddsOwnerTimestampAndSourceWithoutAcceptingExtraFields() {
        val fields = JsonObject(valid() - "source_url" - "captured_at")
        val capture = openFluxBrowserCapturedDocument(JsonPrimitive(fields.toString()).toString(), URL, NOW)
        assertEquals(valid(), capture?.json)
        assertNull(openFluxBrowserCapturedDocument("null", URL, NOW))
        listOf(null, "false", "{}", "\"not-json\"", JsonPrimitive(valid().toString()).toString()).forEach {
            assertFailsWith<OpenFluxBrowserException> { openFluxBrowserCapturedDocument(it, URL, NOW) }
        }
    }

    private fun changed(key: String, value: JsonPrimitive) = JsonObject(valid() + (key to value))

    private fun rejects(value: JsonObject) {
        assertFailsWith<OpenFluxBrowserException> { OpenFluxClassicDocument.parse(value.toString(), URL, NOW) }
    }

    private fun valid() = buildJsonObject {
        put("source_url", URL)
        put("captured_at", NOW)
        put("balancer_url", "https://editor.yandex.ru")
        put("token", "synthetic-token")
        put("document_key", "synthetic-key")
        put("file_type", "docx")
        put("download_url", "https://download.yandex.net/file?key=synthetic")
        put("title", "Synthetic document")
        put("edit", true)
    }

    private companion object {
        const val URL = "https://docs.yandex.ru/docs/view?url=synthetic%2Fraw"
        const val ID = "0123456789abcdef0123456789abcdef"
        const val NOW = 1_700_000_000L
    }
}
