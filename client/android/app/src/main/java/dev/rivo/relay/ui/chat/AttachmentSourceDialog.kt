package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** QiGuang AttachmentPickerDialog, limited to Relay's existing local and node sources. */
@Composable
internal fun AttachmentSourceDialog(imageSlots: Int, fileSlots: Int, onDismiss: () -> Unit,
    onImage: () -> Unit, onFile: () -> Unit, onVideo: () -> Unit, onWorkspace: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(max = 720.dp).testTag("attachment_dialog"),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 12.dp) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加附件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("选择要发送到当前对话的内容", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, { tab = 0 }, modifier = Modifier.testTag("attachment_tab_local"), text = { Text("本地") })
                        Tab(tab == 1, { tab = 1 }, modifier = Modifier.testTag("attachment_tab_node"), text = { Text("节点") })
                    }
                    if (tab == 0) {
                        AttachmentSourceOption("图", "从相册选择图片", "可多选；还可添加 ${imageSlots.coerceAtLeast(0)} 张，支持裁剪与标注", "add_image", imageSlots > 0, onImage)
                        AttachmentSourceOption("文", "浏览文件", "可多选；上传至当前节点，单个最大 1 GiB", "add_file", fileSlots > 0, onFile)
                        AttachmentSourceOption("视", "选择视频", "作为文件上传至当前节点，单个最大 1 GiB", "add_video", fileSlots > 0, onVideo)
                    } else AttachmentSourceOption("工", "节点工作区文件", "浏览当前会话工作区并附加文件引用", "add_workspace_file", fileSlots > 0, onWorkspace)
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("attachment_cancel"), shape = RoundedCornerShape(16.dp)) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun AttachmentSourceOption(icon: String, title: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}
