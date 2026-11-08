package dev.rivo.relay.connection

import dev.rivo.relay.data.RecoveryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Observes the final routed response; never infers connectivity from a saved preference. */
data class ConnectionStatus(val endpoint: String = "", val httpCode: Int? = null, val failed: Boolean = false, val checkedAt: Long = 0,
    val latencyMs: Long? = null)

class ConnectionStatusObserver(private val routes: NodeRoutes?, private val base: String) : Interceptor {
    private val state = MutableStateFlow(ConnectionStatus())
    val status = state.asStateFlow()
    override fun intercept(chain: Interceptor.Chain): Response {
        val startedAt = System.nanoTime()
        try {
            val response = chain.proceed(chain.request())
            val url = response.request.url.toString()
            val endpoint = listOfNotNull(routes?.relay, routes?.direct, base)
                .filter { it.isNotBlank() }.firstOrNull { url.startsWith(it.trimEnd('/') + "/") }.orEmpty()
            state.value = ConnectionStatus(endpoint, response.code, checkedAt = System.currentTimeMillis(),
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000)
            return response
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) state.value = ConnectionStatus(failed = true, checkedAt = System.currentTimeMillis())
            throw error
        }
    }
}

/** Connection indicators describe the transport, never project/session loading. */
fun ConnectionStatus.indicatorState(recovery: RecoveryState?): RecoveryState = when {
    recovery == RecoveryState.CONNECTING -> RecoveryState.CONNECTING
    recovery == RecoveryState.DISCONNECTED -> RecoveryState.DISCONNECTED
    checkedAt == 0L -> RecoveryState.CONNECTING
    !failed && httpCode in 200..299 -> RecoveryState.CONNECTED
    else -> RecoveryState.DISCONNECTED
}
