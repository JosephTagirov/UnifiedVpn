package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class OlcRtcFailureMessagesTest {
    @Test
    fun explainsIncompatibleRecordLayer() {
        val message = friendlyOlcRtcFailure("muxconn: decrypt failed: open record: bad record magic")

        assertContains(message, "incompatible record-layer versions")
    }

    @Test
    fun explainsAuthenticatedHandshakeTimeout() {
        val message = friendlyOlcRtcFailure(
            "run public client: client: handshake: read welcome: read hdr: timeout"
        )

        assertContains(message, "server did not answer")
        assertContains(message, "same olcRTC protocol version")
    }

    @Test
    fun preservesUnrelatedErrors() {
        assertEquals("network unavailable", friendlyOlcRtcFailure("network unavailable"))
    }
}
