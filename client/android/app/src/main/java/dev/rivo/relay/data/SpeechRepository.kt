package dev.rivo.relay.data

import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class SpeechCapability(val available: Boolean, val reason: String? = null)

internal interface SpeechTransport {
    suspend fun start(context: String?): String
    suspend fun chunk(streamId: String, pcm: ByteArray): String
    suspend fun finish(streamId: String): String
    suspend fun cancel(streamId: String)
}

internal class SpeechRepository(private val api: RelayApi) : SpeechTransport {
    suspend fun capability(): SpeechCapability = capabilityLock.withLock {
        capabilities[api] ?: withTimeout(8_000) {
            val speech = api.call("/v1/media/capabilities").optJSONObject("speech")
            val compatible = speech?.optBoolean("streaming") == true &&
                speech.optInt("sampleRate") == 16_000 && speech.string("encoding") == "pcm_s16le"
            SpeechCapability(compatible, if (compatible) null else if (speech?.string("state") == "unconfigured")
                "此节点尚未配置语音识别服务" else "此节点暂不支持实时语音输入")
        }.also { capabilities[api] = it }
    }
    override suspend fun start(context: String?): String = api.call("/v1/speech/streams", "POST",
        JSONObject().put("context", context.orEmpty().take(400))).string("stream_id").also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "节点返回了无效的语音会话" }
    }
    override suspend fun chunk(streamId: String, pcm: ByteArray): String = transcript(api.speechChunk(streamId, pcm))
    override suspend fun finish(streamId: String): String = transcript(api.call("/v1/speech/streams/${encoded(streamId)}/finish", "POST"))
    override suspend fun cancel(streamId: String) { api.call("/v1/speech/streams/${encoded(streamId)}/cancel", "POST") }
    private fun transcript(response: JSONObject): String {
        require(response.opt("text") is String) { "节点返回了无效的语音转写" }
        return response.string("text").also { require(it.length <= 100_000) { "语音转写内容过长" } }
    }
    companion object {
        private val capabilities = WeakHashMap<RelayApi, SpeechCapability>()
        private val capabilityLock = Mutex()
    }
}
