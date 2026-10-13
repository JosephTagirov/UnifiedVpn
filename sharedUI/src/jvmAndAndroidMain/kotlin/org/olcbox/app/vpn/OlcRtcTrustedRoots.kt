package org.olcbox.app.vpn

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object OlcRtcTrustedRoots {
    // Public TLS root, not a server certificate. Source and independently published fingerprint:
    // https://secure.globalsign.com/cacert/rootr46.crt
    // https://bugzilla.mozilla.org/show_bug.cgi?id=1693173
    private const val R46_SHA256 = "4fa3126d8d3a11d1c4855a4f807cbad6cf919d3a5a88b03bea2c6372d93c40c9"

    private val r46Pem = """
        -----BEGIN CERTIFICATE-----
        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
        UxeCp6
        -----END CERTIFICATE-----
    """.trimIndent() + "\n"

    fun supplementalPem(): ByteArray = r46Pem.toByteArray(Charsets.US_ASCII).also(::validate)

    internal fun validate(pem: ByteArray) {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(pem.inputStream())
        require(certificates.size == 1) { "Expected exactly one supplemental TLS root" }
        val certificate = certificates.single() as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(fingerprint == R46_SHA256) { "Supplemental TLS root fingerprint mismatch" }
        require(certificate.basicConstraints >= 0 && certificate.keyUsage?.get(5) == true) {
            "Supplemental TLS certificate must be a CA"
        }
        require(certificate.subjectX500Principal == certificate.issuerX500Principal) {
            "Supplemental TLS certificate must be a root"
        }
        certificate.verify(certificate.publicKey)
    }
}
