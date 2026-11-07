package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.BuildConfig
import dev.rivo.relay.data.AppRelease
import dev.rivo.relay.data.NodeAppUpdates
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.content.Intent
import java.io.File
import dev.rivo.relay.data.RELEASES_PAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nodeName: String? = null, nodeAddress: String? = null,
    checkUpdate: suspend () -> AppRelease? = { NodeAppUpdates().check(BuildConfig.VERSION_NAME) },
    downloadAndInstall: (suspend (AppRelease) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    fun open(url: String) {
        try { uriHandler.openUri(url) } catch (_: Exception) { error = "无法打开浏览器，请安装或启用浏览器后重试" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text("Rivo Relay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("默认从 Tailscale 节点检查更新和下载安装包，校验通过后由系统确认安装。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (!checking) {
                        checking = true; checked = false; release = null; error = null
                        scope.launch {
                            try { release = checkUpdate(); checked = true }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "检查更新失败，请重试" }
                            finally { checking = false }
                        }
                    }
                }, enabled = !checking && !downloading, modifier = Modifier.testTag("check_updates")) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (checking) "正在检查…" else "检查更新")
                }
                if (checked && release == null) Text("当前已是最新版本", Modifier.testTag("update_current"))
                release?.let { latest ->
                    Text("发现新版本 ${latest.version}" + if (latest.preview) "（测试版）" else "", Modifier.testTag("update_available"))
                    Button(onClick = {
                        if (!downloading) scope.launch {
                            downloading = true; error = null
                            try {
                                if (downloadAndInstall != null) { downloadAndInstall(latest); return@launch }
                                if (!context.packageManager.canRequestPackageInstalls()) {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        android.net.Uri.parse("package:${context.packageName}")))
                                    error = "请允许安装未知应用，然后再次点击下载并安装"
                                    return@launch
                                }
                                val file = NodeAppUpdates().download(latest, File(context.cacheDir, "downloads"))
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "下载安装包失败，请重试" }
                            finally { downloading = false }
                        }
                    }, enabled = !downloading, modifier = Modifier.testTag("download_update")) { Text(if (downloading) "下载并校验中…" else "下载并安装") }
                    TextButton(onClick = { notesOpen = true }) { Text("查看更新说明") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("update_error")) }
                TextButton(onClick = { open(RELEASES_PAGE) }) { Text("打开 GitHub 发布页") }
            }
        }
        if (nodeName != null) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前节点", style = MaterialTheme.typography.titleMedium)
                Text(nodeName)
                nodeAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("可在 Codex 页顶部菜单切换节点。连接凭据已加密保存在此设备。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (notesOpen) release?.let { latest ->
        AlertDialog(onDismissRequest = { notesOpen = false }, title = { Text("${latest.version} 更新说明") },
            text = { Text(latest.notes.ifBlank { "此版本未提供更新说明" }, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { notesOpen = false }) { Text("完成") } })
    }
}
