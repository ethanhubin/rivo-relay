package dev.rivo.relay.ui.settings

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.data.RelayEvent
import dev.rivo.relay.data.string
import dev.rivo.relay.github.GithubAlertJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GithubWatchSettings(api: RelayApi, onBack: () -> Unit, canReviewPr: Boolean,
    onReviewPr: (String) -> Unit, events: Flow<RelayEvent>? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
    }
    var watches by remember(api) { mutableStateOf(JSONArray()) }
    var auth by remember(api) { mutableStateOf(JSONObject()) }
    var repository by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var flow by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        val result = api.call("/v1/github/watches")
        watches = result.optJSONArray("watches") ?: JSONArray()
        auth = result.optJSONObject("auth") ?: JSONObject()
    }
    LaunchedEffect(api) {
        try { refresh() } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "无法读取 GitHub 监听"
        }
    }
    LaunchedEffect(events) {
        events?.collect { event ->
            if (event.type in setOf("connected", "reset") || event.payload.string("method") == "relay/githubAlert") {
                try { refresh() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message ?: "无法刷新 GitHub 监听"
                }
            }
        }
    }
    LaunchedEffect(flow?.string("flowId")) {
        val current = flow ?: return@LaunchedEffect
        val id = current.string("flowId")
        if (id.isBlank()) return@LaunchedEffect
        var interval = current.optLong("interval", 5)
        while (true) {
            delay((interval * 1000).coerceAtLeast(5000))
            try {
                val result = api.call("/v1/github/auth/flows/$id")
                if (!result.optBoolean("pending", true)) {
                    flow = null
                    refresh()
                    break
                } else {
                    interval = result.optLong("interval", 5)
                    flow = JSONObject(current.toString()).put("interval", interval)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                flow = null
                error = e.message ?: "GitHub 授权失败"
                break
            }
        }
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "GitHub 操作失败"
            } finally { busy = false }
        }
    }
    Scaffold(modifier = Modifier.testTag("github_watches_page"), topBar = {
        TopAppBar(title = { Text("GitHub PR 监听") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("节点每约 5 分钟检查目标分支；连接 GitHub 且 App 在前台在线时，节点约每 30 秒加速检查并通过实时连接提醒。首次添加不提醒历史 PR。App 关闭后由 Android 后台任务补收，系统可能延迟调度。", style = MaterialTheme.typography.bodyMedium)
            if (!notificationsAllowed) {
                TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("允许通知以接收后台提醒")
                }
            }
            if (auth.optBoolean("connected")) {
                Text("已连接 GitHub：${auth.string("login")}")
                TextButton(onClick = { action { api.call("/v1/github/auth", "DELETE"); refresh() } }, enabled = !busy) {
                    Text("从节点移除 GitHub 授权")
                }
            } else if (auth.optBoolean("available")) {
                Button(onClick = { action { flow = api.call("/v1/github/auth/start", "POST") } }, enabled = !busy) {
                    Text("连接 GitHub（私有仓库需要）")
                }
            } else {
                Text("节点尚未配置 GitHub App；公开仓库可直接监听，私有仓库需要节点管理员配置授权。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            flow?.let { pending ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("在 GitHub 输入授权码 ${pending.string("userCode")}", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.string("verificationUri"))))
                        }) { Text("打开 GitHub 授权页") }
                        Text("授权后会自动返回本页。请在 GitHub App 安装设置中允许访问要监听的私有仓库。")
                    }
                }
            }
            HorizontalDivider()
            OutlinedTextField(repository, { repository = it }, label = { Text("仓库，例如 owner/repo") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_repository"))
            OutlinedTextField(branch, { branch = it }, label = { Text("PR 目标分支") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("github_branch"))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                action {
                    api.call("/v1/github/watches", "POST", JSONObject().put("repository", repository.trim()).put("branch", branch.trim()))
                    repository = ""
                    refresh()
                    GithubAlertJob.schedule(context)
                    GithubAlertJob.checkSoon(context)
                }
            }, enabled = !busy && repository.isNotBlank() && branch.isNotBlank()) { Text("添加监听") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            (0 until watches.length()).mapNotNull { watches.optJSONObject(it) }.forEach { watch ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${watch.string("repository")} → ${watch.string("branch")}", style = MaterialTheme.typography.titleMedium)
                        val checked = watch.optDouble("checked_at", 0.0)
                        Text(if (!watch.optBoolean("initialized")) "正在建立初始列表…" else if (checked > 0)
                            "上次检查：${DateFormat.getDateTimeInstance().format(Date((checked * 1000).toLong()))}" else "等待检查",
                            style = MaterialTheme.typography.bodySmall)
                        watch.string("error").takeIf { it.isNotBlank() }?.let { code ->
                            val detail = when (code) {
                                "github_not_found", "github_unauthorized", "github_forbidden" -> "无法访问仓库；请检查 GitHub 授权和 GitHub App 的仓库安装范围"
                                "github_authorization_expired" -> "GitHub 授权已过期，请重新连接"
                                "github_rate_limited" -> "GitHub 请求额度已用尽，稍后重试"
                                "github_unavailable" -> "暂时无法连接 GitHub"
                                else -> "GitHub 返回异常（$code）"
                            }
                            Text("检查失败：$detail", color = MaterialTheme.colorScheme.error)
                        }
                        val pending = watch.optJSONArray("pending") ?: JSONArray()
                        (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }.forEach { pr ->
                            Text("#${pr.optInt("number")} ${pr.string("title")}")
                            Row {
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pr.string("url"))))
                                }) { Text("打开 PR") }
                                TextButton(onClick = { onReviewPr(pr.string("url")) }, enabled = canReviewPr) { Text("交给 Codex 审核") }
                            }
                        }
                        if (!canReviewPr && pending.length() > 0) Text("切换到此节点后可在 Codex 中审核", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { action {
                            api.call("/v1/github/watches/${watch.string("id")}", "DELETE")
                            refresh()
                        } }, enabled = !busy) { Text("删除监听") }
                    }
                }
            }
        }
    }
}
