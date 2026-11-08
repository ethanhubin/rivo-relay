package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rivo.relay.connection.*
import dev.rivo.relay.data.RelayApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectionSettings(connection: NodeConnection, api: RelayApi?, setMode: (ConnectionMode) -> Unit) {
    val routes = connection.routes
    val scope = rememberCoroutineScope()
    var checking by remember(connection) { mutableStateOf(false) }
    val status = api?.connectionStatus?.collectAsState()?.value ?: ConnectionStatus()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)
            Text(connection.name)
            Column(Modifier.selectableGroup()) {
                ConnectionMode.entries.forEach { mode ->
                    val available = when (mode) {
                        ConnectionMode.AUTO -> routes != null
                        ConnectionMode.DIRECT -> routes?.direct?.isNotBlank() ?: true
                        ConnectionMode.RELAY -> routes?.relay?.isNotBlank() == true
                    }
                    val selected = mode == (routes?.mode ?: ConnectionMode.DIRECT)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = available && routes != null,
                        role = Role.RadioButton, onClick = { setMode(mode) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = available && routes != null)
                        Text(when (mode) { ConnectionMode.AUTO -> "自动选择"; ConnectionMode.DIRECT -> "仅 Tailscale 直连"; ConnectionMode.RELAY -> "仅 VPS 中转" })
                    }
                }
            }
            Text("自动模式会记住最近可用路径，读取失败时尝试另一条路径。", style = MaterialTheme.typography.bodySmall)
            if (routes?.relay.isNullOrBlank()) Text("此节点尚未配置 VPS，请扫描该桌面节点新生成的双通道二维码。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("当前连接情况", style = MaterialTheme.typography.titleSmall)
            Text(when {
                status.checkedAt == 0L -> "尚未检测"
                status.failed -> "连接失败，当前路径未确认"
                status.httpCode == 401 -> "授权已失效，请重新配对"
                status.httpCode in 200..299 -> "连接正常"
                else -> "请求返回 HTTP ${status.httpCode}"
            })
            if (status.endpoint.isNotBlank()) {
                Text("实际路径：" + if (routes != null && status.endpoint == routes.relay) "VPS 中转" else "Tailscale 直连")
                Text(status.endpoint, style = MaterialTheme.typography.bodySmall)
            }
            if (status.checkedAt > 0) Text("最近检测：${DateFormat.getDateTimeInstance().format(Date(status.checkedAt))}", style = MaterialTheme.typography.bodySmall)
            Text("显示最近一次请求结果；网络变化后可重新检测。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = api != null && !checking, onClick = {
                scope.launch {
                    checking = true
                    try { api?.call("/v1/node") }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Transport status is reported by the observer. */ }
                    finally { checking = false }
                }
            }) { Text(if (checking) "正在检测…" else "检测连接") }
        }
    }
}
