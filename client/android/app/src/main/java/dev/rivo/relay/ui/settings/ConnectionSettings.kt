package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rivo.relay.connection.*
import dev.rivo.relay.data.RecoveryState
import dev.rivo.relay.data.RelayApi
import dev.rivo.relay.ui.common.ConnectionStatusDot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ConnectionSettings(connection: NodeConnection, api: RelayApi?, setMode: (ConnectionMode) -> Unit,
    savedNodes: List<SavedNode> = emptyList(), onSelectNode: (String) -> Unit = {},
    onAddNode: () -> Unit = {}, canAddNode: Boolean = true) {
    val routes = connection.routes
    val scope = rememberCoroutineScope()
    var checking by remember(connection) { mutableStateOf(false) }
    var nodeMenuOpen by remember(connection) { mutableStateOf(false) }
    val recovery = api?.recovery?.state?.collectAsState()?.value
    val reconnecting = recovery == RecoveryState.CONNECTING
    val status = api?.connectionStatus?.collectAsState()?.value ?: ConnectionStatus()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { nodeMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().testTag("settings_node_selector")) {
                        Text(connection.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(" ▾")
                    }
                    DropdownMenu(expanded = nodeMenuOpen, onDismissRequest = { nodeMenuOpen = false }) {
                        savedNodes.filter { it.authorized }.forEach { saved ->
                            val selected = saved.base == connection.base
                            DropdownMenuItem(
                                text = { Text(saved.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Text(if (selected) "✓" else "", Modifier.width(20.dp)) },
                                onClick = { nodeMenuOpen = false; if (!selected) onSelectNode(saved.base) },
                                modifier = Modifier.testTag("settings_switch_node_${saved.base}"),
                            )
                        }
                    }
                }
                FilledTonalIconButton(onClick = onAddNode, enabled = canAddNode,
                    modifier = Modifier.size(48.dp).testTag("settings_add_node")
                        .semantics { contentDescription = "添加节点" }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
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
            if (routes?.relay.isNullOrBlank()) Text("此节点尚未配置 VPS，请扫描该桌面节点新生成的双通道二维码。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            val statusLabel = when {
                reconnecting -> "正在重连…"
                recovery == RecoveryState.DISCONNECTED -> "连接已断开"
                checking -> "正在检测连接…"
                status.checkedAt == 0L -> "尚未检测"
                status.failed -> "连接失败，当前路径未确认"
                status.httpCode == 401 -> "授权已失效，请重新配对"
                status.httpCode in 200..299 -> "连接正常"
                else -> "请求返回 HTTP ${status.httpCode}"
            }
            val routeLabel = if (status.endpoint.isBlank()) null
                else if (routes != null && status.endpoint == routes.relay) "VPS 中转" else "Tailscale 直连"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val indicator = if (api == null) RecoveryState.DISCONNECTED else if (checking) RecoveryState.CONNECTING
                    else status.indicatorState(recovery)
                ConnectionStatusDot(when (indicator) {
                    RecoveryState.CONNECTED -> true
                    RecoveryState.CONNECTING -> null
                    RecoveryState.DISCONNECTED -> false
                }, Modifier.testTag("settings_connection_status"))
                Text(
                    listOfNotNull(statusLabel, routeLabel, status.latencyMs?.let { "Ping ${it} ms" }).joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(enabled = api != null && !checking && !reconnecting, modifier = Modifier.testTag("settings_reconnect"), onClick = {
                if (api?.recovery != null) api.recovery?.reconnect(manual = true)
                else
                scope.launch {
                    checking = true
                    try { api?.call("/v1/node") }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Transport status is reported by the observer. */ }
                    finally { checking = false }
                }
            }) { Text(if (reconnecting) "正在重连…" else if (checking) "正在检测…" else if (api?.recovery != null) "重连" else "检测连接") }
        }
    }
}
