package dev.rivo.relay.data

import org.json.JSONObject

/** A missing read alone cannot prove failure: fence delayed requests on the node first. */
suspend fun RelayApi.reconcileSubmission(id: String): JSONObject {
    val path = "/v1/submissions/${encoded(id)}"
    return try { call(path) }
    catch (e: ApiException) {
        if (e.status != 404 || e.code != "submission_not_found") throw e
        call("$path/reconcile", "POST", JSONObject())
    }
}
