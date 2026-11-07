package dev.rivo.relay.data

import java.io.IOException
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

/** Adapted from QiGuang HttpTransport: cancellable JSON and SSE, with Rivo envelopes and no write retry. */
class RelayApi(private val base: String, private val token: String = "", private val dns: Dns = TailnetDns()) {
    fun describeFailure(error: Throwable) = connectionFailure(error, base)
    private fun newClient() = OkHttpClient.Builder().dns(dns).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private val transportLock = Any()
    private val reads = mutableSetOf<Call>()
    @Volatile private var client = newClient()
    private var reader = client.newBuilder().retryOnConnectionFailure(true).build()
    private fun forgetRead(call: Call) { synchronized(transportLock) { reads.remove(call) } }
    /** A lost route can leave HTTP/2 sockets alive but unusable. Never replay or cancel writes here. */
    suspend fun resetTransport() = withContext(Dispatchers.IO) {
        val (old, obsolete) = synchronized(transportLock) {
            val old = client
            client = newClient()
            reader = client.newBuilder().retryOnConnectionFailure(true).build()
            (old to reads.toList()).also { reads.clear() }
        }
        obsolete.forEach { it.cancel() }
        old.connectionPool.evictAll()
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Request {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (method != "GET") builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = synchronized(transportLock) {
            (if (method == "GET") reader else client).newCall(request(path, method, body)).also { if (method == "GET") reads.add(it) }
        }
        continuation.invokeOnCancellation { call.cancel(); forgetRead(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { forgetRead(call); if (continuation.isActive) continuation.resumeWithException(if (call.isCanceled()) CancellationException("Network route changed") else e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        val json = try { if (raw.isBlank()) JSONObject() else JSONObject(raw) } catch (_: Exception) { throw ApiException(response.code, "invalid_response", "节点返回了无法识别的响应") }
                        if (!response.isSuccessful) {
                            val error = json.optJSONObject("error")
                            throw ApiException(response.code, error?.string("code").orEmpty(), error?.string("message")?.ifBlank { "节点请求失败（${response.code}）" } ?: "节点请求失败（${response.code}）")
                        }
                        if (continuation.isActive) continuation.resume(json)
                    }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (call.isCanceled()) CancellationException("Network route changed") else e) }
                finally { forgetRead(call) }
            }
        })
    }
    /** Stream through OkHttp; node credentials stay on this authenticated endpoint. */
    suspend fun uploadFile(threadId: String, name: String, file: java.io.File): JSONObject =
        uploadAttachment("/v1/threads/${encoded(threadId)}/attachments", name, file)
    suspend fun uploadProjectFile(projectId: String, name: String, file: java.io.File): JSONObject =
        uploadAttachment("/v1/projects/${encoded(projectId)}/attachments", name, file)
    private suspend fun uploadAttachment(path: String, name: String, file: java.io.File): JSONObject = suspendCancellableCoroutine { continuation ->
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val req = request("$path?name=${encoded(name)}")
            .newBuilder().post(body).build()
        val call = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build().newCall(req)
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

    suspend fun downloadFile(path: String, output: java.io.OutputStream, progress: (Long, Long) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        val call = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
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

    suspend fun cachedFile(cacheDirectory: java.io.File, path: String, progress: (Long, Long) -> Unit): java.io.File =
        ResumableDownload.get(java.io.File(cacheDirectory, "downloads"), "$base\u0000$token", path,
            client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS).build(),
            request("/v1/files/download?path=" + encoded(path)), progress)

    internal fun mediaTransport(): OkHttpClient = reader
    internal fun mediaRequest(source: String, thumbnail: Boolean = false): Request {
        val path = if (source.startsWith("/v1/media/")) {
            require(!source.contains("..") && !source.contains('\\'))
            if (thumbnail) source.replace(Regex("/content$"), "/thumbnail") else source
        } else "/v1/media/${if (thumbnail) "thumbnail" else "content"}?source=${encoded(source)}"
        return request(path)
    }
    suspend fun cachedMedia(cacheDirectory: java.io.File, source: String, progress: (Long, Long) -> Unit): java.io.File =
        ResumableDownload.get(java.io.File(cacheDirectory, "downloads"), "$base\u0000$token", "media:$source",
            reader.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(180, TimeUnit.SECONDS).build(),
            mediaRequest(source), progress)

    /** PCM chunks are writes: cancellation propagates and the transport never retries. */
    suspend fun speechChunk(streamId: String, pcm: ByteArray): JSONObject = suspendCancellableCoroutine { continuation ->
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
        val call = synchronized(transportLock) {
            val builder = request("/v1/events?threadId=" + encoded(threadId)).newBuilder().header("Accept", "text/event-stream")
            lastEventId?.takeIf { it.isNotBlank() && '\u0000' !in it && '\n' !in it && '\r' !in it }?.let { builder.header("Last-Event-ID", it) }
            reader.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
                .newCall(builder.build())
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
