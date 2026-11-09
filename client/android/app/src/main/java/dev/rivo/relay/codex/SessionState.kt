package dev.rivo.relay.codex

import dev.rivo.relay.data.*

/** Content and execution belong to snapshot; synchronization never rewrites either. */
data class SessionState(
    val id: String? = null,
    val snapshot: SessionSnapshot? = null,
    val synchronization: SessionSynchronization = SessionSynchronization.confirmed(),
    val historyCursor: String? = null, val loadingEarlier: Boolean = false, val earlierLoaded: Boolean = false,
    val historyError: String? = null,
    val pendingMessages: List<PendingNativeMessage> = emptyList(), val overlayRevision: Long = 0L,
    val lastConfirmedControl: SessionControl? = null, val mediaValidationEpoch: Long = 0,
) {
    // Read-only compatibility projections. There is no independently writable stale/loading flag.
    val stale get() = synchronization.verification != SessionVerification.CONFIRMED
    val loading get() = synchronization.read == SessionRead.OPENING
    val readError get() = synchronization.error
    val controlNotice get() = synchronization.notice
    val offline get() = synchronization.connection == SessionConnection.OFFLINE

    fun transition(event: SessionSyncEvent) = copy(synchronization = synchronization.reduce(event))
}

enum class SessionConnection { ONLINE, RECONNECTING, OFFLINE }
enum class SessionVerification { REQUIRED, CONFIRMED }
enum class SessionRead { IDLE, OPENING, REFRESHING, SLOW, FAILED }

sealed interface SessionSyncEvent {
    data class Open(val online: Boolean) : SessionSyncEvent
    data object ReadStarted : SessionSyncEvent
    data object ReadSlow : SessionSyncEvent
    data object ReadSucceeded : SessionSyncEvent
    data class ReadFailed(val message: String?) : SessionSyncEvent
    data class Revalidate(val notice: String) : SessionSyncEvent
    data object StreamInterrupted : SessionSyncEvent
    data object HistoryInvalidated : SessionSyncEvent
    data object RouteUnavailable : SessionSyncEvent
    data object Pause : SessionSyncEvent
    data object DismissError : SessionSyncEvent
}

/** All synchronization transitions live here; callers fence obsolete async results before reducing. */
data class SessionSynchronization private constructor(
    val connection: SessionConnection,
    val verification: SessionVerification,
    val read: SessionRead,
    val error: String? = null,
    val notice: String? = null,
) {
    companion object {
        // Explicit snapshots supplied by callers are confirmed; controller opens always reduce Open first.
        fun confirmed() = SessionSynchronization(SessionConnection.ONLINE, SessionVerification.CONFIRMED, SessionRead.IDLE)
        fun opening(online: Boolean = true) = confirmed().reduce(SessionSyncEvent.Open(online))
        fun pending() = confirmed().reduce(SessionSyncEvent.Revalidate("正在重新确认会话权限"))
    }

    fun reduce(event: SessionSyncEvent): SessionSynchronization = when (event) {
        is SessionSyncEvent.Open -> if (event.online) SessionSynchronization(
            SessionConnection.RECONNECTING, SessionVerification.REQUIRED, SessionRead.OPENING)
            else reduce(SessionSyncEvent.RouteUnavailable)
        SessionSyncEvent.RouteUnavailable -> SessionSynchronization(SessionConnection.OFFLINE,
            SessionVerification.REQUIRED, SessionRead.IDLE, notice = "网络不可用，恢复连接并同步后才能操作")
        SessionSyncEvent.ReadStarted -> copy(read = if (read == SessionRead.OPENING) read else SessionRead.REFRESHING)
        SessionSyncEvent.ReadSlow -> copy(verification = SessionVerification.REQUIRED, read = SessionRead.SLOW,
            notice = "服务器响应较慢，暂时禁止操作，等待同步确认")
        SessionSyncEvent.ReadSucceeded -> confirmed()
        is SessionSyncEvent.ReadFailed -> copy(verification = SessionVerification.REQUIRED, read = SessionRead.FAILED,
            error = event.message, notice = "当前会话同步失败，暂时禁止操作，请重试或切换会话")
        is SessionSyncEvent.Revalidate -> copy(verification = SessionVerification.REQUIRED, notice = event.notice)
        SessionSyncEvent.StreamInterrupted -> copy(connection = SessionConnection.RECONNECTING,
            verification = SessionVerification.REQUIRED, notice = "连接已中断，正在重新确认操作权限")
        SessionSyncEvent.HistoryInvalidated -> copy(verification = SessionVerification.REQUIRED, read = SessionRead.OPENING)
        SessionSyncEvent.Pause -> copy(verification = SessionVerification.REQUIRED, read = SessionRead.IDLE)
        SessionSyncEvent.DismissError -> copy(error = null)
    }
}

fun SessionState.syncStatusLabel(): String? = when {
    offline -> if (snapshot != null) "离线缓存" else "连接中断"
    readError != null -> "同步失败，正在重试"
    loading || stale -> "同步中"
    snapshot?.requests?.isNotEmpty() == true -> "等待回应"
    snapshot?.status == "waiting" -> "等待回应，请在原会话处理"
    snapshot?.isOngoing == true -> "执行中"
    else -> null
}
