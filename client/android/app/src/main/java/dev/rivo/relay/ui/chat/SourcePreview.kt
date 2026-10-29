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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private val sourceTokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b(?:class|fun|def|return|if|else|when|for|while|import|from|package|val|var|const|let|function|async|await|try|catch|except|throw|raise|public|private|internal|interface|data|object|new|true|false|null|None|True|False|SELECT|FROM|WHERE)\\b|\\b\\d+(?:\\.\\d+)?\\b")

/** Bounded, selectable source preview. Highlighting is lexical, never executes file contents. */
@Composable
internal fun SourceCodePreview(text: String, path: String, modifier: Modifier = Modifier, diff: Boolean = false, initialLine: Int? = null) {
    if (diff) { DiffPatchPreview(text, modifier); return }
    val lines = remember(text) { text.take(200_000).replace("\r\n", "\n").split('\n') }
    val colors = MaterialTheme.colorScheme
    val horizontal = rememberScrollState()
    val listState = key(path, initialLine) { rememberLazyListState(((initialLine ?: 1) - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))) }
    val clipboard = LocalClipboardManager.current
    Column(modifier) {
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.align(Alignment.End).testTag("source_copy"),
        ) { Text("复制代码") }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(horizontal), state = listState) {
                itemsIndexed(lines) { index, line ->
                    val styled = remember(line, colors) {
                        AnnotatedString.Builder(line).apply {
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
                        Text((index + 1).toString().padStart(lines.size.toString().length), color = colors.onSurfaceVariant,
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
        val newPath = lines.firstOrNull { it.startsWith("+++ ") }?.substring(4)?.let(::gitDiffHeaderPath)
        val oldPath = lines.firstOrNull { it.startsWith("--- ") }?.substring(4)?.let(::gitDiffHeaderPath)
        val renamePath = lines.firstOrNull { it.startsWith("rename to ") }?.removePrefix("rename to ")
        val target = newPath?.takeUnless { it == "/dev/null" } ?: oldPath?.takeUnless { it == "/dev/null" } ?: renamePath
            ?: lines.firstOrNull { it.startsWith("diff --git ") }?.substringAfterLast(" b/")
        target == path
    }.joinToString("")
}

internal fun fileMimeType(path: String, declared: String = ""): String {
    val extension = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    if (extension in setOf("md", "mdx", "markdown")) return "text/markdown"
    if (declared.isNotBlank() && !declared.substringBefore(';').trim().equals("application/octet-stream", true)) return declared
    return when (extension) {
        "md", "mdx", "markdown" -> "text/markdown"
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "swift", "rs", "go", "c", "h", "cpp", "hpp", "cc", "cs", "rb", "php", "lua", "r", "dart", "vue", "svelte", "css", "scss", "less", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "toml", "yaml", "yml", "log", "txt", "ini", "conf", "cfg", "sql", "diff", "patch", "srt", "vtt", "gradle", "properties", "env", "gitignore" -> "text/plain"
        "json", "jsonl", "ipynb", "geojson" -> "application/json"
        "xml", "xsl", "xsd" -> "application/xml"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "rtf" -> "application/rtf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "epub" -> "application/epub+zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "tif", "tiff" -> "image/tiff"
        "ico" -> "image/x-icon"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "amr" -> "audio/amr"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "weba" -> "audio/webm"
        "m2ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ogv" -> "video/ogg"
        "mpeg", "mpg" -> "video/mpeg"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        else -> "application/octet-stream"
    }
}
