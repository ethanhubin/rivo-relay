package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Questions live in their own window; closing it leaves the answer draft intact. */
@Composable
internal fun QuestionDialog(title: String, tag: String, toggleTag: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(toggleTag)) { Text(title) }
        return
    }
    Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        ThemedTextContextMenu {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 560.dp)
                    .fillMaxWidth().testTag(tag),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { open = false }, modifier = Modifier.testTag(toggleTag)) { Text("稍后回答") }
                    }
                    content()
                }
            }
        }
    }
}
