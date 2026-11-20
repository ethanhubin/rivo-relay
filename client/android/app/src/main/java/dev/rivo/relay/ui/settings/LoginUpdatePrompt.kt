package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.BuildConfig
import dev.rivo.relay.data.NodeAppUpdates
import dev.rivo.relay.data.UpdateCompatibilityChecker

@Composable
fun LoginUpdatePrompt(updates: UpdateViewModel) {
    val latest = updates.release ?: return
    if (!updates.promptVisible) return
    val context = LocalContext.current.applicationContext
    val declarationMissing = updates.compatibility?.candidateKnown == false
    val contentHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceAtMost(360.dp)
    AlertDialog(
        onDismissRequest = updates::dismissPrompt,
        title = { Text("发现新版本 ${latest.version}") },
        text = {
            Column(Modifier.fillMaxWidth().height(contentHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    updatePromptNotes(latest.notes).ifBlank { "新版本已就绪，点击立即更新即可下载并安装。" },
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                )
                if (updates.downloading) {
                    val fraction = (updates.downloadedBytes.toFloat() / latest.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(if (updates.downloadedBytes >= latest.size) "正在校验安装包…" else "正在下载 ${(fraction * 100).toInt()}%")
                }
                updates.compatibility?.takeIf { !it.allowed && it.summary != updates.error }?.let {
                    Text(it.summary, Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
                }
                updates.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (declarationMissing) updates.check(
                    { NodeAppUpdates.configured(context).check(BuildConfig.VERSION_NAME) }, prompt = true,
                    assess = { UpdateCompatibilityChecker.check(context, it) })
                else updates.download(context, latest)
            }, enabled = !updates.downloading && !updates.checking,
                modifier = Modifier.testTag("login_update_now")) {
                Text(if (declarationMissing) "重新检查更新" else if (updates.downloading) "下载并校验中…"
                    else if (updates.readyFile != null) "安装更新" else "立即更新")
            }
        },
        dismissButton = { TextButton(onClick = updates::dismissPrompt) { Text(if (updates.downloading) "后台下载" else "稍后再说") } },
    )
}

internal fun updatePromptNotes(notes: String): String = notes.lineSequence()
    .takeWhile { line ->
        !Regex("^(?:#{1,6}\\s*)?(?:\\*\\*)?验证(?:\\*\\*)?(?:[：:].*|\\s*)$").matches(line.trim())
    }
    .joinToString("\n")
    .trim()
