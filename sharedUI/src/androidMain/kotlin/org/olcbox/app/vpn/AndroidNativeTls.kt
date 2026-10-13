package org.olcbox.app.vpn

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import java.io.File
import tlsbootstrap.Tlsbootstrap

object AndroidNativeTls {
    @Synchronized
    fun initialize(context: Context) {
        try {
            val pem = OlcRtcTrustedRoots.supplementalPem()
            val file = File(context.noBackupFilesDir, "native-tls-roots.pem")
            val atomicFile = AtomicFile(file)
            val existing = runCatching { atomicFile.readFully() }.getOrNull()
            if (existing == null || !existing.contentEquals(pem)) {
                val output = atomicFile.startWrite()
                try {
                    output.write(pem)
                    atomicFile.finishWrite(output)
                } catch (error: Exception) {
                    atomicFile.failWrite(output)
                    throw error
                }
            }
            // Child native engines inherit libc's environment; gomobile has a separate Go copy.
            Os.setenv("SSL_CERT_FILE", file.absolutePath, true)
            Tlsbootstrap.configure(file.absolutePath)
            Log.i("UnifiedVPN.NativeTLS", "Supplemental GlobalSign R46 root verified by Go; TLS verification enabled")
        } catch (error: Exception) {
            // Retain normal fail-closed TLS behavior without crashing the whole application.
            Log.e("UnifiedVPN.NativeTLS", "Supplemental TLS root preparation failed", error)
        } catch (error: LinkageError) {
            Log.e("UnifiedVPN.NativeTLS", "Native TLS bootstrap binding is unavailable", error)
        }
    }
}
