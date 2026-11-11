package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.BuildConfig
import dev.rivo.relay.data.AppRelease
import dev.rivo.relay.data.NodeAppUpdates
import androidx.compose.ui.platform.LocalContext
import android.text.format.Formatter

@Composable
fun SettingsScreen(
    connection: dev.rivo.relay.connection.NodeConnection? = null,
    api: dev.rivo.relay.data.RelayApi? = null,
    setConnectionMode: (dev.rivo.relay.connection.ConnectionMode) -> Unit = {},
    savedNodes: List<dev.rivo.relay.connection.SavedNode> = emptyList(),
    resolveConnection: (String) -> dev.rivo.relay.connection.NodeConnection? = { null },
    setNodeConnectionMode: ((String, dev.rivo.relay.connection.ConnectionMode) -> dev.rivo.relay.connection.NodeConnection?)? = null,
    onAddNode: () -> Unit = {},
    canAddNode: Boolean = true,
    checkUpdate: (suspend () -> AppRelease?)? = null,
    downloadAndInstall: (suspend (AppRelease) -> Unit)? = null,
    updates: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var speechEditorOpen by rememberSaveable { mutableStateOf(false) }
    var notificationEditorOpen by rememberSaveable { mutableStateOf(false) }
    val settingsScroll = rememberScrollState()
    var inspectedConnection by remember(connection?.base) { mutableStateOf(connection) }
    val inspectedApi = remember(inspectedConnection, api) {
        if (inspectedConnection?.base == connection?.base) api else inspectedConnection?.api()
    }
    val (themeMode, setThemeMode) = rememberThemePreference()
    val context = LocalContext.current
    val downloading = updates.downloading
    val downloadedBytes = updates.downloadedBytes
    val bytesPerSecond = updates.bytesPerSecond
    val checking = updates.checking
    val checked = updates.checked
    val release = updates.release
    val error = updates.error
    var notesOpen by remember { mutableStateOf(false) }
    if (notificationEditorOpen) {
        NotificationSettingsEditor(onBack = { notificationEditorOpen = false })
        return
    }
    if (speechEditorOpen) {
        SpeechSettingsEditor(onBack = { speechEditorOpen = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(settingsScroll).padding(24.dp).testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp)) {
                Text("明暗", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = themeMode == mode
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clip(shape)
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape))
                                .testTag("theme_${mode.name.lowercase()}")
                                .selectable(selected = selected, role = Role.RadioButton,
                                    onClick = { setThemeMode(mode) })
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(mode.label, style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        inspectedConnection?.let { selected ->
            key(selected) {
                ConnectionSettings(selected, inspectedApi, { mode ->
                    if (setNodeConnectionMode != null) {
                        setNodeConnectionMode(selected.base, mode)?.let { inspectedConnection = it }
                    } else setConnectionMode(mode)
                }, savedNodes, { base ->
                    resolveConnection(base)?.let { inspectedConnection = it }
                }, onAddNode, canAddNode)
            }
        }
        SettingsEntry("通知", dev.rivo.relay.R.drawable.ic_notifications, "edit_notification_settings") {
            notificationEditorOpen = true
        }
        SpeechSettings(onEdit = { speechEditorOpen = true })

        Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text("Rivo Relay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = {
                    updates.check(checkUpdate ?: { NodeAppUpdates.configured(context).check(BuildConfig.VERSION_NAME) })
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
            }
        }
    }
    if (notesOpen) release?.let { latest ->
        AlertDialog(onDismissRequest = { notesOpen = false }, title = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text("${latest.version} 更新说明") } },
            text = { dev.rivo.relay.ui.chat.ThemedTextContextMenu { Text(latest.notes.ifBlank { "此版本未提供更新说明" }, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { notesOpen = false }) { Text("完成") } })
    }
}
