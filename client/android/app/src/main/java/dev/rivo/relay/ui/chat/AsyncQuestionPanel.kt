package dev.rivo.relay.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun AsyncQuestionPanel(questions: List<AsyncQuestion>, enabled: Boolean,
    store: ConversationStore? = null, draftKey: String = "", unavailableReason: String? = null, onSubmit: (JSONArray, () -> Unit) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var answerJson by rememberSaveable { mutableStateOf("{}") }
    var submitted by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val scope = rememberCoroutineScope()
    var loaded by remember(store, draftKey) { mutableStateOf(store == null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store, draftKey) {
        if (store != null) try {
            val saved = store.loadDraft(draftKey).text
            if (saved.isNotBlank()) answerJson = JSONObject(saved).toString()
            loaded = true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            saveError = "无法读取回答草稿，请重新进入后重试。"
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val remaining = questions.filterNot { it.id in submitted }
    if (remaining.isEmpty()) return
    val answers = JSONObject(answerJson).let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("async_questions_panel")) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle_async_questions")) {
                Text(if (expanded) "Codex 有 ${remaining.size} 个问题 ▴" else "待回答 ${remaining.size} 个问题 ▾")
            }
            if (expanded && unavailableReason != null) Text(unavailableReason, style = MaterialTheme.typography.bodySmall)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (expanded) NativeQuestionCard(remaining.map { it.card() }, answers, index, loaded && !saving,
                { index = it }, { id, value ->
                    answerJson = JSONObject(answerJson).put(id, value.take(200000)).toString()
                    store?.enqueueDraft(draftKey, ConversationDraft(text = answerJson))
                }, submitEnabled = enabled && loaded && !saving) {
                saving = true
                scope.launch {
                    try {
                        store?.saveDraftOrdered(draftKey, ConversationDraft(text = answerJson))
                        saveError = null
                        onSubmit(asyncQuestionReply(remaining, answers)) {
                            submitted = ArrayList(submitted + remaining.map { it.id })
                            val retained = JSONObject(answerJson)
                            remaining.forEach { retained.remove(it.id) }
                            answerJson = retained.toString()
                            store?.enqueueDraft(draftKey, ConversationDraft(text = answerJson))
                            index = 0
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        saveError = "无法保存回答，请重试；尚未发送。"
                    } finally { saving = false }
                }
            }
        }
    }
}
