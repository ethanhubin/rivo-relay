package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeEndpoints
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public update transport: no pairing token, redirects or implicit GitHub fallback. */
class NodeAppUpdates(
    private val base: String = NodeEndpoints.DIRECT,
    private val client: OkHttpClient = OkHttpClient.Builder().dns(TailnetDns()).followRedirects(false)
        .followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build(),
) {
    suspend fun check(currentVersion: String): AppRelease? {
        requireNotNull(versionParts(currentVersion))
        val manifest = get("${base.trimEnd('/')}/v1/app-updates/latest") { response ->
            val source = response.body?.source() ?: throw IOException("更新信息为空")
            if (source.request(65537)) throw IOException("更新信息过大")
            JSONObject(source.readUtf8())
        }
        val version = manifest.getString("version")
        val digest = manifest.getString("sha256")
        val size = manifest.getLong("size")
        val code = manifest.getInt("versionCode")
        if (versionParts(version) == null || !digest.matches(Regex("[a-f0-9]{64}")) || size !in 1..268435456L || code <= 0)
            throw IOException("节点更新信息无效")
        val path = "/v1/app-updates/apks/$digest.apk"
        if (manifest.getString("apkPath") != path) throw IOException("节点更新地址无效")
        return AppRelease(version, manifest.optString("notes").take(16000), "${base.trimEnd('/')}$path",
            base, true, digest, size).takeIf { compareVersions(version, currentVersion) > 0 }
    }

    suspend fun download(release: AppRelease, directory: File): File {
        val expected = "${base.trimEnd('/')}/v1/app-updates/apks/${release.sha256}.apk"
        if (release.apkUrl != expected || !release.sha256.matches(Regex("[a-f0-9]{64}")) || release.size !in 1..268435456L)
            throw IOException("安装包地址无效")
        directory.mkdirs()
        val temporary = File.createTempFile("rivo-update-", ".part", directory)
        try {
            return get(expected) { response ->
                val body = response.body ?: throw IOException("安装包为空")
                if (body.contentLength() != release.size) throw IOException("安装包长度不匹配")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > release.size) throw IOException("安装包超出声明长度")
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != release.size || hash != release.sha256) throw IOException("安装包校验失败，请重新下载")
                val target = File(directory, "rivo-relay-${release.sha256}.apk")
                if (!temporary.renameTo(target)) throw IOException("无法保存安装包")
                target
            }
        } finally { temporary.delete() }
    }

    private suspend fun <T> get(url: String, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val parsed = url.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        val call = client.newCall(Request.Builder().url(parsed).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("无法连接更新节点，请检查 Tailscale 网络后重试", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException(if (it.code == 404) "节点尚未提供更新，请稍后重试" else "节点更新请求失败（${it.code}）")
                        read(it)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
