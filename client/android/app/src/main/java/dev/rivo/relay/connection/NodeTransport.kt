package dev.rivo.relay.connection

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

enum class ConnectionMode(val label: String) { AUTO("自动"), DIRECT("仅直连"), RELAY("仅 VPS") }

data class NodeRoutes(val direct: String = "", val relay: String = "", val pin: String = "", val mode: ConnectionMode = ConnectionMode.AUTO) {
    fun json() = JSONObject().put("direct", direct).put("relay", relay).put("pin", pin).put("mode", mode.name)
    fun validated(): NodeRoutes {
        require(direct.isNotBlank() || relay.isNotBlank()) { "节点缺少连接地址" }
        if (direct.isNotBlank()) {
            require(java.net.URI(direct).fragment == null) { "直连地址不能包含配对信息" }
            require(PairingParser.parse(direct, "validate", dev.rivo.relay.BuildConfig.DEBUG).baseUrl == direct) { "直连地址格式无效" }
        }
        if (relay.isNotBlank()) {
            val url = relay.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(url.encodedPath.matches(Regex("/relay/[a-f0-9]{32}"))) { "中转地址格式无效" }
            require(pin.matches(Regex("[a-f0-9]{64}"))) { "中转证书指纹无效" }
        } else require(pin.isEmpty())
        require(mode != ConnectionMode.DIRECT || direct.isNotBlank()) { "此节点没有直连地址" }
        require(mode != ConnectionMode.RELAY || relay.isNotBlank()) { "此节点没有 VPS 地址" }
        return this
    }
    companion object {
        fun from(json: JSONObject): NodeRoutes = NodeRoutes(json.optString("direct"), json.optString("relay"), json.optString("pin"),
            ConnectionMode.valueOf(json.optString("mode", "AUTO"))).validated()
    }
}

/** Only stores a route choice, never credentials. Configured by the encrypted credential repository. */
object RouteMemory {
    var read: (String) -> String? = { null }
    var write: (String, String) -> Unit = { _, _ -> }
}

/** Exact certificate pin for the configured gateway; system trust remains in force elsewhere. */
fun OkHttpClient.Builder.gatewayTrust(routes: NodeRoutes): OkHttpClient.Builder {
    if (routes.relay.isBlank()) return this
    val gatewayHost = routes.relay.toHttpUrl().host
    fun pinned(cert: X509Certificate): Boolean = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it) } == routes.pin
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
    val system = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers() = system.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isNotEmpty() && pinned(chain[0])) chain[0].checkValidity()
            else system.checkServerTrusted(chain, authType)
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
    val defaultVerifier = OkHttpClient().hostnameVerifier
    return sslSocketFactory(context.socketFactory, trust).hostnameVerifier { host, session ->
        if (host == gatewayHost) runCatching { pinned(session.peerCertificates[0] as X509Certificate) }.getOrDefault(false)
        else defaultVerifier.verify(host, session)
    }
}

class NodeRouteInterceptor(
    private val base: String,
    private val routes: NodeRoutes,
    private val probe: OkHttpClient,
    private val token: String = "",
    private val nodeId: String = "",
) : Interceptor {
    private val memoryKey = "route:" + (nodeId.ifBlank { base })
    private val lock = Any()
    @Volatile private var selected: String? = null
    @Volatile private var verifiedAt = 0L
    private fun candidates(): List<String> {
        val available = when (routes.mode) {
            ConnectionMode.AUTO -> listOf(routes.direct, routes.relay)
            ConnectionMode.DIRECT -> listOf(routes.direct)
            ConnectionMode.RELAY -> listOf(routes.relay)
        }.filter { it.isNotBlank() }
        val recent = selected ?: RouteMemory.read(memoryKey)
        return if (recent in available) listOf(recent!!) + available.filter { it != recent } else available
    }
    private fun choose(exclude: String? = null): String = synchronized(lock) {
        if (exclude == null && selected != null && System.nanoTime() - verifiedAt < 15_000_000_000L) return@synchronized selected!!
        var failure: IOException? = null
        for (endpoint in candidates().filter { it != exclude }) {
            try {
                val timeout = if (endpoint == routes.direct && routes.mode == ConnectionMode.AUTO) 1800L else 6000L
                val request = Request.Builder().url(endpoint + if (token.isBlank()) "/health" else "/v1/node")
                if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
                probe.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).callTimeout(timeout, TimeUnit.MILLISECONDS)
                    .build().newCall(request.build()).execute().use { response ->
                        if (response.code == 401) { // Surface revocation on the real request; do not hide it with failover.
                            selected = endpoint; verifiedAt = System.nanoTime(); return@synchronized endpoint
                        }
                        if (!response.isSuccessful) throw IOException("节点暂不可用（${response.code}）")
                        if (nodeId.isNotBlank() && token.isNotBlank()) {
                            val source = response.body?.source() ?: throw IOException("节点身份响应为空")
                            if (source.request(65537)) throw IOException("节点身份响应过大")
                            if (runCatching { JSONObject(source.readUtf8()).optString("id") }.getOrNull() != nodeId) throw IOException("节点身份不匹配")
                        }
                    }
                selected = endpoint; verifiedAt = System.nanoTime(); RouteMemory.write(memoryKey, endpoint)
                return@synchronized endpoint
            } catch (e: IOException) { failure = e }
        }
        verifiedAt = 0L
        throw failure ?: IOException("没有可用的节点访问路径")
    }
    private fun routed(request: Request, endpoint: String): Request {
        val original = base.toHttpUrl()
        val prefix = original.encodedPath.trimEnd('/')
        require(request.url.host == original.host && request.url.port == original.port && request.url.encodedPath.startsWith("$prefix/"))
        val suffix = request.url.encodedPath.removePrefix(prefix) + (request.url.encodedQuery?.let { "?$it" } ?: "")
        return request.newBuilder().url(endpoint.trimEnd('/') + suffix).build()
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val endpoint = choose()
        try {
            val response = chain.proceed(routed(request, endpoint))
            if (response.code !in listOf(502, 503, 504)) return response
            verifiedAt = 0L
            if (request.method != "GET" || routes.mode != ConnectionMode.AUTO) return response
            val alternate = try { choose(endpoint) } catch (_: IOException) { return response }
            response.close()
            return chain.proceed(routed(request, alternate))
        } catch (error: IOException) {
            // Navigation and subscription replacement cancel healthy requests too.
            // Preserve the verified route instead of making other reads wait for a probe.
            if (chain.call().isCanceled()) throw error
            verifiedAt = 0L
            if (request.method != "GET" || routes.mode != ConnectionMode.AUTO) throw error
            val alternate = try { choose(endpoint) } catch (_: IOException) { throw error }
            return chain.proceed(routed(request, alternate))
        }
    }
}

/** Domain-separated, one-use pairing proof; the existing bearer never crosses an unverified new route. */
fun routeProof(token: String, direction: String, code: String, nodeId: String, binding: String = ""): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(digest, "HmacSHA256")) }
    return mac.doFinal((direction + "\u0000" + code + "\u0000" + nodeId + "\u0000" + binding).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun NodeRoutes.binding(): String = MessageDigest.getInstance("SHA-256")
    .digest(listOf(direct, relay, pin).joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
