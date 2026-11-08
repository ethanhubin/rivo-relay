package dev.rivo.relay.codex

import dev.rivo.relay.connection.CredentialStorage
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

data class PendingSubmission(val id: String, val path: String, val label: String, val threadId: String? = null)
data class SubmissionState(val busy: Boolean = false, val pending: PendingSubmission? = null, val notice: String? = null, val reconciling: Boolean = false, val resolvedRevision: Long = 0)
data class BatchSubmission(val path: String, val body: JSONObject, val label: String)

/** Never retire the durable submission ID when recording its outcome failed. */
internal suspend fun recordSubmissionFailure(cause: Throwable, definitive: Boolean,
    record: suspend () -> Unit, clear: suspend () -> Unit): Throwable {
    return try {
        record()
        if (definitive) clear()
        cause
    } catch (recoveryError: Exception) {
        if (recoveryError !== cause) recoveryError.addSuppressed(cause)
        recoveryError
    }
}

/** Stable identity and frozen target adapted from QiGuang submission coordinator, without replay/outbox/queue. */
class SubmissionCoordinator(private val api: RelayApi, private val scope: CoroutineScope, private val vault: CredentialStorage,
    private val storageKey: String,
    private val onResolved: suspend (PendingSubmission, JSONObject) -> Unit = { _, _ -> },
    private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(SubmissionState(pending = vault.read(storageKey)?.let { runCatching {
        val j = JSONObject(it); PendingSubmission(j.getString("id"), j.getString("path"), j.getString("label"), j.string("threadId").takeIf(String::isNotBlank))
    }.getOrNull() }))
    val state = mutable.asStateFlow()
    private var afterAccepted: ((JSONObject) -> Unit)? = null
    private fun failureMessage(response: JSONObject, label: String): String = when (response.string("errorCode")) {
        "submission_not_received" -> "节点未收到该操作，已解除等待；草稿已保留，请重新发送。"
        "native_auth_required" -> "Codex 登录已失效，请在后台重新授权并检查独立后台登录状态。无需重新配对手机。"
        "writer_still_held" -> "会话仍被进程持有，未确认释放成功"
        "handoff_changed", "handoff_confirmation_required" -> "持锁进程或关联会话已变化，请重新操作并确认"
        "handoff_acquire_failed" -> "原进程已结束，但新运行时尚未取得会话权限"
        "handoff_busy" -> "任务尚未确认停止，未执行交接"
        else -> response.optJSONObject("error")?.string("message").orEmpty().ifBlank { "$label 未完成" }
    }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    private suspend fun save(pending: PendingSubmission?) {
        withContext(Dispatchers.IO) {
            if (pending == null) vault.remove(storageKey)
            else vault.save(storageKey, JSONObject().put("id", pending.id).put("path", pending.path).put("label", pending.label).put("threadId", pending.threadId).toString())
        }
        mutable.update { it.copy(pending = pending) }
    }
    /** Explicit user confirmation releases only this phone's wait. It never sends or cancels a native action. */
    fun finishReconciliation(expectedId: String) {
        if (mutable.value.busy || mutable.value.pending?.id != expectedId) return
        afterAccepted = null
        mutable.update { it.copy(busy = true) }
        scope.launch {
            try { save(null); mutable.update { it.copy(notice = "已结束手机等待。原操作未取消，节点记录仍保留。") } }
            catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun retainReceipt(pending: PendingSubmission, response: JSONObject) {
        val threadId = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank) ?: pending.threadId
        if (threadId != pending.threadId) save(pending.copy(threadId = threadId))
    }
    fun submit(path: String, body: JSONObject, label: String, submissionId: String = UUID.randomUUID().toString(),
        prepared: (PendingSubmission) -> Unit = {}, beforeSend: suspend (PendingSubmission) -> Unit = {},
        prepareBody: suspend (JSONObject) -> JSONObject = { it }, accepted: (JSONObject) -> Unit = {}) {
        if (mutable.value.busy || mutable.value.pending != null) return
        val originalThread = Regex("^/v1/threads/([^/]+)/").find(path)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pending = PendingSubmission(submissionId, path, label, originalThread)
        val frozenBody = copyJsonObject(body).put("submissionId", pending.id)
        afterAccepted = accepted
        mutable.update { it.copy(busy = true, notice = null) }
        try { prepared(pending) }
        catch (e: Exception) {
            scope.launch { runCatching { onResolved(pending, JSONObject().put("status", "failed")) } }
            afterAccepted = null
            mutable.update { it.copy(busy = false) }
            failure(e)
            return
        }
        scope.launch {
            var completed: JSONObject? = null
            var requestStarted = false
            var terminalResponse = false
            try {
                val requestBody = prepareBody(frozenBody)
                save(pending)
                beforeSend(pending)
                requestStarted = true
                val response = api.call(path, "POST", requestBody)
                terminalResponse = response.string("status") in setOf("accepted", "failed")
                when (response.string("status")) {
                    "accepted" -> { onResolved(pending, response); save(null); afterAccepted = null; mutable.update { it.copy(notice = null) }; completed = response.optJSONObject("receipt") ?: JSONObject() }
                    "failed" -> { onResolved(pending, response); save(null); afterAccepted = null; failure(ApiException(422, "submission_failed", failureMessage(response, label))) }
                    else -> { onResolved(pending, response); retainReceipt(pending, response); mutable.update { it.copy(notice = "$label 结果待核对，不会自动重发") } }
                }
            } catch (e: Exception) {
                // A local persistence error cannot undo an authoritative receipt.
                // Keep the durable ID/callback so reconciliation can finish the save.
                if (terminalResponse) {
                    if (e !is CancellationException) failure(e)
                } else {
                    // A conflicting ID belongs to the original request, not this continuation.
                    if (e is ApiException && e.code == "submission_conflict") afterAccepted = null
                    val definitive = !requestStarted || e is ApiException && e.status in 400..499 &&
                        e.status != 408 && e.code != "submission_conflict"
                    val reported = withContext(NonCancellable) {
                        recordSubmissionFailure(e, definitive,
                            record = { onResolved(pending, JSONObject().put("status", if (definitive) "failed" else "unknown")) },
                            clear = { save(null); afterAccepted = null })
                    }
                    if (reported !is CancellationException) failure(reported)
                }
            } finally { mutable.update { it.copy(busy = false) } }
            completed?.let { complete(accepted, it) }
        }
    }
    private fun complete(callback: (JSONObject) -> Unit, receipt: JSONObject) {
        try { callback(receipt) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e) }
        // A continuation can own the next submission already. Never reset its
        // state or rewrite the previous accepted result when the callback fails.
    }
    /** Each write has a durable ID. Stop the batch on failure or uncertainty. */
    fun submitBatch(operations: List<BatchSubmission>, onChanged: () -> Unit = {}) {
        val frozen = operations.map { it.copy(body = copyJsonObject(it.body)) }
        fun next(index: Int) {
            if (index >= frozen.size) return
            val item = frozen[index]
            submit(item.path, item.body, item.label) { onChanged(); next(index + 1) }
        }
        next(0)
    }
    fun reconcile() {
        val pending = mutable.value.pending ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, reconciling = true) }
        scope.launch {
            var completed: Pair<(JSONObject) -> Unit, JSONObject>? = null
            var receivedResponse = false
            try {
                val response = api.reconcileSubmission(pending.id)
                receivedResponse = true
                when (response.string("status")) {
                    "accepted", "failed" -> {
                        onResolved(pending, response)
                        save(null)
                        val completion = afterAccepted; afterAccepted = null
                        val succeeded = response.string("status") == "accepted"
                        mutable.update { it.copy(notice = if (succeeded) null else failureMessage(response, pending.label), resolvedRevision = it.resolvedRevision + 1) }
                        if (succeeded && completion != null)
                            completed = completion to (response.optJSONObject("receipt") ?: JSONObject())
                    }
                    else -> { onResolved(pending, response); retainReceipt(pending, response); mutable.update { it.copy(notice = "节点仍未确认该操作结果，请稍后核对。") } }
                }
            } catch (e: Exception) {
                // Background receipt polling must not repeatedly raise global connection errors.
                // Retain the durable ID and try again; authentication errors still need attention.
                if (e !is CancellationException && (receivedResponse || e is ApiException && e.status == 401)) failure(e)
            }
            finally { mutable.update { it.copy(busy = false, reconciling = false) } }
            // Clear the old owner before invoking a continuation. A batch may synchronously
            // start its next submission, whose busy flag/callback must not be reset by this read.
            completed?.let { (completion, receipt) -> complete(completion, receipt) }
        }
    }
}
