package org.olcbox.app.vpn

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class ManagedEntryDnsTest {
    @Test fun acceptsOnlyAResponseForTheOwnedQuestionAndMatchingTransaction() {
        val query = ManagedEntryDns.query(1234)
        val answer = reply(query, listOf(a(ManagedEntryDns.PRIMARY_HOST, "178.208.70.181")))
        assertEquals(listOf("178.208.70.181"), ManagedEntryDns.addresses(query, answer))
        assertTrue(ManagedEntryDns.addresses(query, answer.copyOf().also { it[1] = 1 }).isEmpty())
        assertTrue(ManagedEntryDns.addresses(query, answer.copyOf().also { it[13] = 'x'.code.toByte() }).isEmpty())
    }

    @Test fun cnameAndAddressRecordsMayArriveInEitherOrderButUnrelatedAnswersCannotRedirectTheEntry() {
        val query = ManagedEntryDns.query(4321)
        val alias = cname(ManagedEntryDns.PRIMARY_HOST, "edge.example")
        val address = a("edge.example", "8.8.4.4")
        for (records in listOf(listOf(alias, address), listOf(address, alias)))
            assertEquals(listOf("8.8.4.4"), ManagedEntryDns.addresses(query, reply(query, records)))
        assertTrue(ManagedEntryDns.addresses(query, reply(query, listOf(address))).isEmpty())
    }

    @Test fun rejectsTruncatedMalformedLoopingAndNegativeResponses() {
        val query = ManagedEntryDns.query(99)
        val valid = reply(query, listOf(a(ManagedEntryDns.PRIMARY_HOST, "178.208.70.181")))
        for (response in listOf(valid.copyOf(10), valid.copyOf(valid.size - 1),
            valid.copyOf().also { it[2] = (it[2].toInt() or 2).toByte() },
            valid.copyOf().also { it[3] = (it[3].toInt() or 3).toByte() },
            valid.copyOf().also { it[4] = 1 },
            valid.copyOf().also { it[12] = 0xc0.toByte(); it[13] = 12 },
            reply(query, listOf(cname(ManagedEntryDns.PRIMARY_HOST, "edge.example"), cname("edge.example", ManagedEntryDns.PRIMARY_HOST)))))
            assertTrue(ManagedEntryDns.addresses(query, response).isEmpty())
    }

    @Test fun privateSyntheticAndReservedAddressesCannotBecomeAVpnServer() {
        val query = ManagedEntryDns.query(7)
        for (address in listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "100.64.0.1", "169.254.1.1", "172.16.0.1",
            "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"))
            assertTrue(ManagedEntryDns.addresses(query, reply(query, listOf(a(ManagedEntryDns.PRIMARY_HOST, address)))).isEmpty(), address)
    }

    @Test fun failedOrUntrustedDnsFallsBackIndependentlyAndCannotCollapsePrimaryIntoBackup() {
        val servers = mutableListOf<String>()
        assertEquals("178.208.70.181", ManagedEntryDns.resolve { server, _ -> servers += server; null })
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), servers)
        assertEquals("178.208.70.181", ManagedEntryDns.resolve { _, query -> reply(query, listOf(a(ManagedEntryDns.PRIMARY_HOST, "48.103.218.29"))) })
        assertEquals("178.208.70.181", ManagedEntryDns.resolve { _, _ -> throw IllegalStateException("No physical network") })
    }

    @Test fun freshAnswersAreReadAgainAtEachStartWithoutChangingTheTlsIdentity() {
        var requests = 0
        fun resolve(address: String) = ManagedEntryDns.resolve { _, query -> requests++; reply(query, listOf(a(ManagedEntryDns.PRIMARY_HOST, address))) }
        assertEquals("178.208.70.181", resolve("178.208.70.181"))
        assertEquals("8.8.4.4", resolve("8.8.4.4"))
        assertEquals(2, requests)
    }

    private fun name(name: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        for (part in name.split('.')) { bytes.write(part.length); bytes.write(part.toByteArray(Charsets.US_ASCII)) }
        bytes.write(0)
    }.toByteArray()

    private fun record(owner: String, type: Int, data: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            // The owned name uses a real DNS compression pointer to the question.
            out.write(if (owner == ManagedEntryDns.PRIMARY_HOST) byteArrayOf(0xc0.toByte(), 12) else name(owner))
            out.writeShort(type); out.writeShort(1); out.writeInt(3600); out.writeShort(data.size); out.write(data)
        }
    }.toByteArray()
    private fun a(owner: String, address: String) = record(owner, 1, address.split('.').map { it.toInt().toByte() }.toByteArray())
    private fun cname(owner: String, target: String) = record(owner, 5, name(target))
    private fun reply(query: ByteArray, records: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(query, 0, 2); out.writeShort(0x8180); out.writeShort(1); out.writeShort(records.size); out.writeShort(0); out.writeShort(0)
            out.write(query, 12, query.size - 12); records.forEach(out::write)
        }
    }.toByteArray()
}
