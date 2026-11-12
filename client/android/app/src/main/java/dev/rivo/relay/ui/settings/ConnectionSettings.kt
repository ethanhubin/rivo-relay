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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ConnectionSettings(connection: NodeConnection, api: RelayApi?, setMode: (ConnectionMode) -> Unit,
    savedNodes: List<SavedNode> = emptyList(), onSelectNode: (String) -> Unit = {},
    onAddNode: () -> Unit = {}, canAddNode: Boolean = true) {
    var version by remember(connection, api) { mutableStateOf("正在获取…") }
    var compatibility by remember(connection, api) { mutableStateOf("兼容性未知") }
    var versionRefresh by remember(connection, api) { mutableIntStateOf(0) }
    LaunchedEffect(connection, api, versionRefresh) {
        version = try {
            val code = api?.call("/v1/node")?.optInt("releaseCode", 0) ?: 0
            compatibility = api?.compatibilityMessage ?: "兼容性未知"
            if (code > 0) code.toString() else "未提供"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            compatibility = "兼容性未知，请重新连接后检查"
            "暂不可用"
        }
    }
    val routes = connection.routes
    val scope = rememberCoroutineScope()
    var checking by remember(connection) { mutableStateOf(false) }
    var nodeMenuOpen by remember(connection) { mutableStateOf(false) }
    val recovery = api?.recovery?.state?.collectAsState()?.value
    val reconnecting = recovery == RecoveryState.CONNECTING
    val status = api?.connectionStatus?.collectAsState()?.value ?: ConnectionStatus()
    var networkLatencyMs by remember(api, status.endpoint) { mutableStateOf<Long?>(null) }
    LaunchedEffect(api, status.endpoint, status.failed, recovery, versionRefresh) {
        networkLatencyMs = null
        if (api != null && status.endpoint.isNotBlank() && !status.failed &&
            recovery != RecoveryState.DISCONNECTED && recovery != RecoveryState.CONNECTING) {
            while (isActive) {
                try {
                    networkLatencyMs = api.networkLatency(status.endpoint)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    networkLatencyMs = null
                }
                delay(15_000)
            }
        }
    }
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { nodeMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().testTag("settings_node_selector")) {
                        Text(connection.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(" ›")
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
                status.httpCode in 200..299 -> null
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
                    listOfNotNull(
                        statusLabel,
                        "节点版本：$version\n$compatibility",
                        routeLabel,
                        networkLatencyMs?.takeIf {
                            !status.failed && status.endpoint.isNotBlank() &&
                                recovery != RecoveryState.DISCONNECTED && !reconnecting
                        }?.let { "$it ms" },
                    ).joinToString(" · "),
                    modifier = Modifier.weight(1f).testTag("settings_node_version"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(enabled = api != null && !checking && !reconnecting, modifier = Modifier.testTag("settings_refresh"), onClick = {
                scope.launch {
                    checking = true
                    try {
                        val controller = api?.recovery
                        if (controller != null) controller.refresh() else api?.call("/v1/node")
                    }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Transport status is reported by the observer. */ }
                    finally { checking = false; versionRefresh++ }
                }
            }) { Text(if (checking || reconnecting) "正在刷新…" else "刷新") }
        }
    }
}
