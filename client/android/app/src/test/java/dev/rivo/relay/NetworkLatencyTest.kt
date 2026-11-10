package dev.rivo.relay

import dev.rivo.relay.connection.NetworkLatencyProbe
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test

class NetworkLatencyTest {
    @Test fun excludesDnsAndUsesMedianOfIndependentTcpConnections() = runBlocking {
        var clock = 0L
        var closed = 0
        val durations = ArrayDeque(listOf(20L, 900L, 30L))
        val probe = NetworkLatencyProbe(
            dns = object : Dns { override fun lookup(hostname: String): List<InetAddress> { clock += 5_000_000_000; return listOf(InetAddress.getLoopbackAddress()) } },
            socketFactory = {
                object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        assertEquals(2000, timeout)
                        clock += durations.removeFirst() * 1_000_000
                    }
                    override fun close() { closed++ }
                }
            },
            nanoTime = { clock },
        )
        assertEquals(30L, probe.measure("https://node.test:80/relay/slot"))
        assertEquals(3, closed)
    }

    @Test fun tcpPingDoesNotWaitForHttpResponseOrSendCredentials() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = async(Dispatchers.IO) {
                repeat(3) {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
            }
            val result = withTimeout(3000) {
                NetworkLatencyProbe(Dns.SYSTEM).measure("http://127.0.0.1:${server.localPort}/relay/private")
            }
            assertTrue(result >= 1)
            accepted.await()
        }
    }

    @Test fun cancellationClosesPendingSocket() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val probe = NetworkLatencyProbe(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }, {
            object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    entered.countDown()
                    check(closed.await(3, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun close() { closed.countDown() }
            }
        })
        val job = launch { probe.measure("http://127.0.0.1") }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertEquals(0, closed.count)
    }

    @Test fun connectionFailureIsNotReportedAsZeroLatency() = runBlocking {
        val probe = NetworkLatencyProbe(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }, {
            object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) { throw IOException("unreachable") }
            }
        })
        try {
            probe.measure("http://127.0.0.1")
            fail("Expected failed probe")
        } catch (_: IOException) { }
    }
}
