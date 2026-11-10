package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.connection.gatewayTrust
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Audio goes straight to the enrolled VPS; only short-lived grant issuance uses the node. */
internal class GatewaySpeechClient(
    routes: NodeRoutes,
    dns: Dns,
    private val issueAccess: suspend () -> JSONObject,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val endpoint = routes.validated().relay.trimEnd('/') + "/v1/speech-direct"
    private val client = OkHttpClient.Builder().dns(dns).gatewayTrust(routes)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private data class Access(val token: String, val expiresAt: Long)
    private val lock = Mutex()
    @Volatile private var cached: Access? = null
    private val streams = ConcurrentHashMap<String, Access>()

    private suspend fun access(): Access = lock.withLock {
        cached?.takeIf { it.expiresAt - nowSeconds() >= 150 } ?: issueAccess().let {
            val token = it.optString("token")
            val expires = it.optLong("expiresAt")
            require(token.startsWith("speech1.") && token.length <= 2048 && expires - nowSeconds() in 150..185) {
                "节点返回了无效的语音授权"
            }
            Access(token, expires).also { value -> cached = value }
        }
    }
    suspend fun capability(): JSONObject = request("/capabilities", access())
    suspend fun start(context: String?): JSONObject {
        val grant = access()
        val result = request("/v1/speech/streams", grant,
            JSONObject().put("context", context.orEmpty().take(400)).toString().toRequestBody(JSON))
        val id = result.optString("stream_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "中转返回了无效的语音会话" }
        streams[id] = grant
        return result
    }
    suspend fun chunk(id: String, pcm: ByteArray): JSONObject {
        val grant = streams[id] ?: throw IOException("语音会话已失效，请重新录音")
        try { return request("/v1/speech/streams/${encoded(id)}/chunks", grant, pcm.toRequestBody(PCM)) }
        catch (error: Exception) { streams.remove(id); throw error }
    }
    suspend fun finish(id: String, cancel: Boolean = false): JSONObject {
        val grant = streams.remove(id) ?: throw IOException("语音会话已失效，请重新录音")
        return request("/v1/speech/streams/${encoded(id)}/${if (cancel) "cancel" else "finish"}", grant,
            "{}".toRequestBody(JSON))
    }
    private suspend fun request(path: String, grant: Access, body: RequestBody? = null): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(endpoint + path).header("Authorization", "Bearer ${grant.token}")
            if (body != null) builder.post(body)
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        val source = response.body?.source() ?: throw IOException("语音响应为空")
                        if (source.request(1_048_577)) throw IOException("语音响应过大")
                        val json = try { JSONObject(source.readUtf8()) }
                            catch (_: Exception) { throw IOException("中转返回了无效的语音响应") }
                        if (response.code == 401) cached = null
                        if (!response.isSuccessful) throw ApiException(response.code,
                            json.optJSONObject("error")?.optString("code").orEmpty(),
                            json.optJSONObject("error")?.optString("message") ?: "语音请求失败")
                        if (continuation.isActive) continuation.resume(json)
                    } } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    companion object {
        private val JSON = "application/json".toMediaType()
        private val PCM = "application/octet-stream".toMediaType()
    }
}
