package dev.rivo.relay.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val sourceTokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b(?:class|fun|def|return|if|else|when|for|while|import|from|package|val|var|const|let|function|async|await|try|catch|except|throw|raise|public|private|internal|interface|data|object|new|true|false|null|None|True|False|SELECT|FROM|WHERE)\\b|\\b\\d+(?:\\.\\d+)?\\b")

/** Bounded, selectable source preview. Highlighting is lexical, never executes file contents. */
@Composable
internal fun SourceCodePreview(text: String, path: String, modifier: Modifier = Modifier, diff: Boolean = false, initialLine: Int? = null) {
    val lines = remember(text) { text.take(200_000).replace("\r\n", "\n").split('\n') }
    val diffNumbers = remember(lines, diff) { if (diff) diffLineNumbers(lines) else emptyList() }
    val colors = MaterialTheme.colorScheme
    val horizontal = rememberScrollState()
    val listState = key(path, initialLine) { rememberLazyListState(((initialLine ?: 1) - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))) }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(horizontal), state = listState) {
            itemsIndexed(lines) { index, line ->
                val styled = remember(line, diff, colors) {
                    if (diff) AnnotatedString(line, SpanStyle(color = when {
                        line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> colors.primary
                        line.startsWith('+') -> Color(0xFF26864A)
                        line.startsWith('-') -> colors.error
                        else -> colors.onSurface
                    })) else AnnotatedString.Builder(line).apply {
                        sourceTokens.findAll(line).forEach { match ->
                            val token = match.value
                            val color = when {
                                token.startsWith("//") || token.startsWith('#') -> colors.onSurfaceVariant
                                token.startsWith('"') || token.startsWith('\'') -> colors.tertiary
                                token.first().isDigit() -> colors.secondary
                                else -> colors.primary
                            }
                            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
                        }
                    }.toAnnotatedString()
                }
                Row(Modifier.background(if (index + 1 == initialLine) colors.secondaryContainer else Color.Transparent).padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(if (diff) diffNumbers[index] else (index + 1).toString().padStart(lines.size.toString().length), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 16.dp))
                    Text(styled.ifEmpty { AnnotatedString(" ") }, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, softWrap = false)
                }
            }
            if (text.length > 200_000) item { Text("预览已截断，保存文件可查看全部内容。", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Unified-diff old/new line positions; headers never pretend to be source lines. */
internal fun diffLineNumbers(lines: List<String>): List<String> {
    var old = 0
    var next = 0
    var inHunk = false
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    return lines.map { line ->
        val match = hunk.matchEntire(line)
        if (match != null) { old = match.groupValues[1].toIntOrNull() ?: 0; next = match.groupValues[2].toIntOrNull() ?: 0; inHunk = true; "    ·     " }
        else if (line.startsWith("diff --git")) { inHunk = false; "          " }
        else if (!inHunk || line.startsWith("\\")) "          "
        else when {
            line.startsWith('+') -> "     ${(next++).toString().padStart(4)}"
            line.startsWith('-') -> "${(old++).toString().padStart(4)}      "
            line.startsWith(' ') -> "${(old++).toString().padStart(4)} ${(next++).toString().padStart(4)}"
            else -> "          "
        }
    }
}

internal fun diffForFile(patch: String, path: String): String {
    val blocks = patch.split(Regex("(?m)(?=^diff --git )"))
    return blocks.filter { block ->
        val lines = block.lineSequence().takeWhile { !it.startsWith("@@") }.toList()
        val target = lines.firstOrNull { it.startsWith("+++ b/") }?.removePrefix("+++ b/")?.substringBefore('\t')
            ?: lines.firstOrNull { it.startsWith("diff --git ") }?.substringAfterLast(" b/")
        target == path
    }.joinToString("")
}

internal fun fileMimeType(path: String, declared: String = ""): String {
    if (path.substringBefore('?').substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) return "text/markdown"
    if (declared.isNotBlank() && declared != "application/octet-stream") return declared
    return when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "swift", "rs", "go", "c", "h", "cpp", "css", "sh", "toml", "yaml", "yml", "log", "txt" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
