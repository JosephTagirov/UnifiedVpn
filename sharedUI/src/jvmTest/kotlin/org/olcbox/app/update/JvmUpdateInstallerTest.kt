package org.olcbox.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JvmUpdateInstallerTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancelledReadDoesNotRemoveConcurrentDownloadOfSameAsset() = runBlocking {
        withDownloadDirectory { directory ->
            val payload = "verified update payload".encodeToByteArray()
            val asset = updateAsset(payload)
            val firstBody = TestInputStream(payload, blockReads = true)
            val secondBody = TestInputStream(payload, blockReads = true)
            val firstConnection = TestHttpConnection(firstBody, asset.sizeBytes!!)
            val secondConnection = TestHttpConnection(secondBody, asset.sizeBytes!!)
            val first = async(Dispatchers.IO) {
                download(JvmUpdateInstaller(directory), asset, firstConnection)
            }
            var second: Deferred<Path>? = null
            try {
                firstBody.awaitRead()
                val firstPartial = partialFiles(directory).single()
                first.cancel()

                val secondDownload = async(Dispatchers.IO) {
                    download(JvmUpdateInstaller(directory), asset, secondConnection)
                }
                second = secondDownload
                secondBody.awaitRead()
                val secondPartial = (partialFiles(directory) - setOf(firstPartial)).single()

                firstBody.release()
                withTimeout(5_000) { first.join() }
                assertFailsWith<CancellationException> { first.await() }
                assertFalse(Files.exists(firstPartial))
                assertTrue(Files.exists(secondPartial))
                assertTrue(firstBody.closed)
                assertTrue(firstConnection.disconnected)

                secondBody.release()
                val target = withTimeout(5_000) { secondDownload.await() }
                assertContentEquals(payload, Files.readAllBytes(target))
                assertTrue(partialFiles(directory).isEmpty())
                assertTrue(secondBody.closed)
                assertTrue(secondConnection.disconnected)
            } finally {
                firstBody.release()
                secondBody.release()
                first.cancel()
                second?.cancel()
                withContext(NonCancellable) {
                    withTimeout(5_000) {
                        first.join()
                        second?.join()
                    }
                }
            }
        }
    }

    @Test
    fun wrongDigestRemovesPartialAndPreservesPreviousUpdate() = runBlocking {
        withDownloadDirectory { directory ->
            val payload = "new update payload".encodeToByteArray()
            val previous = "previous verified update".encodeToByteArray()
            val asset = updateAsset(payload).copy(digest = "sha256:${"0".repeat(64)}")
            val target = directory.resolve(asset.name)
            Files.write(target, previous)
            val body = TestInputStream(payload)
            val connection = TestHttpConnection(body, asset.sizeBytes!!)

            val error = assertFailsWith<IllegalArgumentException> {
                download(JvmUpdateInstaller(directory), asset, connection)
            }

            assertTrue("SHA-256 mismatch" in error.message.orEmpty())
            assertContentEquals(previous, Files.readAllBytes(target))
            assertTrue(partialFiles(directory).isEmpty())
            assertTrue(body.closed)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun truncatedDownloadRemovesPartialAndPreservesPreviousUpdate() = runBlocking {
        withDownloadDirectory { directory ->
            val payload = "complete verified update payload".encodeToByteArray()
            val previous = "previous verified update".encodeToByteArray()
            val asset = updateAsset(payload)
            val target = directory.resolve(asset.name)
            Files.write(target, previous)
            val body = TestInputStream(payload.copyOf(payload.size - 3))
            val connection = TestHttpConnection(body, asset.sizeBytes!!)

            val error = assertFailsWith<IllegalArgumentException> {
                download(JvmUpdateInstaller(directory), asset, connection)
            }

            assertTrue("size mismatch" in error.message.orEmpty())
            assertContentEquals(previous, Files.readAllBytes(target))
            assertTrue(partialFiles(directory).isEmpty())
            assertTrue(body.closed)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun verifiedDownloadReplacesPreviousUpdateAndClosesConnection() = runBlocking {
        withDownloadDirectory { directory ->
            val payload = "verified replacement update".encodeToByteArray()
            val asset = updateAsset(payload)
            val target = directory.resolve(asset.name)
            Files.write(target, "previous update".encodeToByteArray())
            val body = TestInputStream(payload)
            val connection = TestHttpConnection(body, asset.sizeBytes!!)
            val progress = mutableListOf<Float>()

            val downloaded = download(JvmUpdateInstaller(directory), asset, connection) {
                progress += it
            }

            assertEquals(target, downloaded)
            assertContentEquals(payload, Files.readAllBytes(downloaded))
            assertTrue(partialFiles(directory).isEmpty())
            assertEquals(1f, progress.last())
            assertTrue(progress.all { it in 0f..1f })
            assertTrue(body.closed)
            assertTrue(connection.disconnected)
        }
    }

    private suspend fun download(
        installer: JvmUpdateInstaller,
        asset: AppUpdateAsset,
        connection: TestHttpConnection,
        onProgress: (Float) -> Unit = {}
    ): Path = installer.download(
        asset = asset,
        expectedSha256 = UpdateDownloadSecurity.normalizeGithubSha256Digest(asset.digest),
        proxySettings = null,
        onProgress = onProgress,
        connectionFactory = { url, proxy ->
            assertEquals(TEST_URL, url.toString())
            assertNull(proxy)
            connection
        }
    )

    private fun updateAsset(payload: ByteArray): AppUpdateAsset {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return AppUpdateAsset(
            name = "UnifiedVPN-test.exe",
            downloadUrl = TEST_URL,
            sizeBytes = payload.size.toLong(),
            digest = "sha256:$sha256"
        )
    }

    private suspend fun withDownloadDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("unifiedvpn-download-test-")
        try {
            block(directory)
        } finally {
            Files.newDirectoryStream(directory).use { files ->
                files.forEach { Files.deleteIfExists(it) }
            }
            Files.deleteIfExists(directory)
        }
    }

    private fun partialFiles(directory: Path): Set<Path> =
        Files.newDirectoryStream(directory, "*.part").use { it.toSet() }

    private class TestInputStream(
        payload: ByteArray,
        blockReads: Boolean = false
    ) : ByteArrayInputStream(payload) {
        private val readStarted = CountDownLatch(1)
        private val readGate = CountDownLatch(if (blockReads) 1 else 0)

        @Volatile
        var closed = false
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            if (!readGate.await(10, TimeUnit.SECONDS)) {
                throw IOException("Test download read was not released")
            }
            return super.read(buffer, offset, length)
        }

        fun awaitRead() {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS), "Download did not reach its input stream")
        }

        fun release() {
            readGate.countDown()
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TestHttpConnection(
        private val body: InputStream,
        private val bodyLength: Long
    ) : HttpURLConnection(URL(TEST_URL)) {
        @Volatile
        var disconnected = false
            private set

        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = HTTP_OK
        override fun getContentLengthLong(): Long = bodyLength
        override fun getInputStream(): InputStream = body

        override fun disconnect() {
            disconnected = true
        }
    }

    private companion object {
        const val TEST_URL = "https://updates.example.invalid/UnifiedVPN-test.exe"
    }
}
