package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*

@Composable
internal fun SpeechSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { SpeechPreferences(context) }
    var saved by remember { mutableStateOf(preferences.vocabulary) }
    var vocabulary by rememberSaveable { mutableStateOf(saved) }
    var savedNotice by remember { mutableStateOf(false) }
    val tooLong = vocabulary.length > SPEECH_VOCABULARY_LIMIT
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("语音输入", style = MaterialTheme.typography.titleMedium)
            Text("填写常用词或专有名词，用逗号、顿号或换行分隔。保存后对所有节点生效，下次录音时使用；留空则不添加词表。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = vocabulary, onValueChange = { vocabulary = it; savedNotice = false },
                label = { Text("识别词表") }, minLines = 2, maxLines = 5, isError = tooLong,
                supportingText = { Text(if (tooLong) "词表不能超过 $SPEECH_VOCABULARY_LIMIT 字符" else "${vocabulary.length} / $SPEECH_VOCABULARY_LIMIT") },
                modifier = Modifier.fillMaxWidth().testTag("speech_vocabulary"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    preferences.vocabulary = vocabulary
                    saved = vocabulary
                    savedNotice = true
                }, enabled = !tooLong && vocabulary != saved, modifier = Modifier.testTag("save_speech_vocabulary")) { Text("保存") }
                TextButton(onClick = { vocabulary = ""; savedNotice = false }, modifier = Modifier.testTag("clear_speech_vocabulary")) { Text("清空") }
                TextButton(onClick = { vocabulary = DEFAULT_SPEECH_VOCABULARY; savedNotice = false }) { Text("恢复默认") }
            }
            if (savedNotice) Text("已保存，下次录音生效", modifier = Modifier.testTag("speech_vocabulary_saved"),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
