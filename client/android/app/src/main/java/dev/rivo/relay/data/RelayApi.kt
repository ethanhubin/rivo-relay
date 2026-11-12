package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.connection.NodeRouteInterceptor
import dev.rivo.relay.connection.gatewayTrust
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class ApiException(val status: Int, val code: String, override val message: String): IOException(message)
data class RelayEvent(val type: String, val payload: JSONObject, val cursor: String? = null)

// A large history response may keep making progress well beyond ten seconds.
// Bound stalled reads separately from the total transfer, including shared reads.
const val JSON_READ_TIMEOUT_MILLIS = 120_000L
const val JSON_READ_IDLE_TIMEOUT_MILLIS = 30_000L

/** Adapted from QiGuang HttpTransport: cancellable JSON and SSE, with Rivo envelopes and no write retry. */
class RelayApi(private val base: String, private val token: String = "", private val dns: Dns = TailnetDns(), private val routes: NodeRoutes? = null, private val nodeId: String = "") {
    private val compatibilityMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var compatibilityRead = false
    @Volatile private var compatibilityCheckedAt = 0L
    private val compatibilityMutable = kotlinx.coroutines.flow.MutableStateFlow<ApiCompatibility?>(null)
    val compatibilityState: kotlinx.coroutines.flow.StateFlow<ApiCompatibility?> = compatibilityMutable
    val compatibility: ApiCompatibility? get() = compatibilityMutable.value
    private val capabilityEpochMutable = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val capabilityEpoch: kotlinx.coroutines.flow.StateFlow<Long> = capabilityEpochMutable
    private var nodeContractFingerprint: String? = null
    @Volatile private var advertisedVersions: Set<Int>? = null
    val compatibilityMessage: String get() = when {
        advertisedVersions?.intersect(ApiCompatibility.CLIENT_VERSIONS)?.isEmpty() == true -> "App 与节点不兼容，请升级节点或使用支持该节点的 App"
        !compatibilityRead || compatibility == null -> "兼容性未知；保留现有连接，更新安装需重新检查"
        else -> "基础兼容（API v${compatibility?.selected()}）"
    }
    @Synchronized private fun acceptNode(info: JSONObject) {
        if (nodeId.isNotBlank() && info.optString("id") != nodeId)
            throw ApiException(409, "node_identity_mismatch", "节点身份不匹配")
        val fingerprint = "${info.optJSONObject("compatibility")}:${info.opt("releaseCode")}"
        if (nodeContractFingerprint != null && nodeContractFingerprint != fingerprint) capabilityEpochMutable.value++
        nodeContractFingerprint = fingerprint
        advertisedVersions = ApiCompatibility.versions(info.optJSONObject("compatibility"))
        compatibilityMutable.value = ApiCompatibility.parse(info.optJSONObject("compatibility"))
        compatibilityCheckedAt = System.nanoTime()
        compatibilityRead = true
    }
    private suspend fun ensureCompatibility() {
        if (nodeId.isBlank()) return // Unpaired/public API clients have no business identity.
        compatibilityMutex.lock()
        try {
            if (!compatibilityRead || System.nanoTime() - compatibilityCheckedAt > 15_000_000_000L)
                call("/v1/node", reconnectProbe = true)
        } finally { compatibilityMutex.unlock() }
        rejectIncompatible()
    }
    private fun rejectIncompatible() {
        if (advertisedVersions?.intersect(ApiCompatibility.CLIENT_VERSIONS)?.isEmpty() == true)
            throw ApiException(409, "api_incompatible", compatibilityMessage)
    }
    suspend fun supportsFeature(feature: String): Boolean {
        ensureCompatibility()
        // Missing legacy declaration preserves existing feature probes, never grants update eligibility.
        return compatibility?.supports(feature) ?: true
    }
    private suspend fun guard(path: String, body: JSONObject? = null) {
        ensureCompatibility()
        val input = body?.optJSONArray("input")
        val hasAttachment = input != null && (0 until input.length()).any { input.optJSONObject(it)?.optString("type") != "text" }
        if (hasAttachment) requireFeature("attachments")
        if (body?.optBoolean("requireGoalSupport") == true) requireFeature("session.goals")
        val feature = when {
            path.contains("/attachments") -> "attachments"
            path.startsWith("/v1/todos") -> "todos"
            path.startsWith("/v1/goals") || path.contains("/goal") -> "session.goals"
            path.startsWith("/v1/speech/access") -> "voice.direct"
            path.startsWith("/v1/speech/streams") -> "voice.stream"
            else -> null
        }
        if (feature != null) requireFeature(feature)
    }
    private fun requireFeature(feature: String) {
        if (compatibility?.supports(feature) == false)
            throw ApiException(409, "feature_unsupported", "此节点不支持该功能，请升级节点")
    }
    fun sitesWebProxy(): dev.rivo.relay.ui.sites.SitesWebProxy {
        val config = routes?.takeIf { it.relay.isNotBlank() }
            ?: throw IOException("此节点尚未配置 VPS 中转，请重新扫码配对")
        return dev.rivo.relay.ui.sites.SitesWebProxy(config, token)
    }
    private val gatewaySpeech by lazy {
        routes?.takeIf { it.relay.isNotBlank() }?.let {
            GatewaySpeechClient(it, dns, issueAccess = { call("/v1/speech/access", "POST") })
        }
    }
    suspend fun speechCapability(): JSONObject {
        if (!supportsFeature(if (gatewaySpeech != null) "voice.direct" else "voice.stream"))
            return JSONObject().put("speech", JSONObject().put("available", false).put("reason", "此节点不支持语音，请升级节点"))
        return gatewaySpeech?.capability() ?: call("/v1/media/capabilities")
    }
    suspend fun speechStart(context: String?): JSONObject {
        guard(if (gatewaySpeech != null) "/v1/speech/access" else "/v1/speech/streams")
        return gatewaySpeech?.start(context)
            ?: call("/v1/speech/streams", "POST", JSONObject().put("context", context.orEmpty().take(400)))
    }
    suspend fun speechFinish(id: String, cancel: Boolean = false): JSONObject = gatewaySpeech?.finish(id, cancel)
        ?: call("/v1/speech/streams/${encoded(id)}/${if (cancel) "cancel" else "finish"}", "POST")
    suspend fun speechChunk(id: String, pcm: ByteArray): JSONObject = gatewaySpeech?.chunk(id, pcm) ?: nodeSpeechChunk(id, pcm)
    private val connectionObserver = dev.rivo.relay.connection.ConnectionStatusObserver(routes, base)
    val connectionStatus = connectionObserver.status
    private val latencyProbe = dev.rivo.relay.connection.NetworkLatencyProbe(dns)
    suspend fun networkLatency(endpoint: String): Long = latencyProbe.measure(endpoint)
    @Volatile var recovery: ConnectionRecovery? = null
    internal val sentImages = SentImageCache()
    fun describeFailure(error: Throwable) = connectionFailure(error, when (routes?.mode) {
        dev.rivo.relay.connection.ConnectionMode.RELAY -> routes.relay
        dev.rivo.relay.connection.ConnectionMode.DIRECT -> routes.direct
        dev.rivo.relay.connection.ConnectionMode.AUTO -> "" // A failed automatic route has no confirmed endpoint.
        null -> base
    })
    private val routing by lazy {
        routes?.let { config ->
            val probe = OkHttpClient.Builder().dns(dns).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .gatewayTrust(config).build()
            NodeRouteInterceptor(base, config, probe, token, nodeId)
        }
    }
    private fun newClient() = OkHttpClient.Builder().dns(dns).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val probe = chain.request().tag(ReconnectProbe::class.java) != null
            if (!probe && recovery?.state?.value?.let { it != RecoveryState.CONNECTED } == true) throw ConnectionPausedException()
            try { chain.proceed(chain.request()) }
            catch (e: IOException) {
                if (!probe && !chain.call().isCanceled()) recovery?.failed(e)
                throw e
            }
        }
        .addInterceptor(connectionObserver)
        .apply { routes?.let { gatewayTrust(it) }; routing?.let { addInterceptor(it) } }.build()
    private val transportLock = Any()
    private val reads = mutableSetOf<Call>()
    @Volatile private var client = newClient()
    private fun readClient() = client.newBuilder().retryOnConnectionFailure(true)
        .readTimeout(JSON_READ_IDLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(JSON_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()
    private var reader = readClient()
    // Health checks must not queue behind large history bodies or reuse their sockets.
    private fun probeClient() = newClient().newBuilder().dispatcher(Dispatcher())
        .connectionPool(ConnectionPool()).callTimeout(12, TimeUnit.SECONDS).build()
    private var probes = probeClient()
    private fun catalogClient() = readClient().newBuilder().dispatcher(Dispatcher()).build()
    private var catalogReader = catalogClient()
    // Bulk bodies occupy callback threads until fully consumed. Reserve a separate
    // bounded dispatcher so transfers cannot queue chat reads or submissions.
    private fun transferClient() = client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
        .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).build()
    @Volatile private var transfers = transferClient()
    private fun mediaClient() = reader.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 }).build()
    @Volatile private var mediaReader = mediaClient()
    private fun backgroundClient() = reader.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 }).build()
    private var backgroundReader = backgroundClient()
    private val predictionClient = client.newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 }).callTimeout(45, TimeUnit.SECONDS).build()
    // SSE callbacks remain active for the lifetime of the stream. They must not
    // consume a JSON dispatcher slot, and missing server heartbeats must expire.
    private fun eventClient() = reader.newBuilder().dispatcher(Dispatcher())
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
    private var eventReader = eventClient()
    private fun forgetRead(call: Call) { synchronized(transportLock) { reads.remove(call) } }
    /** A lost route can leave HTTP/2 sockets alive but unusable. Never replay or cancel writes here. */
    suspend fun resetTransport() = withContext(Dispatchers.IO) {
        compatibilityRead = false
        capabilityEpochMutable.value++
        val (old, obsolete) = synchronized(transportLock) {
            val old = client
            probes.connectionPool.evictAll()
            probes = probeClient()
            client = newClient()
            reader = readClient()
            catalogReader = catalogClient()
            transfers = transferClient()
            mediaReader = mediaClient()
            backgroundReader = backgroundClient()
            eventReader = eventClient()
            (old to reads.toList()).also { reads.clear() }
        }
        obsolete.forEach { it.cancel() }
        old.connectionPool.evictAll()
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Request {
        if (path.substringBefore('?') !in setOf("/v1/node", "/v1/pair", "/health")) rejectIncompatible()
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (method != "GET") builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null, background: Boolean = false, reconnectProbe: Boolean = false, diagnosticRequestId: String? = null): JSONObject = withContext(Dispatchers.Default) {
        if (path.substringBefore('?') !in setOf("/v1/node", "/v1/pair", "/health")) guard(path, body)
        suspendCancellableCoroutine { continuation ->
        val optionalPrediction = path.endsWith("/prediction")
        val call = synchronized(transportLock) {
            (if (optionalPrediction) predictionClient else if (reconnectProbe) probes else if (method != "GET") client else if (background) backgroundReader else if (path.substringBefore('?') in setOf("/v1/projects", "/v1/threads")) catalogReader else reader).newCall(request(path, method, body).newBuilder().apply { if (reconnectProbe) tag(ReconnectProbe::class.java, ReconnectProbe()); diagnosticRequestId?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { header("X-Rivo-Request-Id", it) } }.build()).also { if (method == "GET" || optionalPrediction) reads.add(it) }
        }
        continuation.invokeOnCancellation { call.cancel(); forgetRead(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (path == "/v1/node") compatibilityRead = false; forgetRead(call); if (continuation.isActive && !optionalPrediction && !reconnectProbe && (!call.isCanceled() || e is InterruptedIOException)) recovery?.failed(e); if (continuation.isActive) continuation.resumeWithException(if (call.isCanceled() && e !is InterruptedIOException) CancellationException("Network route changed") else e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        val json = try { if (raw.isBlank()) JSONObject() else JSONObject(raw) } catch (_: Exception) { throw ApiException(response.code, "invalid_response", "节点返回了无法识别的响应") }
                        if (!response.isSuccessful) {
                            val error = json.optJSONObject("error")
                            throw ApiException(response.code, error?.string("code").orEmpty(), error?.string("message")?.ifBlank { "节点请求失败（${response.code}）" } ?: "节点请求失败（${response.code}）")
                        }
                        if (path == "/v1/node") acceptNode(json)
                        if (compatibility?.supports("session.goals") == false && json.has("goalSupported"))
                            json.put("goalSupported", false)
                        if (continuation.isActive) continuation.resume(json)
                    }
                } catch (e: Exception) { if (path == "/v1/node") compatibilityRead = false; if (continuation.isActive && !optionalPrediction && !reconnectProbe && (!call.isCanceled() || e is InterruptedIOException)) recovery?.failed(e); if (continuation.isActive) continuation.resumeWithException(if (call.isCanceled() && e !is InterruptedIOException) CancellationException("Network route changed") else e) }
                finally { forgetRead(call) }
            }
        })
    } }
    /** Stream through OkHttp; node credentials stay on this authenticated endpoint. */
    /** Paths belong to the selected Relay node, never the phone filesystem. */
    suspend fun readFile(path: String): JSONObject =
        call("/v1/files?path=" + java.net.URLEncoder.encode(path, "UTF-8"))

    suspend fun uploadFile(threadId: String, name: String, file: java.io.File): JSONObject =
        uploadAttachment("/v1/threads/${encoded(threadId)}/attachments", name, file)
    suspend fun uploadProjectFile(projectId: String, name: String, file: java.io.File): JSONObject =
        uploadAttachment("/v1/projects/${encoded(projectId)}/attachments", name, file)
    suspend fun uploadTodoFile(name: String, file: java.io.File): JSONObject =
        uploadAttachment("/v1/todos/attachments", name, file)
    private suspend fun uploadAttachment(path: String, name: String, file: java.io.File): JSONObject {
        guard(path)
        return suspendCancellableCoroutine { continuation ->
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val req = request("$path?name=${encoded(name)}")
            .newBuilder().post(body).build()
        val call = transfers.newCall(req)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful) throw IOException(json.optJSONObject("error")?.string("message") ?: "文件上传失败")
                    if (continuation.isActive) continuation.resume(json)
                } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
        }
    }
    private suspend fun <T> downloadRead(read: suspend () -> T): T {
        recovery?.awaitDownloadConnection()
        return try { read() } catch (paused: ConnectionPausedException) {
            // Foreground recovery can start between the preflight and dispatch.
            // This exception is thrown before sending; retry only this explicit read.
            recovery?.awaitDownloadConnection()
            read()
        }
    }

    suspend fun downloadFile(path: String, output: java.io.OutputStream, progress: (Long, Long) -> Unit) {
        downloadRead { downloadFileConnected(path, output, progress) }
    }

    private suspend fun downloadFileConnected(path: String, output: java.io.OutputStream, progress: (Long, Long) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val call = transfers
            .newCall(request("/v1/files/download?path=" + encoded(path)))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    val body = response.body ?: throw IOException("节点返回空文件")
                    if (!response.isSuccessful) {
                        val message = runCatching { JSONObject(body.string()).optJSONObject("error")?.string("message") }.getOrNull()
                        throw IOException(message ?: "文件下载失败")
                    }
                    val total = body.contentLength()
                    var copied = 0L
                    val buffer = ByteArray(65536)
                    body.byteStream().use { input ->
                        while (true) {
                            if (call.isCanceled()) throw IOException("下载已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > 1024L * 1024 * 1024) throw IOException("文件最大支持 1 GiB")
                            output.write(buffer, 0, count)
                            progress(copied, total)
                        }
                    }
                    if (total >= 0 && copied != total) throw IOException("下载不完整，请重试")
                    output.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    suspend fun cachedFile(cacheDirectory: java.io.File, path: String, progress: (Long, Long) -> Unit): java.io.File = downloadRead {
        ResumableDownload.get(java.io.File(cacheDirectory, "downloads"), "$base\u0000$token", path,
            transfers,
            request("/v1/files/download?path=" + encoded(path)), progress)
    }

    private var sharedImages: coil.ImageLoader? = null
    @Synchronized internal fun images(context: android.content.Context): coil.ImageLoader = sharedImages ?: coil.ImageLoader.Builder(context.applicationContext)
        .callFactory { okhttp3.Call.Factory { request -> mediaReader.newCall(request) } }
        .components { add(InlineImageFetcher.Factory()) }
        .memoryCache { coil.memory.MemoryCache.Builder(context.applicationContext).maxSizeBytes(32 * 1024 * 1024).build() }
        .build().also { sharedImages = it }
    internal fun mediaTransport(): OkHttpClient = mediaReader
    internal fun mediaRequest(source: String, thumbnail: Boolean = false): Request {
        val path = if (source.startsWith("/v1/media/")) {
            require(!source.contains("..") && !source.contains('\\'))
            if (thumbnail) source.replace(Regex("/content$"), "/thumbnail") else source
        } else "/v1/media/${if (thumbnail) "thumbnail" else "content"}?source=${encoded(source)}"
        return request(path)
    }
    suspend fun cachedMedia(cacheDirectory: java.io.File, source: String, progress: (Long, Long) -> Unit): java.io.File = downloadRead {
        ResumableDownload.get(java.io.File(cacheDirectory, "downloads"), "$base\u0000$token", "media:$source",
            transfers,
            mediaRequest(source), progress)
    }

    /** PCM chunks are writes: cancellation propagates and the transport never retries. */
    private suspend fun nodeSpeechChunk(streamId: String, pcm: ByteArray): JSONObject = suspendCancellableCoroutine { continuation ->
        val req = request("/v1/speech/streams/${encoded(streamId)}/chunks").newBuilder()
            .post(pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        val call = client.newCall(req)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful) throw ApiException(response.code,
                        json.optJSONObject("error")?.string("code").orEmpty(),
                        json.optJSONObject("error")?.string("message") ?: "语音上传失败")
                    if (continuation.isActive) continuation.resume(json)
                } } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    fun events(threadId: String, lastEventId: String? = null): Flow<RelayEvent> = callbackFlow {
        ensureCompatibility()
        val call = synchronized(transportLock) {
            val builder = request("/v1/events?threadId=" + encoded(threadId)).newBuilder().header("Accept", "text/event-stream")
            lastEventId?.takeIf { it.isNotBlank() && '\u0000' !in it && '\n' !in it && '\r' !in it }?.let { builder.header("Last-Event-ID", it) }
            eventReader.newCall(builder.build())
                .also { reads.add(it) }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { forgetRead(call); close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!response.isSuccessful) throw ApiException(response.code, "stream_failed", "实时连接已中断")
                        val source = response.body?.source() ?: throw IOException("实时响应为空")
                        trySend(RelayEvent("connected", JSONObject()))
                        var type = "native"
                        var cursor: String? = null
                        var data = StringBuilder()
                        while (!call.isCanceled() && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("id:") -> line.substringAfter(':').trimStart().takeUnless { '\u0000' in it }?.let { cursor = it }
                                line.startsWith("event:") -> type = line.substringAfter(':').trim()
                                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').trimStart()) }
                                line.isEmpty() -> { if (data.isNotEmpty()) { if (trySend(RelayEvent(type, JSONObject(data.toString()), cursor)).isFailure) throw IOException("实时缓冲已满，请刷新快照") }; type = "native"; cursor = null; data = StringBuilder() }
                            }
                        }
                    }
                    close()
                } catch (e: Exception) { close(e) }
                finally { forgetRead(call) }
            }
        })
        awaitClose { call.cancel(); forgetRead(call) }
    }
}

fun encoded(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)
