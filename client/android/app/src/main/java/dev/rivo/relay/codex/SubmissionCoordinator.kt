package dev.rivo.relay.codex

import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

data class PendingSubmission(val id: String, val path: String, val label: String, val threadId: String? = null)
data class SubmissionState(val busy: Boolean = false, val pending: PendingSubmission? = null, val notice: String? = null)
data class BatchSubmission(val path: String, val body: JSONObject, val label: String)

/** Stable identity and frozen target adapted from QiGuang submission coordinator, without replay/outbox/queue. */
class SubmissionCoordinator(private val api: RelayApi, private val scope: CoroutineScope, private val vault: CredentialStore,
    private val storageKey: String,
    private val onResolved: (PendingSubmission, JSONObject) -> Unit = { _, _ -> },
    private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(SubmissionState(pending = vault.read(storageKey)?.let { runCatching {
        val j = JSONObject(it); PendingSubmission(j.getString("id"), j.getString("path"), j.getString("label"), j.string("threadId").takeIf(String::isNotBlank))
    }.getOrNull() }))
    val state = mutable.asStateFlow()
    private var afterAccepted: ((JSONObject) -> Unit)? = null
    private fun failureMessage(response: JSONObject, label: String): String = when (response.string("errorCode")) {
        "writer_still_held" -> "会话仍被进程持有，未确认释放成功"
        "handoff_changed", "handoff_confirmation_required" -> "持锁进程或关联会话已变化，请重新操作并确认"
        "handoff_acquire_failed" -> "原进程已结束，但新运行时尚未取得会话权限"
        "handoff_busy" -> "任务尚未确认停止，未执行交接"
        else -> response.optJSONObject("error")?.string("message").orEmpty().ifBlank { "$label 未完成" }
    }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    private fun save(pending: PendingSubmission?) {
        if (pending == null) vault.remove(storageKey)
        else vault.save(storageKey, JSONObject().put("id", pending.id).put("path", pending.path).put("label", pending.label).put("threadId", pending.threadId).toString())
        mutable.update { it.copy(pending = pending) }
    }
    /** Explicit user confirmation releases only this phone's wait. It never sends or cancels a native action. */
    fun finishReconciliation(expectedId: String) {
        if (mutable.value.busy || mutable.value.pending?.id != expectedId) return
        afterAccepted = null
        save(null)
        mutable.update { it.copy(notice = "已结束手机等待。原操作未取消，节点记录仍保留。") }
    }
    private fun retainReceipt(pending: PendingSubmission, response: JSONObject) {
        val threadId = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank) ?: pending.threadId
        if (threadId != pending.threadId) save(pending.copy(threadId = threadId))
    }
    fun submit(path: String, body: JSONObject, label: String, submissionId: String = UUID.randomUUID().toString(),
        prepared: (PendingSubmission) -> Unit = {}, beforeSend: suspend (PendingSubmission) -> Unit = {}, accepted: (JSONObject) -> Unit = {}) {
        if (mutable.value.busy || mutable.value.pending != null) return
        val originalThread = Regex("^/v1/threads/([^/]+)/").find(path)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pending = PendingSubmission(submissionId, path, label, originalThread)
        val frozenBody = JSONObject(body.toString()).put("submissionId", pending.id)
        afterAccepted = accepted
        mutable.update { it.copy(busy = true, notice = null) }
        try { prepared(pending) }
        catch (e: Exception) {
            runCatching { onResolved(pending, JSONObject().put("status", "failed")) }
            afterAccepted = null
            mutable.update { it.copy(busy = false) }
            failure(e)
            return
        }
        scope.launch {
            var completed: JSONObject? = null
            var requestStarted = false
            try {
                save(pending)
                beforeSend(pending)
                requestStarted = true
                val response = api.call(path, "POST", frozenBody)
                when (response.string("status")) {
                    "accepted" -> { onResolved(pending, response); save(null); afterAccepted = null; mutable.update { it.copy(notice = null) }; completed = response.optJSONObject("receipt") ?: JSONObject() }
                    "failed" -> { onResolved(pending, response); save(null); afterAccepted = null; failure(ApiException(422, "submission_failed", failureMessage(response, label))) }
                    else -> { onResolved(pending, response); retainReceipt(pending, response); mutable.update { it.copy(notice = "$label 结果待核对，不会自动重发") } }
                }
            } catch (e: Exception) {
                val definitive = !requestStarted || e is ApiException && e.status in 400..499 && e.status != 408
                onResolved(pending, JSONObject().put("status", if (definitive) "failed" else "unknown"))
                if (definitive) { save(null); afterAccepted = null }
                if (e !is CancellationException) failure(e)
            } finally { mutable.update { it.copy(busy = false) } }
            completed?.let(accepted)
        }
    }
    /** Each write has a durable ID. Stop the batch on failure or uncertainty. */
    fun submitBatch(operations: List<BatchSubmission>, onChanged: () -> Unit = {}) {
        val frozen = operations.map { it.copy(body = JSONObject(it.body.toString())) }
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
        mutable.update { it.copy(busy = true) }
        scope.launch {
            var completed: Pair<(JSONObject) -> Unit, JSONObject>? = null
            try {
                val response = api.call("/v1/submissions/" + encoded(pending.id))
                when (response.string("status")) {
                    "accepted", "failed" -> {
                        onResolved(pending, response)
                        save(null)
                        val completion = afterAccepted; afterAccepted = null
                        val succeeded = response.string("status") == "accepted"
                        mutable.update { it.copy(notice = if (succeeded) "已确认节点接受该操作" else failureMessage(response, pending.label)) }
                        if (succeeded && completion != null)
                            completed = completion to (response.optJSONObject("receipt") ?: JSONObject())
                    }
                    else -> { onResolved(pending, response); retainReceipt(pending, response); mutable.update { it.copy(notice = "节点仍未确认该操作结果，请稍后核对。") } }
                }
            } catch (e: Exception) {
                if (e is ApiException && e.status == 404) failure(ApiException(404, e.code, "节点暂未记录此提交，原请求仍可能在处理中。请稍后核对，不要重新发送。"))
                else if (e !is CancellationException) failure(e)
            }
            finally { mutable.update { it.copy(busy = false) } }
            // Clear the old owner before invoking a continuation. A batch may synchronously
            // start its next submission, whose busy flag/callback must not be reset by this read.
            completed?.let { (completion, receipt) -> completion(receipt) }
        }
    }
}
