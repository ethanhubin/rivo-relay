package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun LoginUpdatePrompt(updates: UpdateViewModel) {
    val latest = updates.release ?: return
    if (!updates.promptVisible) return
    val context = LocalContext.current.applicationContext
    AlertDialog(
        onDismissRequest = updates::dismissPrompt,
        title = { Text("发现新版本 ${latest.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(latest.notes.ifBlank { "新版本已就绪，点击立即更新即可下载并安装。" })
                if (updates.downloading) {
                    val fraction = (updates.downloadedBytes.toFloat() / latest.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(if (updates.downloadedBytes >= latest.size) "正在校验安装包…" else "正在下载 ${(fraction * 100).toInt()}%")
                }
                updates.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { updates.download(context, latest) }, enabled = !updates.downloading,
                modifier = Modifier.testTag("login_update_now")) {
                Text(if (updates.downloading) "下载并校验中…" else if (updates.readyFile != null) "安装更新" else "立即更新")
            }
        },
        dismissButton = { TextButton(onClick = updates::dismissPrompt) { Text(if (updates.downloading) "后台下载" else "稍后再说") } },
    )
}
