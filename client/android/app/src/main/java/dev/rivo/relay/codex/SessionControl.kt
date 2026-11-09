package dev.rivo.relay.codex

import dev.rivo.relay.data.string

enum class ControlState { WRITABLE, EXTERNAL, RELEASED, RELEASE_PENDING, WORKSPACE_UNAVAILABLE, UNVERIFIED, UNKNOWN }
data class SessionControl(val state: ControlState, val action: String? = null, val reason: String = "") {
    val canOperate get() = state == ControlState.WRITABLE
}

/** One projection for both the status notice and native mutation controls. */
fun SessionState.control(): SessionControl {
    val snapshot = snapshot ?: return SessionControl(ControlState.UNKNOWN)
    if (stale || loading) return SessionControl(ControlState.UNKNOWN)
    val wire = snapshot.control
    val state = if (wire != null) when (wire.string("state")) {
        "writable" -> if (wire.optBoolean("canOperate") && !snapshot.readOnly) ControlState.WRITABLE else ControlState.UNKNOWN
        "external" -> ControlState.EXTERNAL
        "released" -> ControlState.RELEASED
        "release_pending" -> ControlState.RELEASE_PENDING
        "workspace_unavailable" -> ControlState.WORKSPACE_UNAVAILABLE
        "ownership_unverified" -> ControlState.UNVERIFIED
        else -> ControlState.UNKNOWN
    } else when {
        snapshot.workspaceUnavailable -> ControlState.WORKSPACE_UNAVAILABLE
        snapshot.writerOwner == "unknown" -> ControlState.UNVERIFIED
        snapshot.readOnly && snapshot.writerOwner == "none" -> ControlState.RELEASED
        snapshot.readOnly -> ControlState.EXTERNAL
        else -> ControlState.WRITABLE
    }
    val action = if (wire != null) wire.string("action").takeIf { it in setOf("resume", "release", "takeover") }
        else when (state) {
            ControlState.WRITABLE -> "release"
            ControlState.EXTERNAL, ControlState.WORKSPACE_UNAVAILABLE -> "takeover".takeIf { snapshot.nativeRuntime?.optBoolean("handoffAvailable", true) != false }
            else -> null // Older nodes do not implement ordinary resume.
        }
    return SessionControl(state, action.takeUnless { state in setOf(ControlState.UNKNOWN, ControlState.UNVERIFIED) }, wire?.string("unavailableReason").orEmpty())
}


data class TakeoverControl(val enabled: Boolean, val reason: String)

/** A fresh handoff preview is authoritative even when conversation reads are stale. */
fun SessionState.takeoverControl(blockedReason: String? = null): TakeoverControl {
    if (blockedReason != null) return TakeoverControl(false, blockedReason)
    if (id.isNullOrBlank()) return TakeoverControl(false, "尚未确定会话，暂时无法接管")
    if (stale || loading || snapshot == null)
        return TakeoverControl(true, "会话状态尚未同步；点击后先向服务器重新核验，再确认接管")
    val runtime = snapshot.nativeRuntime
    if (runtime?.optBoolean("desktopShared") == true || runtime?.string("runtime") == "desktop")
        return TakeoverControl(false, "共享 Desktop 不支持结束进程接管；请在 Desktop 停止任务或释放会话")
    if (runtime?.optBoolean("handoffCheckAvailable") == true)
        return TakeoverControl(true, "接管前将重新核验实际运行主机和持锁进程")
    if (runtime?.has("handoffAvailable") == true && !runtime.optBoolean("handoffAvailable"))
        return TakeoverControl(false, runtime.string("handoffUnavailableReason").ifBlank { "服务器暂不能确认接管条件，请刷新后重试" })
    return TakeoverControl(true, "接管前会核验并展示影响范围，确认后执行")
}


/** Loading/transport uncertainty must never be presented as loss of ownership. */
fun SessionState.displayControl(): SessionControl =
    control().takeUnless { it.state == ControlState.UNKNOWN }
        ?: lastConfirmedControl ?: SessionControl(ControlState.UNKNOWN)

fun SessionState.showTakeover(): Boolean = control().state == ControlState.EXTERNAL

fun SessionState.showRelease(): Boolean = control().let { it.canOperate && it.action == "release" }

/** A released session can draft/send; the send request acquires normally on the server. */
fun SessionState.canCompose(): Boolean = control().let {
    it.canOperate || it.state == ControlState.RELEASED && it.action == "resume"
}

/** Queue bookkeeping does not acquire a native writer or execute input. */
fun SessionState.canManageQueue(): Boolean =
    !stale && !loading && snapshot != null && id == snapshot.id

fun SessionState.canQueueOperation(operation: String): Boolean =
    if (operation in setOf("edit", "remove", "reorder", "dismiss")) canManageQueue()
    else control().canOperate

fun SessionState.canResumeQueue(): Boolean = canManageQueue() &&
    control().let { it.state == ControlState.RELEASED && it.action == "resume" } &&
    snapshot?.queue?.any { it.status == "waiting" } == true

/** Only a fresh, idle interrupted turn can be explicitly continued. */
fun SessionState.canContinueTask(): Boolean = canCompose() && snapshot?.let {
    it.id == id && it.resumeTurnId.isNotBlank() && it.activeTurnId.isBlank() &&
        it.status == "idle" && it.requests.isEmpty()
} == true
