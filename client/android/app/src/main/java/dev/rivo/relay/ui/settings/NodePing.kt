package dev.rivo.relay.ui.settings

import android.os.SystemClock
import dev.rivo.relay.data.TailnetDns
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private val pingClient = OkHttpClient.Builder()
    .dns(TailnetDns())
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(3, TimeUnit.SECONDS)
    .build()

internal suspend fun measureNodePing(address: String): Long? = withContext(Dispatchers.IO) {
    val url = address.trimEnd('/').plus("/health").toHttpUrlOrNull() ?: return@withContext null
    val request = Request.Builder().url(url).build()
    val start = SystemClock.elapsedRealtime()
    try {
        pingClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) SystemClock.elapsedRealtime() - start else null
        }
    } catch (_: IOException) {
        null
    }
}
