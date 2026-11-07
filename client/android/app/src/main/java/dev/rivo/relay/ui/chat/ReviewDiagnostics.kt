package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.string
import org.json.JSONObject

@Composable
internal fun ReviewDiagnostic(diagnostic: JSONObject?) {
    if (diagnostic == null || diagnostic.string("status") == "verified") return
    val changed = diagnostic.optBoolean("readOnlyViolation") || diagnostic.string("status") == "changed"
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(if (changed) "review_workspace_warning" else "review_workspace_unverified"),
        color = if (changed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (changed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (changed) "审查期间工作区发生变化" else "未能核实审查期间的工作区状态", fontWeight = FontWeight.SemiBold)
            Text(diagnostic.string("message").ifBlank {
                if (changed) "请核对改动来源。现有改动已保留，未自动还原。" else "审查内容仍可查看，但无法确认工作区是否保持只读。"
            }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
