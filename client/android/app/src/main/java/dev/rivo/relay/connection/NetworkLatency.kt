package dev.rivo.relay.connection

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** TCP connect RTT to the selected endpoint, not a business request or end-to-end relay RTT. */
class NetworkLatencyProbe(
    private val dns: Dns,
    private val socketFactory: () -> Socket = { Socket(Proxy.NO_PROXY) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun measure(endpoint: String): Long = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrl()
        // Resolve outside the timer; DNS, TLS, route selection and HTTP handlers
        // must not be presented as network round-trip time.
        val address = dns.lookup(url.host).firstOrNull() ?: throw IOException("无法解析节点地址")
        val target = InetSocketAddress(address, url.port)
        val samples = List(3) {
            coroutineContext.ensureActive()
            suspendCancellableCoroutine<Long> { continuation ->
                val socket = socketFactory()
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                try {
                    socket.use {
                        val started = nanoTime()
                        socket.connect(target, 2000)
                        val elapsed = (nanoTime() - started) / 1_000_000
                        continuation.resume(elapsed.coerceAtLeast(1))
                    }
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
        samples.sorted()[1]
    }
}
