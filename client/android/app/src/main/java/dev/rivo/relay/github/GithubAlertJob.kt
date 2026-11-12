package dev.rivo.relay.github

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.rivo.relay.R
import dev.rivo.relay.connection.CredentialStore
import dev.rivo.relay.connection.PairingRepository
import dev.rivo.relay.data.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Persistent OS job: catches up on node alerts even after the app process exits. */
class GithubAlertJob : JobService() {
    companion object {
        private const val JOB_ID = 0x5249564F
        private const val NOW_ID = 0x52495650
        private val checkMutex = Mutex()
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, GithubAlertJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build())
        }
        fun checkSoon(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(NOW_ID, ComponentName(context, GithubAlertJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .build())
        }
        suspend fun checkNow(context: Context, nodeKey: String? = null) = withContext(Dispatchers.IO) {
            checkMutex.withLock { checkAlerts(context.applicationContext, nodeKey) }
        }
    }

    private val running = ConcurrentHashMap<Int, CoroutineScope>()
    override fun onStartJob(params: JobParameters): Boolean {
        val run = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        running[params.jobId] = run
        run.launch {
            try { checkNow(applicationContext) } finally {
                if (running.remove(params.jobId, run)) jobFinished(params, false)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

}

private const val ALERT_CHANNEL = "github_pr_watches"

private suspend fun checkAlerts(context: Context, onlyNodeKey: String?) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val pairing = PairingRepository(CredentialStore(context))
    val preferences = context.getSharedPreferences("github_alert_cursors", Context.MODE_PRIVATE)
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "GitHub PR 监听", NotificationManager.IMPORTANCE_DEFAULT))
    for (saved in pairing.boundNodes().filter { it.authorized }) {
        val connection = pairing.connection(saved.base) ?: continue
        val key = "${connection.nodeId}:${connection.deviceId}"
        if (onlyNodeKey != null && key != onlyNodeKey) continue
        var after = preferences.getLong(key, 0)
        // Each page is durable before the next one. A failed node cannot
        // consume another node's alerts or erase the last successful cursor.
        for (page in 0 until 10) {
            val rows = try { connection.api().call("/v1/github/alerts?after=$after", background = true)
                .optJSONArray("alerts") ?: JSONArray() } catch (_: Exception) { break }
            if (rows.length() == 0) break
            for (index in 0 until rows.length()) {
                val alert = rows.optJSONObject(index) ?: continue
                val id = alert.optLong("id", 0)
                if (id <= after) continue
                show(context, manager, key, alert)
                if (!preferences.edit().putLong(key, id).commit()) return
                after = id
            }
            if (rows.length() < 100) break
        }
    }
}

private fun show(context: Context, manager: NotificationManager, nodeKey: String, alert: JSONObject) {
    val url = Uri.parse(alert.string("url"))
    if (url.scheme != "https" || url.host != "github.com") return
    val title = alert.string("repository") + " · #" + alert.optInt("number")
    val notificationKey = "$nodeKey:${alert.optLong("id")}"
    val open = PendingIntent.getActivity(context, notificationKey.hashCode(),
        Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(notificationKey, 1, NotificationCompat.Builder(context, ALERT_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(alert.string("title"))
        .setAutoCancel(true)
        .setContentIntent(open)
        .build())
}
