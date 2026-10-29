package dev.rivo.relay.codex

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

internal data class TaskNotice(val key: String, val kind: String)

/** The initial read is a baseline, never a completion event. Only authoritative subsequent reads count. */
internal class TaskChangeTracker {
    private var initialized = false
    private val known = linkedSetOf<String>()
    private val running = linkedSetOf<String>()
    private val announced = linkedSetOf<String>()
    private var requests = emptySet<String>()
    fun expectTurn(id: String) { if (id.isNotBlank()) running += id }
    fun observe(json: JSONObject): List<TaskNotice> {
        val turns = json.optJSONObject("thread")?.objects("turns").orEmpty()
        val nextRequests = json.objects("requests").map { it.string("key") }.filter(String::isNotBlank).toSet()
        val result = mutableListOf<TaskNotice>()
        if (initialized || running.isNotEmpty()) (nextRequests - requests).forEach { result += TaskNotice("request:$it", "waiting") }
        val overlap = turns.any { it.string("id") in known }
        val latest = turns.lastOrNull()?.string("id")
        turns.forEach { turn ->
            val id = turn.string("id")
            val status = turn.string("status")
            if (id.isNotBlank()) {
                if (status == "inProgress") running += id
                else if (status in setOf("completed", "failed")) {
                    if (id in running || (initialized && overlap && id == latest && id !in known))
                        result += TaskNotice("turn:$id:$status", if (status == "failed") "failed" else "completed")
                    running -= id
                } else if (status == "interrupted") running -= id
                known += id
            }
        }
        json.string("activeTurnId").takeIf(String::isNotBlank)?.let { running += it }
        initialized = true
        requests = nextRequests
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
    private val nodeBase: String, private val nodeName: String, private val requestPermission: () -> Unit,
    private val failure: (Throwable) -> Unit) {
    private data class Watch(val tracker: TaskChangeTracker, val job: Job, val signal: Channel<Unit>, var busy: Boolean = false, var lastReadNanos: Long = 0)
    private val watches = linkedMapOf<String, Watch>()
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
        visibleThread = id
        if (id != null) {
            manager.cancel(tag(id), 1)
            watch(id)
        }
    }
    fun watch(id: String, requestPermission: Boolean = false, turnId: String? = null) {
        if (id.isBlank()) return
        if (requestPermission) this.requestPermission()
        val existing = watches[id]
        if (existing != null) {
            watches.remove(id); watches[id] = existing
            turnId?.let { existing.tracker.expectTurn(it); existing.busy = true }
            if (requestPermission) existing.busy = true
            existing.signal.trySend(Unit)
            return
        }
        val tracker = TaskChangeTracker().also { turnId?.let(it::expectTurn) }
        val signals = Channel<Unit>(Channel.CONFLATED)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            launch {
                while (isActive) {
                    delay(8_000)
                    val current = watches[id]
                    if (current?.busy == true || current != null && System.nanoTime() - current.lastReadNanos >= 60_000_000_000L)
                        signals.trySend(Unit)
                }
            }
            signals.trySend(Unit)
            for (ignored in signals) {
                try { observe(id, api.call("/v1/threads/${encoded(id)}")) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.status == 401) failure(e)
                }
                delay(350)
            }
        }
        watches[id] = Watch(tracker, job, signals, busy = !turnId.isNullOrBlank())
        // Bound read/cache state. All watched conversations share one node-level event stream.
        while (watches.size > 12) {
            val oldest = watches.keys.firstOrNull { it != visibleThread && it != id } ?: break
            watches.remove(oldest)?.let { it.job.cancel(); it.signal.close() }
        }
        job.start()
        ensureSharedStream()
    }
    private fun ensureSharedStream() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            while (isActive) {
                try {
                    // Relay currently broadcasts the node event bus; one socket serves every watched thread.
                    api.events("").collect { event ->
                        if (event.type == "revoked") {
                            failure(ApiException(401, "revoked", "节点授权已失效"))
                            return@collect
                        }
                        val method = event.payload.string("method")
                        // Text/tool output deltas cannot complete a turn or create a user request.
                        if (event.type == "native" && method.startsWith("item/") && !event.payload.has("id")) return@collect
                        val params = event.payload.optJSONObject("params")
                        val id = params?.string("threadId").orEmpty().ifBlank { params?.string("conversationId").orEmpty() }
                        if (event.type in setOf("reset", "connected") || id.isBlank()) watches.values.forEach { it.signal.trySend(Unit) }
                        else watches[id]?.signal?.trySend(Unit)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.status == 401) failure(e)
                }
                delay(3_000)
            }
        }
    }
    fun observe(id: String, json: JSONObject) {
        val watch = watches[id] ?: return
        if (json.optJSONObject("thread")?.string("id") != id) return
        watch.lastReadNanos = System.nanoTime()
        watch.busy = json.string("activeTurnId").isNotBlank() || json.string("status") in setOf("running", "waiting") ||
            json.objects("requests").isNotEmpty() || json.objects("queue").any { it.string("status") in setOf("queued", "pending", "waiting", "running") }
        val title = visibleSessionText(json.getJSONObject("thread").string("name")).ifBlank { "Codex 会话" }
        watch.tracker.observe(json).forEach { notice -> if (visibleThread != id) notify(id, title, notice) }
    }
    private fun tag(id: String) = "relay:$nodeBase:$id"
    private fun notify(id: String, title: String, notice: TaskNotice) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = when (notice.kind) { "waiting" -> "Codex 需要你的回应"; "failed" -> "Codex 任务失败，请打开会话查看"; else -> "Codex 任务已完成" }
        val pending = PendingIntent.getActivity(context, 0, TaskNotificationTarget(nodeBase, id).intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "codex_tasks").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.take(120)).setContentText(text).setSubText(nodeName).setContentIntent(pending)
            .setAutoCancel(true).setOnlyAlertOnce(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        try { manager.notify(tag(id), 1, notification) } catch (_: SecurityException) { /* User may revoke between check and post. */ }
    }
}
