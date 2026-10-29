package dev.rivo.relay.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

internal data class DiffDisplayLine(val kind: String, val text: String, val oldLine: Int? = null, val newLine: Int? = null)

/** Adapt native unified patches to QiGuang's file / hunk / old-new-line presentation. */
internal fun diffDisplayLines(patch: String): List<DiffDisplayLine> {
    val rows = mutableListOf<DiffDisplayLine>()
    var old = 0
    var next = 0
    var inHunk = false
    var fileRow = -1
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    patch.replace("\r\n", "\n").lineSequence().forEach { line ->
        val header = hunk.matchEntire(line)
        when {
            line.startsWith("diff --git ") -> {
                inHunk = false
                fileRow = rows.size
                rows += DiffDisplayLine("file", line.removePrefix("diff --git "))
            }
            header != null -> {
                old = header.groupValues[1].toIntOrNull() ?: 0
                next = header.groupValues[2].toIntOrNull() ?: 0
                inHunk = true
                rows += DiffDisplayLine("hunk", line)
            }
            !inHunk && (line.startsWith("+++ ") || line.startsWith("--- ")) -> {
                val path = gitDiffHeaderPath(line.substring(4))
                if (path != "/dev/null") {
                    if (fileRow < 0) { fileRow = rows.size; rows += DiffDisplayLine("file", path) }
                    else rows[fileRow] = DiffDisplayLine("file", path)
                }
            }
            inHunk && line.startsWith('+') -> rows += DiffDisplayLine("addition", line.drop(1), newLine = next++)
            inHunk && line.startsWith('-') -> rows += DiffDisplayLine("deletion", line.drop(1), oldLine = old++)
            inHunk && line.startsWith(' ') -> rows += DiffDisplayLine("context", line.drop(1), old++, next++)
            inHunk && line.startsWith('\\') -> rows += DiffDisplayLine("metadata", line)
            !inHunk && line.startsWith("Binary files ") -> rows += DiffDisplayLine("metadata", "二进制文件只显示元数据，不加载内容")
            else -> Unit
        }
    }
    return rows
}

/** Git quotes tabs and other special bytes using C escapes, including octal UTF-8 bytes. */
internal fun gitDiffHeaderPath(header: String): String {
    val value = if (!header.startsWith('"')) header.substringBefore('\t') else {
        val bytes = ByteArrayOutputStream()
        var cursor = 1
        while (cursor < header.length) {
            val char = header[cursor++]
            if (char == '"') break
            if (char != '\\') {
                val point = if (char.isHighSurrogate() && cursor < header.length && header[cursor].isLowSurrogate()) "$char${header[cursor++]}" else char.toString()
                bytes.write(point.toByteArray(Charsets.UTF_8))
            } else if (cursor < header.length) {
                val escaped = header[cursor++]
                if (escaped in '0'..'7') {
                    var octal = escaped.toString()
                    repeat(2) { if (cursor < header.length && header[cursor] in '0'..'7') octal += header[cursor++] }
                    bytes.write(octal.toInt(8))
                } else bytes.write(when (escaped) { 'a' -> 7; 'b' -> 8; 'f' -> 12; 'n' -> 10; 'r' -> 13; 't' -> 9; 'v' -> 11; else -> escaped.code })
            }
        }
        bytes.toString("UTF-8")
    }
    return if (value.startsWith("a/") || value.startsWith("b/")) value.substring(2) else value
}

/** Rows and colours mirror QiGuang CodexReviewScreen.DiffLineRow. */
@Composable
internal fun DiffPatchPreview(patch: String, modifier: Modifier = Modifier, metadataFiles: List<String> = emptyList(), copyPatch: String = patch) {
    val rows = remember(patch) { diffDisplayLines(patch.take(200_000)) }
    val clipboard = LocalClipboardManager.current
    Column(modifier) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(copyPatch)) }, enabled = copyPatch.isNotBlank(),
            modifier = Modifier.align(Alignment.End).testTag("source_copy")) { Text("复制补丁") }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(metadataFiles) { path ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(path, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("二进制文件只显示元数据，不加载内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(rows) { line -> when (line.kind) {
                    "file" -> Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    "hunk" -> Text(line.text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 6.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    "metadata" -> Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> DiffLineRow(line)
                } }
                if (patch.length > 200_000) item { Text("差异较大，仅展示部分内容；复制补丁可获取完整返回内容。", Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffDisplayLine) {
    val marker = when (line.kind) { "addition" -> "+"; "deletion" -> "−"; else -> " " }
    val background = when (line.kind) {
        "addition" -> Color(0xFF1B5E20).copy(alpha = 0.16f)
        "deletion" -> Color(0xFFB71C1C).copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(line.oldLine?.toString().orEmpty().padStart(4), Modifier.padding(start = 4.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line.newLine?.toString().orEmpty().padStart(4), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$marker ${line.text}", Modifier.weight(1f).padding(horizontal = 6.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
