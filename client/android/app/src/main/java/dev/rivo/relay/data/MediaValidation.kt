package dev.rivo.relay.data

import org.json.JSONObject
import kotlinx.coroutines.async

/** Only a missing endpoint permits legacy transport; missing media/auth failures do not. */
internal suspend fun readMediaVersions(read: suspend () -> JSONObject): JSONObject? =
    try { read() }
    catch (error: ApiException) {
        if (error.status == 404 && error.code == "not_found") null else throw error
    }

internal fun <T> imageAfterValidationFailure(previous: T?, error: Throwable): T? =
    if (isTransientMediaFailure(error)) previous else null

internal fun isTransientMediaFailure(error: Throwable): Boolean =
    if (error is coil.network.HttpException) error.response.code == 429 || error.response.code in 502..504
    else (error is ApiException && error.status == 429 && error.code == "media_busy") || isConnectionFailure(error)

internal fun isMediaVersionMismatch(error: Throwable): Boolean =
    (error is ApiException && error.status == 412) ||
        (error is coil.network.HttpException && error.response.code == 412)

internal suspend fun <T> retryChangedMedia(refresh: suspend () -> Unit, load: suspend () -> T): T =
    try { load() } catch (error: Exception) {
        if (!isMediaVersionMismatch(error)) throw error
        refresh()
        load() // One retry only, even if the source changes again.
    }

/** Shared work belongs to the client, so cancelling one UI waiter cannot cancel another. */
internal class MediaValidationFlights(private val scope: kotlinx.coroutines.CoroutineScope) {
    private val active = mutableMapOf<String, kotlinx.coroutines.Deferred<Unit>>()
    suspend fun run(source: String, validate: suspend () -> Unit) {
        val work = synchronized(active) {
            active[source]?.takeIf { !it.isCompleted } ?: scope.async {
                validate()
            }.also { active[source] = it }
        }
        try { work.await() } finally {
            synchronized(active) {
                if (work.isCompleted && active[source] === work) active.remove(source)
            }
        }
    }
}
