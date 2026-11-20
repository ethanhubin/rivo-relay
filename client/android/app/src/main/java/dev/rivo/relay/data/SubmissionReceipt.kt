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

/** Accepted input and started execution are distinct provider-neutral facts. */
internal sealed interface InputReceipt {
    data class Accepted(val submissionId: String, val threadId: String) : InputReceipt
    data class Started(val submissionId: String, val threadId: String, val executionId: String) : InputReceipt
}

internal fun decodeInputReceipt(receipt: JSONObject, submissionId: String, threadId: String? = null): InputReceipt? {
    val returnedThread = receipt.opt("threadId") as? String ?: return null
    if (returnedThread.isBlank() || threadId != null && threadId != returnedThread) return null
    if (receipt.has("kind")) {
        if (receipt.opt("submissionId") != submissionId) return null
        if (receipt.has("submission_id") && receipt.opt("submission_id") != submissionId) return null
        if (receipt.has("thread_id") && receipt.opt("thread_id") != returnedThread) return null
        return when (receipt.opt("kind")) {
            "input-accepted" -> if (listOf("turnId", "turn_id", "executionId", "execution_id").any(receipt::has)) null else InputReceipt.Accepted(submissionId, returnedThread)
            "execution-started" -> (receipt.opt("executionId") as? String)?.takeIf(String::isNotBlank)
                ?.takeIf { execution -> listOf("turnId", "turn_id", "execution_id").all { !receipt.has(it) || receipt.opt(it) == execution } }
                ?.let { InputReceipt.Started(submissionId, returnedThread, it) }
            else -> null
        }
    }
    // Decode persisted receipts issued before the neutral protocol; no product branch
    // escapes this compatibility boundary, and malformed new receipts never fall back.
    val turn = (receipt.opt("turnId") as? String)?.takeIf(String::isNotBlank)
    if (turn != null) return InputReceipt.Started(submissionId, returnedThread, turn)
    return if (receipt.opt("runtime") == "dsh" && receipt.opt("acceptance") == "input" && receipt.opt("requestId") == submissionId)
        InputReceipt.Accepted(submissionId, returnedThread) else null
}
