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

/** Retain and retry orphaned durable checkpoints after temporary read/save failures. */
internal suspend fun recoverPendingCheckpoints(
    read: suspend () -> Set<String>, resolve: suspend (String) -> Unit,
    failure: (Throwable) -> Unit, intervalMillis: Long = 10_000,
) {
    while (currentCoroutineContext().isActive) {
        try {
            val ids = read()
            if (ids.isEmpty()) return
            for (id in ids) try { resolve(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e) }
        delay(intervalMillis)
    }
}

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
    private val recoveryDelayMillis: Long = 3_000,
    private val recoveryIntervalMillis: Long = 10_000,
    private val failure: (Throwable) -> Unit) {
    private val mutable = MutableStateFlow(SubmissionState(pending = vault.read(storageKey)?.let {
        try {
            val j = JSONObject(it)
            val id = j.getString("id"); val path = j.getString("path")
            require(id.isNotBlank() && path.startsWith("/v1/"))
            PendingSubmission(id, path, j.getString("label"), j.string("threadId").takeIf(String::isNotBlank))
        } catch (e: Exception) {
            throw IllegalArgumentException("无法读取未确认提交记录，已保留原记录，未允许重新提交", e)
        }
    }))
    val state = mutable.asStateFlow()
    init {
        require(recoveryDelayMillis >= 0 && recoveryIntervalMillis > 0)
        // Recovery belongs to the node scope, not the currently composed screen.
        // Only read/fence the original ID; never replay a mutation after uncertainty.
        scope.launch {
            state.map { it.pending?.id }.distinctUntilChanged().collectLatest { id ->
                if (id != null) {
                    delay(recoveryDelayMillis)
                    while (isActive) {
                        reconcile()
                        delay(recoveryIntervalMillis)
                    }
                }
            }
        }
    }
    private var afterAccepted: ((JSONObject) -> Unit)? = null
    private fun validateResponse(pending: PendingSubmission, response: JSONObject) {
        val returnedId = response.string("submissionId")
        if (returnedId.isNotBlank() && returnedId != pending.id)
            throw IllegalArgumentException("提交回执身份不一致，将继续自动核对")
        if (pending.path == USAGE_RESET_PATH && response.string("status") == "accepted") {
            val receipt = response.optJSONObject("receipt")
            require(receipt?.string("requestId") == pending.id && receipt.string("resetOutcome") in usageResetOutcomes) {
                "额度重置回执不完整，将继续核对原操作"
            }
        }
        val expectedThread = Regex("^/v1/threads/([^/]+)/").find(pending.path)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val returnedThread = response.optJSONObject("receipt")?.string("threadId").orEmpty()
        if (expectedThread != null && !pending.path.endsWith("/fork") && returnedThread.isNotBlank() && returnedThread != expectedThread)
            throw IllegalArgumentException("回执对应其他会话，将继续自动核对")
        if (response.string("status") != "accepted") return
        val receipt = response.optJSONObject("receipt") ?: throw IllegalArgumentException("成功回执缺少身份信息，将继续自动核对")
        val threadOperation = pending.path == "/v1/threads" || pending.path.startsWith("/v1/threads/")
        if (receipt.length() == 0 || threadOperation && receipt.string("threadId").isBlank())
            throw IllegalArgumentException("成功回执缺少会话身份，将继续自动核对")
        if (pending.path.endsWith("/turns") || pending.path.endsWith("/continue") || pending.path.endsWith("/answers")) {
            if (receipt.string("turnId").isBlank()) throw IllegalArgumentException("成功回执缺少任务身份，将继续自动核对")
        }
    }
    private fun failureMessage(response: JSONObject, label: String): String = when (response.string("errorCode")) {
        "submission_not_received" -> "节点未收到该操作，已解除等待；草稿已保留，请重新发送。"
        "usage_reset_recovery_only" -> "已尝试核对原额度重置，请查看刷新后的额度再确认；本次未创建新的兑换"
        "usage_reset_unsupported" -> "此节点不支持额度重置，请更新节点"
        "usage_reset_account_unverified" -> "无法确认当前 ChatGPT 账户，未重置额度"
        "usage_reset_account_changed" -> "登录账户已变化，请核对原重置操作"
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
    private suspend fun retainReceipt(pending: PendingSubmission, response: JSONObject) {
        val threadId = response.optJSONObject("receipt")?.string("threadId")?.takeIf(String::isNotBlank) ?: pending.threadId
        if (threadId != pending.threadId) save(pending.copy(threadId = threadId))
    }
    fun submit(path: String, body: JSONObject, label: String, submissionId: String = UUID.randomUUID().toString(),
        prepared: (PendingSubmission) -> Unit = {}, beforeSend: suspend (PendingSubmission) -> Unit = {},
        prepareBody: suspend (JSONObject) -> JSONObject = { it }, accepted: (JSONObject) -> Unit = {}) {
        val originalThread = Regex("^/v1/threads/([^/]+)/").find(path)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val pending = PendingSubmission(submissionId, path, label, originalThread)
        val frozenBody = copyJsonObject(body).put("submissionId", pending.id)
        while (true) {
            val current = mutable.value
            if (current.busy || current.pending != null) return
            // Publish ownership before prepared()/attachment staging can create
            // durable overlays. Orphan recovery must not fence this live ID.
            if (mutable.compareAndSet(current, current.copy(busy = true, pending = pending, notice = null))) break
        }
        afterAccepted = accepted
        scope.launch {
            var completed: JSONObject? = null
            var requestStarted = false
            var terminalResponse = false
            try {
                prepared(pending)
                val requestBody = prepareBody(frozenBody)
                save(pending)
                beforeSend(pending)
                requestStarted = true
                val response = api.call(path, "POST", requestBody)
                terminalResponse = response.string("status") in setOf("accepted", "failed")
                validateResponse(pending, response)
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
                    // An uncertain send is represented by the durable pending receipt.
                    // Do not stack a global transport error on top of that actionable state.
                    if (reported !is CancellationException && (definitive || reported !== e || e is ApiException || e !is java.io.IOException)) failure(reported)
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
        val pending: PendingSubmission
        while (true) {
            val current = mutable.value
            val candidate = current.pending ?: return
            if (current.busy) return
            if (mutable.compareAndSet(current, current.copy(busy = true, reconciling = true))) {
                pending = candidate
                break
            }
        }
        scope.launch {
            var completed: Pair<(JSONObject) -> Unit, JSONObject>? = null
            var receivedResponse = false
            try {
                var response = api.reconcileSubmission(pending.id)
                if (pending.path == USAGE_RESET_PATH && response.string("status") == "unknown") {
                    // Only this native operation explicitly guarantees idempotent redemption.
                    // The node retains the original account binding and exact native key.
                    response = api.call("$USAGE_RESET_PATH/reconcile", "POST", JSONObject().put("submissionId", pending.id))
                }
                receivedResponse = true
                validateResponse(pending, response)
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
                    else -> {
                        onResolved(pending, response); retainReceipt(pending, response)
                        val notice = if (pending.path == USAGE_RESET_PATH) when (response.string("recoveryErrorCode")) {
                            "usage_reset_account_changed" -> "登录账户已变化，请恢复原账户后核对；原重置结果仍未知"
                            "usage_reset_account_unverified", "native_auth_required" -> "请恢复原 ChatGPT 账户登录后核对；原重置结果仍未知"
                            "usage_reset_unsupported" -> "请更新节点后核对原重置操作，勿另行重复兑换"
                            else -> "额度重置结果待核对，正在使用原操作身份恢复"
                        } else "节点仍未确认该操作结果，请稍后核对。"
                        mutable.update { it.copy(notice = notice) }
                    }
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
