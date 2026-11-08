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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.theme.RivoSpacing
import dev.rivo.relay.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(busy: Boolean, error: String?, onPair: (String, String) -> Unit, onError: (String) -> Unit,
    onBack: (() -> Unit)? = null) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var readingImage by remember { mutableStateOf(false) }
    val pickImage = rememberQrImagePicker({ onPair(it, "") }, onError) { readingImage = it }
    val scan = rememberQrScanner({ onPair(it, "") }, onError)
    Column(Modifier.fillMaxSize().imePadding()) {
        if (onBack != null) TopAppBar(title = { Text("新增节点") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("login_back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
            }
        })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(RivoSpacing.Page),
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
            Button(onClick = scan, enabled = !busy && !readingImage, modifier = Modifier.fillMaxWidth(0.86f).heightIn(min = 52.dp).testTag("scan_connect")) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在连接…") }
                else Text("扫码连接")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = pickImage, enabled = !busy && !readingImage, modifier = Modifier.fillMaxWidth(0.86f).heightIn(min = 52.dp).testTag("photo_connect")) {
                Text(if (readingImage) "正在识别…" else "从相册识别")
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { manual = true }, enabled = !busy && !readingImage, modifier = Modifier.testTag("manual_entry")) { Text("手动输入") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp).testTag("login_error")) }
        }
    }
    if (manual) {
        AlertDialog(onDismissRequest = { if (!busy && !readingImage) manual = false }, title = { Text("手动连接") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("粘贴完整配对链接，或输入节点地址和配对码。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(address, { address = it }, label = { Text("节点地址或配对链接") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("pairing_address"))
                    OutlinedTextField(code, { code = it }, label = { Text("配对码") }, supportingText = { Text("完整配对链接无需再填") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("pairing_code"))
                }
            }, confirmButton = { Button(onClick = { manual = false; onPair(address, code); code = "" }, enabled = address.isNotBlank() && !busy && !readingImage,
                modifier = Modifier.testTag("manual_connect")) { Text("连接") } },
            dismissButton = { TextButton(onClick = { manual = false }, enabled = !busy && !readingImage) { Text("取消") } })
    }
}
