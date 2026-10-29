package dev.rivo.relay.ui.todos

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.rivo.relay.MainActivity
import dev.rivo.relay.data.Todo
import dev.rivo.relay.data.string
import org.json.JSONObject
import java.security.MessageDigest

/** User-requested reminders are local inexact alarms; no background Codex execution or exact-alarm access. */
class TodoReminderScheduler(private val context: Context, nodeIdentity: String, private val nodeBase: String = "") {
    private val nodeKey = MessageDigest.getInstance("SHA-256").digest(nodeIdentity.toByteArray()).joinToString("") { "%02x".format(it) }

    fun update(items: List<Todo>) = synchronized(reminderLock) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val records = runCatching { JSONObject(prefs.getString("records", "{}")!!) }.getOrDefault(JSONObject())
        val wanted = items.filter { !it.completed && it.details.optLong("reminderAt") > System.currentTimeMillis() }
            .associateBy { "$nodeKey:${it.id}" }
        records.keys().asSequence().toList().filter { it.startsWith("$nodeKey:") && it !in wanted }.forEach { key ->
            cancel(context, key); records.remove(key)
        }
        wanted.forEach { (key, item) ->
            val whenMillis = item.details.optLong("reminderAt")
            val previous = records.optJSONObject(key)
            val record = JSONObject().put("title", item.title).put("todoId", item.id).put("when", whenMillis).put("nodeBase", nodeBase)
            if (previous?.optLong("when") != whenMillis || previous.stringOrEmpty("title") != item.title) {
                records.put(key, record)
                schedule(context, key, whenMillis)
            }
        }
        prefs.edit().putString("records", records.toString()).apply()
    }

    companion object {
        internal const val PREFERENCES = "rivo-todo-reminders"
        internal const val CHANNEL = "rivo_todo_reminders"
        internal val reminderLock = Any()
        internal fun pending(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, TodoReminderReceiver::class.java).setAction("dev.rivo.relay.TODO_REMINDER")
                .setData(Uri.parse("rivo://todo-reminder/${Uri.encode(key)}")).putExtra("key", key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal fun schedule(context: Context, key: String, whenMillis: Long) {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                maxOf(System.currentTimeMillis() + 1000, whenMillis), pending(context, key))
        }
        private fun cancel(context: Context, key: String) { context.getSystemService(AlarmManager::class.java).cancel(pending(context, key)) }
    }
}

private fun JSONObject?.stringOrEmpty(key: String) = this?.string(key).orEmpty()

class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = synchronized(TodoReminderScheduler.reminderLock) {
        val preferences = context.getSharedPreferences(TodoReminderScheduler.PREFERENCES, Context.MODE_PRIVATE)
        val records = runCatching { JSONObject(preferences.getString("records", "{}")!!) }.getOrDefault(JSONObject())
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            records.keys().asSequence().toList().forEach { key ->
                records.optJSONObject(key)?.let { TodoReminderScheduler.schedule(context, key, it.optLong("when")) }
            }
            return@synchronized
        }
        val key = intent.getStringExtra("key") ?: return@synchronized
        val record = records.optJSONObject(key) ?: return@synchronized
        records.remove(key)
        preferences.edit().putString("records", records.toString()).apply()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@synchronized
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TodoReminderScheduler.CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, key.hashCode(), Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openTodos", true)
            .putExtra("todoNodeBase", record.string("nodeBase")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("todo:$key", 1, NotificationCompat.Builder(context, TodoReminderScheduler.CHANNEL)
            .setSmallIcon(dev.rivo.relay.R.drawable.ic_launcher).setContentTitle(record.string("title"))
            .setContentText("待办提醒").setAutoCancel(true).setContentIntent(open).build())
    }
}
