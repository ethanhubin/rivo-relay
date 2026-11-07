package dev.rivo.relay.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONArray

const val RELEASES_PAGE = "https://github.com/ethanhubin/rivo-relay/releases"
private const val RELEASES_API = "https://api.github.com/repos/ethanhubin/rivo-relay/releases?per_page=100"

data class AppRelease(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val preview: Boolean)

/** Release tags use vMAJOR.MINOR.PATCH; compare numbers, never lexicographic strings. */
internal fun versionParts(value: String): List<Int>? {
    if (!Regex("v?\\d+\\.\\d+\\.\\d+").matches(value)) return null
    return value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
}
internal fun compareVersions(left: String, right: String): Int {
    val a = requireNotNull(versionParts(left))
    val b = requireNotNull(versionParts(right))
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
}

internal fun latestApkRelease(raw: String): AppRelease? {
    val releases = JSONArray(raw)
    return (0 until releases.length()).mapNotNull { index ->
        val release = releases.optJSONObject(index) ?: return@mapNotNull null
        val tag = release.optString("tag_name")
        if (release.optBoolean("draft") || versionParts(tag) == null) return@mapNotNull null
        val assets = release.optJSONArray("assets") ?: return@mapNotNull null
        val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter {
            it.optString("name").matches(Regex("rivo-relay-${Regex.escape(tag.removePrefix("v"))}(-debug)?\\.apk")) &&
                it.optString("state") == "uploaded" && it.optLong("size") > 0 &&
                it.optString("browser_download_url") == "$RELEASES_PAGE/download/$tag/${it.optString("name")}"
        }
        // Ambiguous builds need a release page decision, not an arbitrary APK.
        val apk = candidates.singleOrNull() ?: return@mapNotNull null
        AppRelease(tag.removePrefix("v"), release.optString("body").take(12000),
            apk.getString("browser_download_url"), "$RELEASES_PAGE/tag/$tag", release.optBoolean("prerelease"))
    }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
}

/** Independent public client: never attach node credentials or require a paired node. */
class AppUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build(),
    private val endpoint: String = RELEASES_API,
) {
    suspend fun check(currentVersion: String): AppRelease? {
        requireNotNull(versionParts(currentVersion)) { "当前版本号无法识别，请打开发布页查看" }
        val raw = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Rivo-Relay-Android/$currentVersion").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接 GitHub，请检查网络后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 403 || it.code == 429) throw IOException("GitHub 暂时限制请求，请稍后重试")
                            if (!it.isSuccessful) throw IOException("检查更新失败（${it.code}），请稍后重试")
                            val source = it.body?.source() ?: throw IOException("更新服务返回了空响应")
                            if (source.request(2 * 1024 * 1024L + 1)) throw IOException("更新信息过大，请打开发布页查看")
                            source.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
        val latest = try { latestApkRelease(raw) } catch (e: Exception) {
            throw IOException("更新信息无法识别，请稍后重试", e)
        } ?: throw IOException("尚未找到可下载的安装包，请打开发布页查看")
        return latest.takeIf { compareVersions(it.version, currentVersion) > 0 }
    }
}
