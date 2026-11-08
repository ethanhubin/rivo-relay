package dev.rivo.relay.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class RecoveryState { CONNECTED, CONNECTING, DISCONNECTED }
class ConnectionPausedException : IOException("节点已断开，请在设置中重连")
internal class ReconnectProbe
fun isConnectionFailure(error: Throwable): Boolean =
    error is IOException && error !is ApiException || error is ApiException &&
        error.status in 502..504 && error.code != "native_history_unavailable"

/** Retries a read-only probe, never the failed operation. Exhaustion requires manual recovery. */
class ConnectionRecovery(private val scope: CoroutineScope, private val probe: suspend () -> Unit,
    private val reset: suspend () -> Unit, private val restored: suspend () -> Unit,
    private val rejected: (Throwable) -> Unit, private val timeoutMillis: Long = 12_000,
    private val backoffMillis: Long = 1_000) {
    private val mutable = MutableStateFlow(RecoveryState.CONNECTED)
    val state = mutable.asStateFlow()
    private var job: Job? = null

    @Synchronized fun reconnect(manual: Boolean = false) {
        if (job?.isActive == true || (!manual && mutable.value == RecoveryState.DISCONNECTED)) return
        val verifyFirst = !manual && mutable.value == RecoveryState.CONNECTED
        if (!verifyFirst) mutable.value = RecoveryState.CONNECTING
        job = scope.launch {
            var connected = false
            try {
                // A failed business response does not prove that the node is offline.
                // Keep unrelated requests and subscriptions alive during this probe.
                if (verifyFirst) {
                    try {
                        withTimeout(timeoutMillis) { probe() }
                        return@launch // No reset, refresh, or replay of the failing read.
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (e: Exception) {
                        if (!isConnectionFailure(e)) {
                            rejected(e)
                            return@launch
                        }
                    }
                    mutable.value = RecoveryState.CONNECTING
                }
                reset()
                val attempts = if (verifyFirst) 2 else 3
                for (attempt in 0 until attempts) {
                    try {
                        withTimeout(timeoutMillis) { probe() }
                        connected = true
                        break
                    } catch (_: TimeoutCancellationException) {
                        // Each attempt has its own deadline; cancellation of the owner still propagates.
                        currentCoroutineContext().ensureActive()
                    } catch (e: CancellationException) {
                        // Transport replacement can cancel just the probe; owner cancellation must stop the round.
                        currentCoroutineContext().ensureActive()
                    }
                    catch (e: Exception) {
                        if (!isConnectionFailure(e)) { rejected(e); break }
                    }
                    if (attempt < attempts - 1) delay(backoffMillis * (attempt + 1))
                }
                mutable.value = if (connected) RecoveryState.CONNECTED else RecoveryState.DISCONNECTED
                if (connected) restored()
            } finally {
                if (mutable.value == RecoveryState.CONNECTING) mutable.value = RecoveryState.DISCONNECTED
            }
        }
    }

    /** An explicit download/retry may recover its read, but never replay a mutation. */
    suspend fun awaitDownloadConnection() {
        if (state.value == RecoveryState.DISCONNECTED) reconnect(manual = true)
        if (state.first { it != RecoveryState.CONNECTING } != RecoveryState.CONNECTED)
            throw ConnectionPausedException()
    }

    fun failed(error: Throwable) {
        if (error !is ConnectionPausedException && isConnectionFailure(error) && state.value == RecoveryState.CONNECTED) reconnect()
    }
}
