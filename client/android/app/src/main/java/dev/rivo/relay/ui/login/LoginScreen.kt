package dev.rivo.relay.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.theme.RivoSpacing

@Composable
fun LoginScreen(busy: Boolean, error: String?, onPair: (String, String) -> Unit, onError: (String) -> Unit, savedNodes: List<dev.rivo.relay.connection.SavedNode> = emptyList(), onSelect: (String) -> Unit = {}) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val scan = rememberQrScanner({ onPair(it, "") }, onError)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(RivoSpacing.Page),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Text("R", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text("登录节点", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("扫描电脑上节点生成的配对二维码", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("scan_connect")) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在连接…") }
            else Text("扫码连接")
        }
        TextButton(onClick = { manual = true }, enabled = !busy, modifier = Modifier.testTag("manual_entry")) { Text("手动输入") }
        savedNodes.forEach { node ->
            OutlinedButton(onClick = { if (node.authorized) onSelect(node.base) else { address = node.base; code = ""; manual = true } }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("saved_node_${node.base}")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(node.name)
                    if (node.name != node.base) Text(node.base, style = MaterialTheme.typography.bodySmall)
                    Text(if (node.authorized) "连接 / 重试" else "重新授权", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp).testTag("login_error")) }
    }
    if (manual) {
        AlertDialog(onDismissRequest = { if (!busy) manual = false }, title = { Text("手动连接") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("粘贴完整配对链接，或输入节点地址和配对码。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(address, { address = it }, label = { Text("节点地址或配对链接") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("pairing_address"))
                    OutlinedTextField(code, { code = it }, label = { Text("配对码") }, supportingText = { Text("完整配对链接无需再填") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("pairing_code"))
                }
            }, confirmButton = { Button(onClick = { manual = false; onPair(address, code); code = "" }, enabled = address.isNotBlank() && !busy,
                modifier = Modifier.testTag("manual_connect")) { Text("连接") } },
            dismissButton = { TextButton(onClick = { manual = false }, enabled = !busy) { Text("取消") } })
    }
}
