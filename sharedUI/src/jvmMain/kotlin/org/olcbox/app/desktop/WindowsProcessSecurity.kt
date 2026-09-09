package org.olcbox.app.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path

object WindowsProcessSecurity {
    /**
     * Returns true when the Windows process is elevated or its token cannot be verified safely.
     * Non-Windows processes never need this Windows-specific refusal.
     */
    fun shouldRefuseCurrentProcess(): Boolean = shouldRefuseProcess(DesktopPaths.os) {
        queryCurrentProcessElevation()
    }

    internal fun shouldRefuseProcess(
        os: DesktopOs,
        elevationQuery: () -> Boolean
    ): Boolean {
        if (os != DesktopOs.Windows) return false
        return runCatching(elevationQuery).getOrDefault(true)
    }

    private fun queryCurrentProcessElevation(): Boolean {
        val tokenReference = PointerByReference()
        check(
            tokenApi.OpenProcessToken(
                kernelApi.GetCurrentProcess(),
                TOKEN_QUERY,
                tokenReference
            )
        ) {
            "Windows process token could not be opened (error ${Native.getLastError()})"
        }
        val token = checkNotNull(tokenReference.value) {
            "Windows returned an empty process token"
        }
        return try {
            val elevation = Memory(Int.SIZE_BYTES.toLong())
            val returnedLength = IntByReference()
            check(
                tokenApi.GetTokenInformation(
                    token,
                    TOKEN_INFORMATION_CLASS_ELEVATION,
                    elevation,
                    elevation.size().toInt(),
                    returnedLength
                )
            ) {
                "Windows process elevation could not be read (error ${Native.getLastError()})"
            }
            check(returnedLength.value == Int.SIZE_BYTES) {
                "Windows returned an invalid process elevation record"
            }
            elevation.getInt(0L) != 0
        } finally {
            kernelApi.CloseHandle(token)
        }
    }

    private const val TOKEN_QUERY = 0x0008
    private const val TOKEN_INFORMATION_CLASS_ELEVATION = 20

    private val kernelApi: WindowsProcessKernelApi by lazy {
        Native.load("kernel32", WindowsProcessKernelApi::class.java)
    }

    private val tokenApi: WindowsProcessTokenApi by lazy {
        Native.load("advapi32", WindowsProcessTokenApi::class.java)
    }

    private interface WindowsProcessKernelApi : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        fun CloseHandle(handle: Pointer): Boolean
    }

    private interface WindowsProcessTokenApi : StdCallLibrary {
        fun OpenProcessToken(
            processHandle: Pointer,
            desiredAccess: Int,
            tokenHandle: PointerByReference
        ): Boolean

        fun GetTokenInformation(
            tokenHandle: Pointer,
            tokenInformationClass: Int,
            tokenInformation: Pointer,
            tokenInformationLength: Int,
            returnLength: IntByReference
        ): Boolean
    }
}

object WindowsTrustedExecutables {
    fun powerShellPath(): Path = resolveFromSystemDirectory(
        listOf("WindowsPowerShell", "v1.0", "powershell.exe")
    )

    fun regPath(): Path = resolveFromSystemDirectory(listOf("reg.exe"))

    private fun resolveFromSystemDirectory(relativeParts: List<String>): Path {
        check(DesktopPaths.os == DesktopOs.Windows) {
            "Trusted Windows executables are only available on Windows"
        }
        val buffer = CharArray(32_768)
        val length = systemDirectoryApi.GetSystemDirectoryW(buffer, buffer.size)
        check(length > 0 && length < buffer.size) {
            "Windows system directory could not be resolved safely"
        }
        return resolveTrustedExecutable(
            systemDirectory = Path.of(String(buffer, 0, length)),
            relativeParts = relativeParts
        )
    }

    internal fun resolveTrustedExecutable(
        systemDirectory: Path,
        relativeParts: List<String>,
        isRegularFile: (Path) -> Boolean = { path -> Files.isRegularFile(path) }
    ): Path {
        require(relativeParts.isNotEmpty() && relativeParts.all { part ->
            part.isNotBlank() &&
                part != "." &&
                part != ".." &&
                '/' !in part &&
                '\\' !in part
        }) {
            "Trusted Windows executable path is invalid"
        }
        val root = systemDirectory.toAbsolutePath().normalize()
        val executable = relativeParts.fold(root) { current, part -> current.resolve(part) }.normalize()
        check(executable.startsWith(root) && isRegularFile(executable)) {
            "Trusted Windows executable was not found: ${relativeParts.last()}"
        }
        return executable
    }

    private val systemDirectoryApi: WindowsSystemDirectoryApi by lazy {
        Native.load("kernel32", WindowsSystemDirectoryApi::class.java)
    }

    private interface WindowsSystemDirectoryApi : StdCallLibrary {
        fun GetSystemDirectoryW(buffer: CharArray, size: Int): Int
    }
}
