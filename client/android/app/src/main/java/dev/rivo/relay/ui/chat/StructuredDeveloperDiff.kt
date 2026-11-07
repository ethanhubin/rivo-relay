package dev.rivo.relay.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rivo.relay.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/** Revision-bound pages are never parsed from a patch or merged across file selections. */
@Composable
internal fun StructuredDeveloperDiffContent(threadId: String, initial: JSONObject,
    read: suspend (String) -> JSONObject, modifier: Modifier = Modifier) {
    val base = "/v1/threads/${encoded(threadId)}/diff"
    var snapshot by remember(threadId, initial) { mutableStateOf(initial) }
    var unified by remember(threadId) { mutableStateOf(true) }
    var selectedPath by remember(threadId) { mutableStateOf<String?>(null) }
    var generation by remember(threadId) { mutableIntStateOf(0) }
    var requestedOffset by remember(threadId, initial, unified, selectedPath, generation) { mutableIntStateOf(0) }
    var pageRequest by remember(threadId, initial, unified, selectedPath, generation) { mutableIntStateOf(0) }
    var lines by remember(threadId, initial, unified, selectedPath, generation) { mutableStateOf(emptyList<JSONObject>()) }
    var nextOffset by remember(threadId, initial, unified, selectedPath, generation) { mutableStateOf<Int?>(null) }
    var loading by remember(threadId, initial, unified, selectedPath, generation) { mutableStateOf(true) }
    var error by remember(threadId, initial, unified, selectedPath, generation) { mutableStateOf<String?>(null) }
    var refreshed by remember(threadId, initial, unified, selectedPath, generation) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val files = snapshot.objects("files")
    val selected = files.firstOrNull { it.string("path") == selectedPath } ?: files.firstOrNull()
    LaunchedEffect(threadId, initial, unified, selectedPath, generation, requestedOffset, pageRequest) {
        loading = true; error = null
        var fresh = snapshot
        var offset = requestedOffset
        var reset = false
        try {
            suspend fun page(): JSONObject {
                val chosen = fresh.objects("files").firstOrNull { it.string("path") == selectedPath } ?: fresh.objects("files").firstOrNull()
                val query = "?revision=${encoded(fresh.string("revision"))}&offset=$offset&limit=400" +
                    if (!unified && chosen != null) "&fileId=${encoded(chosen.string("id"))}" else ""
                return read(base + query).optJSONObject("diff") ?: error("节点未返回差异内容")
            }
            val result = try { page() } catch (failure: ApiException) {
                if (failure.status != 409) throw failure
                fresh = read(base).optJSONObject("diff") ?: throw failure
                coroutineContext.ensureActive()
                offset = 0; reset = true
                page()
            }
            coroutineContext.ensureActive()
            require(result.string("revision") == fresh.string("revision")) { "差异已变化，请刷新后重试" }
            snapshot = fresh
            if (reset) refreshed = true
            lines = if (offset == 0) result.objects("lines") else lines + result.objects("lines")
            nextOffset = if (result.isNull("nextOffset")) null else result.optInt("nextOffset").takeIf { it > offset }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = if (failure is ApiException && failure.status == 409) "工作区仍在变化，请稍后刷新" else failure.message ?: "无法读取差异" }
        finally { coroutineContext.ensureActive(); loading = false }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(unified, { unified = true }, label = { Text("统一") }, modifier = Modifier.testTag("codex_diff_unified"))
            FilterChip(!unified, { unified = false }, label = { Text("文件") }, modifier = Modifier.testTag("codex_diff_files"))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { generation++ }, enabled = !loading, modifier = Modifier.testTag("codex_diff_refresh")) { Text("刷新") }
        }
        if (!unified) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { file -> FilterChip(selected?.string("path") == file.string("path"), { selectedPath = file.string("path") },
                label = { Text(file.string("path").substringAfterLast('/')) }, modifier = Modifier.testTag("codex_diff_file_${file.string("path")}")) }
        }
        if (refreshed) Text("工作区已变化，已刷新差异。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        if (snapshot.optBoolean("truncated")) Text("内容超过上限，部分差异未展示。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        if (files.isEmpty() && !loading && error == null) Text("当前工作空间没有未提交改动", Modifier.padding(24.dp).testTag("codex_diff_empty"))
        else {
            if (!unified) selected?.let { file ->
                Text(file.string("path"), Modifier.padding(horizontal = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                if (file.string("oldPath").isNotBlank() && file.string("oldPath") != file.string("newPath")) Text("${file.string("oldPath")} → ${file.string("newPath")}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n") { line ->
                (when (line.string("kind")) { "addition" -> "+"; "deletion" -> "-"; "context" -> " "; else -> "" }) + line.string("text")
            })) }, enabled = lines.isNotEmpty(), modifier = Modifier.testTag("source_copy")) { Text("复制当前差异") }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().testTag("diff_preview")) {
                    itemsIndexed(lines) { _, line ->
                        when (line.string("kind")) {
                            "file", "hunk", "notice" -> Text(line.string("text"), Modifier.fillMaxWidth()
                                .background(if (line.string("kind") == "hunk") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 10.dp, vertical = 6.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            else -> DiffLineRow(DiffDisplayLine(line.string("kind"), line.string("text"),
                                if (line.isNull("oldLine")) null else line.optInt("oldLine"), if (line.isNull("newLine")) null else line.optInt("newLine")))
                        }
                    }
                    if (nextOffset != null) item { TextButton(onClick = { requestedOffset = nextOffset!!; pageRequest++ }, enabled = !loading,
                        modifier = Modifier.fillMaxWidth().testTag("codex_diff_load_more")) { Text(if (loading) "加载中…" else "加载更多差异") } }
                }
            }
        }
    }
}
