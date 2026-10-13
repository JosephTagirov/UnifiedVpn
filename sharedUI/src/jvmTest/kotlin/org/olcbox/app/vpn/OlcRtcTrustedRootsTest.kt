package org.olcbox.app.vpn

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OlcRtcTrustedRootsTest {
    @Test
    fun bundledRootHasExpectedFingerprintAndSelfSignature() {
        val pem = OlcRtcTrustedRoots.supplementalPem()
        OlcRtcTrustedRoots.validate(pem)
        val root = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.inputStream()) as X509Certificate
        assertTrue(root.subjectX500Principal.name.contains("CN=GlobalSign Root R46"))
        assertEquals(root.subjectX500Principal, root.issuerX500Principal)
        assertTrue(root.basicConstraints >= 0)
    }

    @Test
    fun mutatedCertificateIsNotTrusted() {
        val der = CertificateFactory.getInstance("X.509")
            .generateCertificate(OlcRtcTrustedRoots.supplementalPem().inputStream()).encoded
        der[der.lastIndex] = (der.last().toInt() xor 1).toByte()
        val pem = ("-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder().encodeToString(der) + "\n-----END CERTIFICATE-----\n").toByteArray()
        val error = assertFailsWith<IllegalArgumentException> { OlcRtcTrustedRoots.validate(pem) }
        assertEquals("Supplemental TLS root fingerprint mismatch", error.message)
    }

    @Test
    fun extraCertificatesAreRejected() {
        val pem = OlcRtcTrustedRoots.supplementalPem()
        assertFails { OlcRtcTrustedRoots.validate(pem + pem) }
    }

    @Test
    fun emptyOrMalformedCertificateIsRejected() {
        assertFails { OlcRtcTrustedRoots.validate(byteArrayOf()) }
        assertFails { OlcRtcTrustedRoots.validate("not a certificate".toByteArray()) }
    }

    @Test
    fun callersCannotMutateTheBundledCertificate() {
        OlcRtcTrustedRoots.supplementalPem().fill(0)
        OlcRtcTrustedRoots.validate(OlcRtcTrustedRoots.supplementalPem())
    }
}
