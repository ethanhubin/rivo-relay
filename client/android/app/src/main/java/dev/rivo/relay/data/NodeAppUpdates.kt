package dev.rivo.relay.data

import dev.rivo.relay.connection.NodeEndpoints
import dev.rivo.relay.connection.NodeRoutes
import dev.rivo.relay.connection.NodeRouteInterceptor
import dev.rivo.relay.connection.gatewayTrust
import dev.rivo.relay.connection.CredentialStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class UpdateTransportException(cause: IOException) : IOException("更新下载中断，请检查网络后重试", cause)

/** Public update transport: no pairing token, redirects or implicit GitHub fallback. */
class NodeAppUpdates(
    private val base: String = NodeEndpoints.DIRECT,
    private val client: OkHttpClient = OkHttpClient.Builder().dns(TailnetDns()).followRedirects(false)
        .followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build(),
) {
    companion object {
        fun configured(context: android.content.Context): NodeAppUpdates {
            val routes = CredentialStore(context).read("update-routes")?.let { runCatching { NodeRoutes.from(JSONObject(it)) }.getOrNull() }
                ?: return NodeAppUpdates()
            val base = routes.direct.ifBlank { routes.relay }
            val probe = OkHttpClient.Builder().dns(TailnetDns()).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).gatewayTrust(routes).build()
            val client = probe.newBuilder().addInterceptor(NodeRouteInterceptor(base, routes, probe))
                .readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
            return NodeAppUpdates(base, client)
        }
    }
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

    suspend fun download(release: AppRelease, directory: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val expected = "${base.trimEnd('/')}/v1/app-updates/apks/${release.sha256}.apk"
        if (release.apkUrl != expected || !release.sha256.matches(Regex("[a-f0-9]{64}")) || release.size !in 1..268435456L)
            throw IOException("安装包地址无效")
        directory.mkdirs()
        val temporary = File.createTempFile("rivo-update-", ".part", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var lastReport = System.nanoTime()
        progress(0L, release.size)
        try {
            repeat(3) { attempt ->
                try {
                    get(expected, total.takeIf { it > 0 }) { response ->
                        val body = response.body ?: throw IOException("安装包为空")
                        if (total > 0 && response.code == 200) {
                            total = 0L
                            digest.reset()
                            progress(0L, release.size)
                        } else if (total > 0 && (response.code != 206 ||
                                response.header("Content-Range") != "bytes $total-${release.size - 1}/${release.size}")) {
                            throw IOException("安装包续传响应无效")
                        } else if (total == 0L && response.code != 200) {
                            throw IOException("安装包响应无效")
                        }
                        if (body.contentLength() != release.size - total) throw IOException("安装包长度不匹配")
                        body.byteStream().use { input -> java.io.FileOutputStream(temporary, total > 0).use { output ->
                            val buffer = ByteArray(65536)
                            while (total < release.size) {
                                val count = try { input.read(buffer) } catch (e: IOException) { throw UpdateTransportException(e) }
                                if (count < 0) throw UpdateTransportException(IOException("响应提前结束"))
                                total += count
                                if (total > release.size) throw IOException("安装包超出声明长度")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val now = System.nanoTime()
                                if (total == release.size || now - lastReport >= 100_000_000L) {
                                    progress(total, release.size)
                                    lastReport = now
                                }
                            }
                        } }
                    }
                } catch (e: UpdateTransportException) {
                    if (attempt == 2) throw e
                    delay(500L * (attempt + 1))
                    return@repeat
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != release.size || hash != release.sha256) throw IOException("安装包校验失败，请重新下载")
                val target = File(directory, "rivo-relay-${release.sha256}.apk")
                if (!temporary.renameTo(target)) throw IOException("无法保存安装包")
                return target
            }
            throw IOException("更新下载中断，请检查网络后重试")
        } finally { temporary.delete() }
    }

    private suspend fun <T> get(url: String, offset: Long? = null, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val parsed = url.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        val request = Request.Builder().url(parsed)
        if (offset != null) request.header("Range", "bytes=$offset-")
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(UpdateTransportException(e))
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
