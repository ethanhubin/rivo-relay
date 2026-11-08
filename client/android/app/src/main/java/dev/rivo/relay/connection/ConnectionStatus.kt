package dev.rivo.relay.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Observes the final routed response; never infers connectivity from a saved preference. */
data class ConnectionStatus(val endpoint: String = "", val httpCode: Int? = null, val failed: Boolean = false, val checkedAt: Long = 0)

class ConnectionStatusObserver(private val routes: NodeRoutes?, private val base: String) : Interceptor {
    private val state = MutableStateFlow(ConnectionStatus())
    val status = state.asStateFlow()
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            val response = chain.proceed(chain.request())
            val url = response.request.url.toString()
            val endpoint = listOfNotNull(routes?.relay, routes?.direct, base)
                .filter { it.isNotBlank() }.firstOrNull { url.startsWith(it.trimEnd('/') + "/") }.orEmpty()
            state.value = ConnectionStatus(endpoint, response.code, checkedAt = System.currentTimeMillis())
            return response
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) state.value = ConnectionStatus(failed = true, checkedAt = System.currentTimeMillis())
            throw error
        }
    }
}
