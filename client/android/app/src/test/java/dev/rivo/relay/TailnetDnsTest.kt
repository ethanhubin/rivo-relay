package dev.rivo.relay

import dev.rivo.relay.data.*
import okhttp3.Dns
import java.net.*
import org.junit.Assert.*
import org.junit.Test

class TailnetDnsTest {
    private val host = "node.tailtest.ts.net"
    private val answer = listOf(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1)))
    private fun failing() = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname) }
    @Test fun systemFailureUsesMagicAndBoundedHostSpecificCache() {
        var time = 0L
        var available = true
        var calls = 0
        val magic = object : Dns { override fun lookup(hostname: String): List<InetAddress> { calls++; if (!available) throw UnknownHostException(hostname); return answer } }
        val dns = TailnetDns(failing(), magic) { time }
        assertEquals(answer, dns.lookup(host))
        available = false
        assertEquals(answer, dns.lookup(host))
        assertThrows(UnknownHostException::class.java) { dns.lookup("other.tailtest.ts.net") }
        time = 600_000_000_001L
        assertThrows(UnknownHostException::class.java) { dns.lookup(host) }
        val before = calls
        assertThrows(UnknownHostException::class.java) { dns.lookup("example.com") }
        assertEquals(before, calls)
    }
    @Test fun successfulSystemAnswerIsPreferredAndRemembered() {
        var available = true
        val system = object : Dns { override fun lookup(hostname: String): List<InetAddress> { if (!available) throw UnknownHostException(hostname); return answer } }
        val dns = TailnetDns(system, failing())
        assertEquals(answer, dns.lookup(host))
        available = false
        assertEquals(answer, dns.lookup(host))
    }
    private fun response(query: ByteArray): ByteArray = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 100, 64, 0, 1)
    @Test fun validatesReplyIdentityQuestionAndBounds() {
        val query = MagicDns.query(host, 42, 1)
        val packet = response(query)
        assertEquals(answer, MagicDns.parse(packet, query, host, 42, 1))
        for (bad in listOf(packet.copyOf().also { it[1] = 43 }, packet.copyOf().also { it[13] = 120 }, packet.copyOf().also { it[2] = 0x83.toByte() }, packet.copyOf(packet.size - 1), packet.copyOf().also { it[query.size + 1] = query.size.toByte() })) {
            assertThrows(IllegalArgumentException::class.java) { MagicDns.parse(bad, query, host, 42, 1) }
        }
    }
    @Test fun udpResolverReceivesQueryAndReturnsValidatedAddress() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val worker = Thread {
                val packet = DatagramPacket(ByteArray(512), 512)
                server.receive(packet)
                val reply = response(packet.data.copyOf(packet.length))
                server.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            }.apply { start() }
            assertEquals(answer, MagicDns(InetSocketAddress(server.localAddress, server.localPort)).lookup(host))
            worker.join(3000)
            assertFalse(worker.isAlive)
        }
    }
}
