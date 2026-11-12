package dev.rivo.relay.codex

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.rivo.relay.MainActivity
import dev.rivo.relay.R
import dev.rivo.relay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.json.JSONArray

internal const val PINNED_COMPLETION = "pinnedCompletion"
internal const val PINNED_SOUND = "pinnedCompletionSound"
internal const val PINNED_VIBRATION = "pinnedCompletionVibration"
internal fun notificationPreferences(context: Context) = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

internal fun mergePinnedObservations(previous: Set<String>, incoming: Set<String>, complete: Boolean): Set<String> =
    if (complete) incoming else previous + incoming

/** Bounded verification for expected turns that have fallen outside a small head. */
internal suspend fun fillObservedTurns(head: JSONObject, expected: Set<String>, readPage: suspend (String, String) -> JSONObject): JSONObject {
    val revision = head.string("nativeRevision").takeIf(String::isNotBlank) ?: return head
    val thread = head.optJSONObject("thread") ?: return head
    val turns = thread.objects("turns").toMutableList()
    var cursor = head.string("nextCursor")
    val cursors = mutableSetOf<String>()
    repeat(3) {
        if (expected.all { id -> turns.any { it.string("id") == id } } || cursor.isBlank() || !cursors.add(cursor)) return@repeat
        val page = readPage(cursor, revision)
        val sync = page.optJSONObject("nativeSync")
        if (page.string("nativeRevision") != revision || sync != null && sync.string("state") != "ready" && !sync.optBoolean("historyReady"))
            throw ApiException(409, "history_changed", "历史已变化，继续核对任务结果")
        val known = turns.mapTo(hashSetOf()) { it.string("id") }
        turns.addAll(0, page.objects("turns").filter { it.string("id") !in known })
        cursor = page.string("nextCursor")
    }
    fun shallow(value: JSONObject) = JSONObject().also { copy -> value.keys().forEach { key -> copy.put(key, value.opt(key)) } }
    return shallow(head).put("thread", shallow(thread).put("turns", JSONArray(turns)))
}

internal data class TaskNotice(val key: String, val kind: String)

/** The initial read is a baseline, never a completion event. Only authoritative subsequent reads count. */
internal class TaskChangeTracker {
    private var initialized = false
    private val known = linkedSetOf<String>()
    private val running = linkedSetOf<String>()
    private val announced = linkedSetOf<String>()
    private val settled = linkedSetOf<String>()
    private var requests = emptySet<String>()
    private var goalState: String? = null
    private val goalOwnedTurns = linkedSetOf<String>()
    private data class Terminal(val status: String, val receivedNanos: Long)
    private val terminals = linkedMapOf<String, Terminal>()
    val hasPendingTerminals: Boolean get() = terminals.isNotEmpty()
    val hasUnresolvedTurns: Boolean get() = running.isNotEmpty()
    val unresolvedTurns: Set<String> get() = running.toSet()
    fun missingFromHead(json: JSONObject): Boolean = running.any { id -> json.optJSONObject("thread")?.objects("turns").orEmpty().none { it.string("id") == id } }
    fun expectTurn(id: String) { if (id.isNotBlank()) running += id }
    fun observeTurnEvent(method: String, turn: JSONObject, receivedNanos: Long): Boolean {
        val id = turn.string("id")
        if (id.isBlank()) return false
        if (method == "turn/started" && turn.string("status") == "inProgress" && id !in settled) {
            running += id
            return true
        }
        val status = turn.string("status")
        if (method != "turn/completed" || id !in running || status !in setOf("completed", "failed", "interrupted", "canceled", "cancelled")) return false
        terminals[id] = Terminal(status, receivedNanos)
        return true
    }
    fun observe(json: JSONObject, freshAfterNanos: Long = Long.MIN_VALUE): List<TaskNotice> {
        val confirmed = terminals.filterValues { it.receivedNanos <= freshAfterNanos }
        // Explicit terminal evidence can have scrolled out of the two-turn head.
        // Resolve it only with goal/control read after that event, never an old in-flight snapshot.
        val turns = confirmed.map { (id, terminal) -> JSONObject().put("id", id).put("status", terminal.status) } +
            json.optJSONObject("thread")?.objects("turns").orEmpty().filterNot { it.string("id") in confirmed }
        val nextRequests = json.objects("requests").map { it.string("key") }.filter(String::isNotBlank).toSet()
        val result = mutableListOf<TaskNotice>()
        val goal = json.optJSONObject("goal")
        val nextGoalState = goal?.let { "${it.optLong("createdAt")}:${it.string("status")}" }
        if (initialized && nextGoalState != goalState && goal != null) {
            val kind = when (goal.string("status")) {
                "complete" -> "completed"
                "blocked", "usageLimited", "budgetLimited" -> "goalStopped"
                else -> null
            }
            if (kind != null) result += TaskNotice("goal:$nextGoalState:${goal.optLong("updatedAt")}", kind)
        }
        val goalOwnsCompletion = goal?.string("status") == "active" || goalState?.endsWith(":active") == true ||
            (nextGoalState != goalState && result.any { it.key.startsWith("goal:") })
        if (goal?.string("status") == "active" || goalState?.endsWith(":active") == true) {
            goalOwnedTurns += running
            turns.filter { it.string("status") == "inProgress" }.mapTo(goalOwnedTurns) { it.string("id") }
            json.string("activeTurnId").takeIf(String::isNotBlank)?.let { goalOwnedTurns += it }
        }
        goalState = nextGoalState
        if (initialized || running.isNotEmpty()) (nextRequests - requests).forEach { result += TaskNotice("request:$it", "waiting") }
        val overlap = turns.any { it.string("id") in known }
        val latest = turns.lastOrNull()?.string("id")
        turns.forEach { turn ->
            val id = turn.string("id")
            val status = turn.string("status")
            if (id.isNotBlank()) {
                if (status == "inProgress") { running += id; settled -= id }
                else if (status in setOf("completed", "failed")) {
                    if (((!goalOwnsCompletion && id !in goalOwnedTurns) || status == "failed") && (id in running || (initialized && overlap && id == latest && id !in known)))
                        result += TaskNotice("turn:$id:$status", if (status == "failed") "failed" else "completed")
                    running -= id; goalOwnedTurns -= id; settled += id
                } else if (status in setOf("interrupted", "canceled", "cancelled")) { running -= id; goalOwnedTurns -= id; settled += id }
                known += id
            }
        }
        json.string("activeTurnId").takeIf(String::isNotBlank)?.let { running += it }
        confirmed.keys.forEach { terminals.remove(it) }
        initialized = true
        requests = nextRequests
        while (settled.size > 256) settled.remove(settled.first())
        while (goalOwnedTurns.size > 256) goalOwnedTurns.remove(goalOwnedTurns.first())
        while (known.size > 256) known.remove(known.first())
        return result.filter { announced.add(it.key) }.also { while (announced.size > 256) announced.remove(announced.first()) }
    }
}

data class TaskNotificationTarget(val base: String, val threadId: String) {
    companion object {
        private const val EXTRA_BASE = "relay.notification.node"
        private const val EXTRA_THREAD = "relay.notification.thread"
        fun from(intent: Intent?): TaskNotificationTarget? {
            val base = intent?.getStringExtra(EXTRA_BASE)?.takeIf { it.isNotBlank() } ?: return null
            val id = intent.getStringExtra(EXTRA_THREAD)?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            return TaskNotificationTarget(base, id)
        }
    }
    fun intent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction("dev.rivo.relay.OPEN_TASK")
        .setData(Uri.Builder().scheme("rivo-relay").authority("task").appendQueryParameter("node", base).appendQueryParameter("thread", threadId).build())
        .putExtra(EXTRA_BASE, base).putExtra(EXTRA_THREAD, threadId)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Live process subscription, not a push service: Android may suspend/kill it in the background. */
class TaskNotifications(private val context: Context, private val api: RelayApi, private val scope: CoroutineScope,
    private val nodeBase: String, var nodeName: String, private val requestPermission: () -> Unit,
    private val failure: (Throwable) -> Unit, private val sync: NodeSessionSync? = null) {
    private data class Watch(val tracker: TaskChangeTracker, val job: Job, val signal: Channel<Unit>, var busy: Boolean = false, var lastReadNanos: Long = 0, var regular: Boolean = true, var needsHistoryCheck: Boolean = false, var lastHistoryCheckNanos: Long = 0)
    private val eventSource = sync ?: NodeSessionSync(api, scope)
    private val watches = linkedMapOf<String, Watch>()
    private val pinnedByProject = mutableMapOf<String, Set<String>>()
    private val preferences = notificationPreferences(context)

    fun updatePinned(projectId: String, ids: Set<String>, complete: Boolean = true) {
        val next = mergePinnedObservations(pinnedByProject[projectId].orEmpty(), ids, complete)
        val removed = pinnedByProject.put(projectId, next).orEmpty() - next
        removed.forEach { id ->
            val watch = watches[id] ?: return@forEach
            if (!watch.regular && !isPinned(id)) {
                watches.remove(id)
                watch.job.cancel()
                watch.signal.close()
            }
        }
        ids.forEach { watch(it, pinnedOnly = true) }
    }

    private fun isPinned(id: String) = pinnedByProject.values.any { id in it }

    private var visibleThread: String? = null
    private var streamJob: Job? = null
    private val manager = NotificationManagerCompat.from(context)
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("codex_tasks", "Codex 任务进度", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "已观察会话的完成、失败和待回应通知；应用运行并连接节点时可用"
            })
    }
    fun setVisibleThread(id: String?) {
        val previous = visibleThread
        visibleThread = id
        if (previous != null && previous != id) watches[previous]?.signal?.trySend(Unit)
        if (id != null) {
            manager.cancel(tag(id), 1)
            watch(id)
        }
    }
    fun watch(id: String, requestPermission: Boolean = false, turnId: String? = null, pinnedOnly: Boolean = false) {
        if (id.isBlank()) return
        if (requestPermission) this.requestPermission()
        val existing = watches[id]
        if (existing != null) {
            watches.remove(id); watches[id] = existing
            existing.regular = existing.regular || !pinnedOnly
            turnId?.let { existing.tracker.expectTurn(it); existing.busy = true }
            if (requestPermission) existing.busy = true
            if (!pinnedOnly || requestPermission || turnId != null) existing.signal.trySend(Unit)
            return
        }
        val tracker = TaskChangeTracker().also { turnId?.let(it::expectTurn) }
        val signals = Channel<Unit>(Channel.CONFLATED)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            launch {
                while (isActive) {
                    delay(8_000)
                    val current = watches[id]
                    if (current?.busy == true || current != null && System.nanoTime() - current.lastReadNanos >= 300_000_000_000L)
                        signals.trySend(Unit)
                }
            }
            signals.trySend(Unit)
            for (ignored in signals) {
                // The visible controller supplies authoritative snapshots through observe().
                // Resume independent observation immediately when that controller pauses.
                val watch = watches[id] ?: continue
                val historyCheck = watch.needsHistoryCheck
                if (visibleThread == id && !tracker.hasPendingTerminals && !historyCheck) continue
                val terminalRead = tracker.hasPendingTerminals
                val readStarted = System.nanoTime()
                val requireFresh = terminalRead || historyCheck
                if (historyCheck) { watch.needsHistoryCheck = false; watch.lastHistoryCheckNanos = readStarted }
                try { observe(id, withTimeout(JSON_READ_TIMEOUT_MILLIS) {
                    val limit = if (historyCheck) 30 else 2
                    val head = sync?.read(id, freshAfterNanos = readStarted.takeIf { requireFresh }, limit = limit)
                        ?: api.call("/v1/threads/${encoded(id)}?limit=$limit")
                    if (historyCheck) fillObservedTurns(head, tracker.unresolvedTurns) { cursor, revision ->
                        api.call("/v1/threads/${encoded(id)}/history?cursor=${encoded(cursor)}&limit=10&nativeRevision=${encoded(revision)}", background = true)
                    } else head
                }, freshAfterNanos = if (requireFresh) readStarted else Long.MIN_VALUE) }
                catch (e: Exception) {
                    if (historyCheck) watch.needsHistoryCheck = true
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    if (e is ApiException && e.status == 401) failure(e)
                }
                delay(350)
            }
        }
        watches[id] = Watch(tracker, job, signals, busy = !turnId.isNullOrBlank(), regular = !pinnedOnly)
        // Bound read/cache state. All watched conversations share one node-level event stream.
        while (watches.size > 12) {
            val candidates = watches.keys.filter { it != visibleThread && it != id }
            val oldest = candidates.firstOrNull { !isPinned(it) } ?: candidates.firstOrNull() ?: break
            watches.remove(oldest)?.let { it.job.cancel(); it.signal.close() }
        }
        job.start()
        ensureSharedStream()
    }
    private fun ensureSharedStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            // The node transport owns reconnection; keep this subscriber and its cursor alive.
            eventSource.events(onInterrupted = { error ->
                if (error is ApiException && error.status == 401) failure(error)
            }).collect { event ->
                if (event.type == "revoked") {
                    failure(ApiException(401, "revoked", "节点授权已失效"))
                    return@collect
                }
                val method = event.payload.string("method")
                if (method == "relay/githubAlert") return@collect
                // Text/tool output deltas cannot complete a turn or create a user request.
                if (event.type == "native" && method.startsWith("item/") && !event.payload.has("id")) return@collect
                val params = event.payload.optJSONObject("params")
                val targets = notificationRefreshTargets(event)
                if (targets == null) watches.values.forEach {
                    if (event.type in setOf("reset", "connected") && it.tracker.hasUnresolvedTurns) it.needsHistoryCheck = true
                    it.signal.trySend(Unit)
                }
                else targets.forEach { id -> watches[id]?.let { watch ->
                    val turn = params?.optJSONObject("turn")
                    if (turn != null && watch.tracker.observeTurnEvent(method, turn, System.nanoTime())) watch.busy = true
                    watch.signal.trySend(Unit)
                } }
            }
        }
    }

    fun observe(id: String, json: JSONObject) = observe(id, json, Long.MIN_VALUE)

    private fun observe(id: String, json: JSONObject, freshAfterNanos: Long) {
        val watch = watches[id] ?: return
        if (json.optJSONObject("thread")?.string("id") != id) return
        watch.lastReadNanos = System.nanoTime()
        val notices = watch.tracker.observe(json, freshAfterNanos)
        watch.busy = watch.tracker.hasUnresolvedTurns || watch.tracker.hasPendingTerminals || json.optJSONObject("goal")?.string("status") == "active" || json.string("activeTurnId").isNotBlank() || json.string("status") in setOf("running", "waiting") ||
            json.objects("requests").isNotEmpty() || json.objects("queue").any { it.string("status") in setOf("queued", "pending", "waiting", "running") }
        if (watch.tracker.missingFromHead(json) && System.nanoTime() - watch.lastHistoryCheckNanos >= 8_000_000_000L) {
            watch.needsHistoryCheck = true
            watch.signal.trySend(Unit)
        }
        val title = sessionTitle(json.getJSONObject("thread"))
        notices.forEach { notice ->
            if (notice.kind == "completed" && isPinned(id)) {
                if (preferences.getBoolean(PINNED_COMPLETION, false)) notify(id, title, notice, pinned = true)
            } else if (watch.regular && visibleThread != id) {
                notify(id, title, notice)
            }
        }
    }
    private fun tag(id: String) = "relay:$nodeBase:$id"
    private fun pinnedChannel(): String {
        val sound = preferences.getBoolean(PINNED_SOUND, true)
        val vibration = preferences.getBoolean(PINNED_VIBRATION, true)
        val channelId = "pinned_completion_${sound}_${vibration}"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "置顶任务完成", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(if (sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                    android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT)
                enableVibration(vibration)
            })
        return channelId
    }

    private fun notify(id: String, title: String, notice: TaskNotice, pinned: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = if (pinned) "置顶任务已完成" else when (notice.kind) { "waiting" -> "Codex 需要你的回应"; "failed" -> "Codex 任务失败，请打开会话查看"; "goalStopped" -> "Codex 目标受阻或达到限制，请打开会话查看"; else -> "Codex 任务已完成" }
        val pending = PendingIntent.getActivity(context, 0, TaskNotificationTarget(nodeBase, id).intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, if (pinned) pinnedChannel() else "codex_tasks").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.take(120)).setContentText(text).setSubText(nodeName).setContentIntent(pending)
            .setAutoCancel(true).setOnlyAlertOnce(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        try { manager.notify(tag(id), 1, notification) } catch (_: SecurityException) { /* User may revoke between check and post. */ }
    }
}
