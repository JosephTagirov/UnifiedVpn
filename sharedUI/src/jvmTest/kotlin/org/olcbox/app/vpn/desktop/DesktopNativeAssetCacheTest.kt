package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopNativeAssetCacheTest {
    @Test fun sourceChangesDuringExtractionLeaveThePreviousVerifiedAssetIntact() {
        val directory = Files.createTempDirectory("asset-source-")
        val target = directory.resolve("core.bin")
        val old = byteArrayOf(1, 2, 3)
        Files.write(target, old)
        val opens = AtomicInteger()
        try {
            assertFailsWith<IllegalStateException> {
                DesktopNativeAssets.installVerifiedAsset(target) {
                    ByteArrayInputStream(if (opens.incrementAndGet() == 1) byteArrayOf(4, 5, 6) else byteArrayOf(7, 8, 9))
                }
            }
            assertContentEquals(old, Files.readAllBytes(target))
            assertEquals(listOf(target), Files.list(directory).use { it.toList() })
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory) }
    }

    @Test fun verifiedEqualAssetsAreNotRewrittenAndUnlockedChangesAreInstalledAtomically() {
        val directory = Files.createTempDirectory("asset-current-")
        val target = directory.resolve("core.bin")
        val first = byteArrayOf(1, 2, 3)
        try {
            DesktopNativeAssets.installVerifiedAsset(target) { ByteArrayInputStream(first) }
            val marker = FileTime.fromMillis(1_000)
            Files.setLastModifiedTime(target, marker)
            DesktopNativeAssets.installVerifiedAsset(target) { ByteArrayInputStream(first) }
            assertEquals(marker, Files.getLastModifiedTime(target))
            val changed = byteArrayOf(4, 5, 6)
            DesktopNativeAssets.installVerifiedAsset(target) { ByteArrayInputStream(changed) }
            assertContentEquals(changed, Files.readAllBytes(target))
            assertEquals(listOf(target), Files.list(directory).use { it.toList() })
        } finally { Files.deleteIfExists(target); Files.deleteIfExists(directory) }
    }

    /** A real mapped PE image reproduces the Windows lock which affected AWG children and the filter. */
    @Test fun runningVerifiedWindowsExecutableIsReusableButChangedLockedAssetsAreRejected() {
        val root = System.getenv("DESKTOP_ENTRY_FAILOVER_TEST_ROOT")?.let(Path::of) ?: return
        val system = System.getenv("SystemRoot")?.let(Path::of) ?: return
        val directory = Files.createTempDirectory(root, "asset-lock-")
        val source = directory.resolve("source.exe")
        val target = directory.resolve("cached.exe")
        var process: Process? = null
        try {
            Files.copy(system.resolve("System32/ping.exe"), source, StandardCopyOption.REPLACE_EXISTING)
            DesktopNativeAssets.installVerifiedAsset(target) { Files.newInputStream(source) }
            process = ProcessBuilder(target.toString(), "-t", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            Thread.sleep(100)
            assertTrue(process.isAlive)
            DesktopNativeAssets.installVerifiedAsset(target) { Files.newInputStream(source) }
            assertTrue(process.isAlive, "Resolving an equal resource must preserve the running image")
            Files.write(source, byteArrayOf(0), StandardOpenOption.APPEND)
            assertFailsWith<java.nio.file.FileSystemException> {
                DesktopNativeAssets.installVerifiedAsset(target) { Files.newInputStream(source) }
            }
            assertTrue(process.isAlive)
            assertEquals(Files.size(source) - 1, Files.size(target))
            assertEquals(2L, Files.list(directory).use { it.count() })
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                assertTrue(it.waitFor(5, TimeUnit.SECONDS))
            }
            Files.deleteIfExists(source)
            // Windows image/antivirus handles can outlive the process's signalled exit briefly.
            deleteStoppedTestImage(target, directory)
            Files.deleteIfExists(directory)
        }
    }

    private fun deleteStoppedTestImage(target: Path, ownedDirectory: Path) {
        require(target.toAbsolutePath().normalize().parent == ownedDirectory.toAbsolutePath().normalize())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try { Files.deleteIfExists(target); return }
            catch (locked: AccessDeniedException) {
                if (System.nanoTime() >= deadline) throw locked
                Thread.sleep(50)
            }
        }
    }
}
