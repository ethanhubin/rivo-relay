package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import dev.rivo.relay.ui.theme.ThemeMode
import dev.rivo.relay.ui.theme.rememberThemePreference
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
import dev.rivo.relay.data.RELEASES_PAGE
import android.text.format.Formatter

@Composable
fun SettingsScreen(nodeName: String? = null, nodeAddress: String? = null,
    checkUpdate: suspend () -> AppRelease? = { NodeAppUpdates().check(BuildConfig.VERSION_NAME) },
    downloadAndInstall: (suspend (AppRelease) -> Unit)? = null,
    updates: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val (themeMode, setThemeMode) = rememberThemePreference()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val downloading = updates.downloading
    val downloadedBytes = updates.downloadedBytes
    val bytesPerSecond = updates.bytesPerSecond
    val checking = updates.checking
    val checked = updates.checked
    val release = updates.release
    val error = updates.error
    var notesOpen by remember { mutableStateOf(false) }
    fun open(url: String) {
        try { uriHandler.openUri(url) } catch (_: Exception) { updates.error = "无法打开浏览器，请安装或启用浏览器后重试" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).selectableGroup()) {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("theme_${mode.name.lowercase()}")
                            .selectable(selected = themeMode == mode, role = Role.RadioButton,
                                onClick = { setThemeMode(mode) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(mode.label)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text("Rivo Relay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("默认从 Tailscale 节点检查更新和下载安装包，校验通过后由系统确认安装。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    updates.check(checkUpdate)
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
                        updates.download(context.applicationContext, latest, downloadAndInstall)
                    }, enabled = !downloading, modifier = Modifier.testTag("download_update")) { Text(if (downloading) "下载并校验中…" else if (updates.readyFile != null) "安装已下载的更新" else "下载并安装") }
                    if (downloading) {
                        val fraction = if (latest.size > 0) (downloadedBytes.toFloat() / latest.size).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("update_download_progress"))
                        Text(if (latest.size > 0 && downloadedBytes >= latest.size) "下载完成，正在校验安装包…"
                            else "${(fraction * 100).toInt()}% · ${Formatter.formatFileSize(context, downloadedBytes)} / ${Formatter.formatFileSize(context, latest.size.coerceAtLeast(0))} · ${Formatter.formatFileSize(context, bytesPerSecond)}/s",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("update_download_status"))
                    }
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
