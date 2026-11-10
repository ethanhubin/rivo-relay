package dev.rivo.relay.ui.sites

import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.connection.gatewayTrust
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback CONNECT adapter. Website TLS stays between WebView and the original host. */
class SitesWebProxy(routes: NodeRoutes, private val token: String) : Closeable {
    internal val receivedBytes = java.util.concurrent.atomic.AtomicLong()
    private val endpoint = routes.validated().relay.toHttpUrl()
    private val client = OkHttpClient.Builder().gatewayTrust(routes).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val tunnels = ConcurrentHashMap.newKeySet<WebSocket>()
    private val capacity = Semaphore(24)
    @Volatile private var closed = false
    val port: Int get() = server.localPort
    init {
        workers.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                if (!capacity.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                workers.execute { try { serve(socket) } catch (_: Exception) { } finally {
                    sockets.remove(socket); runCatching { socket.close() }; capacity.release()
                } }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n") && head.length < 8192) {
            val byte = input.read(); if (byte < 0) return
            head.append(byte.toChar())
        }
        val host = connectHost(head.toString())
        if (host == null) { output.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val ready = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val request = Request.Builder().url(endpoint.newBuilder().addPathSegments("v1/sites/tunnel")
            .addQueryParameter("host", host).build()).header("Authorization", "Bearer $token").build()
        val tunnel = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush()
                    opened.set(true)
                } catch (_: IOException) { failed.set(true); webSocket.cancel() }
                ready.countDown()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { output.write(bytes.toByteArray()); output.flush(); receivedBytes.addAndGet(bytes.size.toLong()) }
                catch (_: IOException) { webSocket.cancel(); runCatching { socket.close() } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.cancel(); runCatching { socket.close() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failed.set(true); ready.countDown()
                if (opened.get()) runCatching { socket.close() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null); ready.countDown(); runCatching { socket.close() }
            }
        })
        tunnels.add(tunnel)
        try {
            if (!ready.await(25, TimeUnit.SECONDS) || !opened.get() || failed.get()) {
                if (!opened.get()) output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            // A response may keep downloading without further browser writes.
            // The gateway bounds tunnel lifetime; the header timeout must not cut it off.
            socket.soTimeout = 0
            val buffer = ByteArray(65536)
            while (!closed && !socket.isClosed) {
                val count = input.read(buffer); if (count < 0) break
                while (tunnel.queueSize() > 1024 * 1024 && !closed && !failed.get()) Thread.sleep(10)
                if (!tunnel.send(buffer.toByteString(0, count))) break
            }
        } finally { tunnels.remove(tunnel); tunnel.cancel() }
    }
    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        tunnels.forEach { it.cancel() }
        workers.shutdownNow(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }
    companion object {
        internal fun connectHost(headers: String): String? {
            if (!headers.endsWith("\r\n\r\n")) return null
            return Regex("CONNECT ([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):443 HTTP/1\\.[01]")
                .matchEntire(headers.substringBefore("\r\n"))?.groupValues?.get(1)
        }
    }
}
