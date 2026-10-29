package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import org.json.JSONObject

/** QiGuang CodexInteractionCard presentation, keeping native question IDs and answer labels. */
@Composable
internal fun NativeQuestionCard(questions: List<JSONObject>, answers: Map<String, String>, index: Int, enabled: Boolean,
    onIndex: (Int) -> Unit, onAnswer: (String, String) -> Unit, onSubmit: () -> Unit) {
    if (questions.isEmpty()) { Text("请求尚未提供可回答的问题，请等待节点更新。"); return }
    val position = index.coerceIn(questions.indices)
    val question = questions[position]
    val id = question.string("id")
    val answer = answers[id].orEmpty()
    val options = question.objects("options")
    Column(Modifier.fillMaxWidth().testTag("native_question_card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(question.string("header").ifBlank { "需要你确认" }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("${position + 1}/${questions.size}", Modifier.testTag("question_progress"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(question.string("question"), style = MaterialTheme.typography.bodyMedium)
        options.forEachIndexed { optionIndex, option ->
            val label = option.string("label")
            val chosen = answer == label
            Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { onAnswer(id, label); if (position < questions.lastIndex) onIndex(position + 1) }
                .semantics { selected = chosen }.testTag("question_option_${id}_$optionIndex"), shape = RoundedCornerShape(12.dp),
                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    option.string("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (question.optBoolean("isOther") || options.isEmpty() || question.optBoolean("isSecret")) {
            val freeText = answer.takeUnless { value -> options.any { it.string("label") == value } }.orEmpty()
            OutlinedTextField(freeText, { onAnswer(id, it) }, label = { Text(if (options.isEmpty()) "回答" else "其他") }, enabled = enabled,
                visualTransformation = if (question.optBoolean("isSecret")) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (question.optBoolean("isSecret")) KeyboardType.Password else KeyboardType.Text),
                minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("question_answer_$id"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (position > 0) TextButton(onClick = { onIndex(position - 1) }, enabled = enabled, modifier = Modifier.testTag("previous_question")) { Text("上一题") }
            if (position < questions.lastIndex) TextButton(onClick = { onIndex(position + 1) }, enabled = enabled && answer.isNotBlank(), modifier = Modifier.testTag("next_question")) { Text("下一题") }
            Button(onClick = onSubmit, enabled = enabled && questions.all { !answers[it.string("id")].isNullOrBlank() }, modifier = Modifier.testTag("answer_questions")) { Text("提交回答") }
        }
    }
}
