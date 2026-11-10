package dev.rivo.relay.data

import org.json.JSONObject

/** A missing read alone cannot prove failure: fence delayed requests on the node first. */
suspend fun RelayApi.reconcileSubmission(id: String): JSONObject {
    val path = "/v1/submissions/${encoded(id)}"
    val response = try { call(path) }
    catch (e: ApiException) {
        if (e.status != 404 || e.code != "submission_not_found") throw e
        call("$path/reconcile", "POST", JSONObject())
    }
    require(response.string("submissionId").let { it.isBlank() || it == id }) { "提交回执身份不一致，将继续自动核对" }
    if (response.string("status") == "accepted") {
        val receipt = response.optJSONObject("receipt")
        require(receipt != null && receipt.keys().asSequence().any { key ->
            (key.endsWith("Id") || key.endsWith("_id")) && (receipt.opt(key) as? String)?.isNotBlank() == true
        }) { "成功回执缺少身份信息，将继续自动核对" }
    }
    return response
}
