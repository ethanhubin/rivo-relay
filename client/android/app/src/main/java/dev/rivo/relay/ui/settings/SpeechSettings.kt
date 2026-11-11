package dev.rivo.relay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import dev.rivo.relay.R
import androidx.compose.ui.res.painterResource

@Composable
internal fun SpeechSettings(onEdit: () -> Unit) {
    SettingsEntry("语音输入", R.drawable.ic_microphone, "edit_speech_settings", onEdit)
}

@Composable
internal fun SettingsEntry(title: String, icon: Int, tag: String, onEdit: () -> Unit) {
    OutlinedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("编辑", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("›", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSettingsEditor(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { SpeechPreferences(context) }
    var saved by remember { mutableStateOf(preferences.vocabulary) }
    var vocabulary by rememberSaveable { mutableStateOf(saved) }
    var savedNotice by remember { mutableStateOf(false) }
    val tooLong = vocabulary.length > SPEECH_VOCABULARY_LIMIT
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("speech_settings_page"),
        topBar = {
            CenterAlignedTopAppBar(title = { Text("语音输入") }, navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.testTag("close_speech_settings")) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回设置")
                }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("填写常用词或专有名词，用逗号、顿号或换行分隔。保存后对所有节点生效，下次录音时使用；留空则不添加词表。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = vocabulary, onValueChange = { vocabulary = it; savedNotice = false },
                label = { Text("识别词表") }, minLines = 5, maxLines = 10, isError = tooLong,
                supportingText = { Text(if (tooLong) "词表不能超过 $SPEECH_VOCABULARY_LIMIT 字符" else "${vocabulary.length} / $SPEECH_VOCABULARY_LIMIT") },
                modifier = Modifier.fillMaxWidth().testTag("speech_vocabulary"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
