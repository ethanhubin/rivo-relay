package dev.rivo.relay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

/** Only immutable APK links on this deployment get hash validation with the existing resumable downloader. */
internal object PublicUpdateLink {
    fun digest(source: String): String? {
        val url = source.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != "134.175.156.219" || url.port != 8443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return Regex("/v1/(?:app-updates/apks|releases/files)/([a-f0-9]{64})\\.apk")
            .matchEntire(url.encodedPath)?.groupValues?.get(1)
    }

    suspend fun download(source: String, directory: File, client: OkHttpClient,
                         progress: (Long, Long) -> Unit): File? {
        val sha = digest(source) ?: return null
        val reader = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val file = ResumableDownload.get(directory, "public-update", source, reader,
            Request.Builder().url(source).build(), progress)
        val hash = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (hash != sha) {
            file.delete(); throw IOException("安装包校验失败，请重新下载")
        }
        return file
    }

}
