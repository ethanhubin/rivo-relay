package dev.rivo.relay.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
data class RelayEvent(val type: String, val payload: JSONObject)

/** Adapted from QiGuang HttpTransport: cancellable JSON and SSE, with Rivo envelopes and no write retry. */
class RelayApi(private val base: String, private val token: String = "") {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Request {
        val builder = Request.Builder().url(base.trimEnd('/') + path)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (method != "GET") builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        return builder.build()
    }
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(path, method, body))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
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
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    /** Stream through OkHttp; node credentials stay on this authenticated endpoint. */
    suspend fun uploadFile(threadId: String, name: String, file: java.io.File): JSONObject = suspendCancellableCoroutine { continuation ->
        val body = file.asRequestBody("application/octet-stream".toMediaType())
        val req = request("/v1/threads/${encoded(threadId)}/attachments?name=${encoded(name)}")
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

    fun events(threadId: String): Flow<RelayEvent> = callbackFlow {
        val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val call = streamClient.newCall(request("/v1/events?threadId=" + encoded(threadId)).newBuilder().header("Accept", "text/event-stream").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled()) close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!response.isSuccessful) throw ApiException(response.code, "stream_failed", "实时连接已中断")
                        val source = response.body?.source() ?: throw IOException("实时响应为空")
                        trySend(RelayEvent("connected", JSONObject()))
                        var type = "native"
                        var data = StringBuilder()
                        while (!call.isCanceled() && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> type = line.substringAfter(':').trim()
                                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').trimStart()) }
                                line.isEmpty() -> { if (data.isNotEmpty()) { if (trySend(RelayEvent(type, JSONObject(data.toString()))).isFailure) throw IOException("实时缓冲已满，请刷新快照") }; type = "native"; data = StringBuilder() }
                            }
                        }
                    }
                    close()
                } catch (e: Exception) { if (!call.isCanceled()) close(e) }
            }
        })
        awaitClose { call.cancel() }
    }
}

fun encoded(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONObject.string(key: String): String = if (isNull(key)) "" else optString(key)
