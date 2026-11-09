package dev.rivo.relay.data

/** Fixed diagnostic categories: never include URL, cursor, body, token or exception message. */
fun sitesDiagnostic(requestId: String, outcome: String, durationMs: Long, failure: Throwable? = null): String {
    val code = when (failure) {
        is ConnectionPausedException -> "connection_paused"
        is ApiException -> failure.code.takeIf { it in setOf("sites_unavailable", "sites_invalid_response", "native_timeout", "native_unavailable", "unauthorized", "invalid_response", "node_updating") } ?: "http_error"
        is java.net.SocketTimeoutException -> "transport_timeout"
        is java.io.IOException -> "transport_error"
        null -> "none"
        else -> "client_error"
    }
    val id = requestId.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: "invalid"
    val state = outcome.takeIf { it in setOf("started", "completed", "failed", "cancelled") } ?: "unknown"
    return "request_id=$id outcome=$state duration_ms=${durationMs.coerceAtLeast(0)} error_code=$code status=${(failure as? ApiException)?.status ?: 0}"
}
