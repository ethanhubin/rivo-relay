package dev.rivo.relay.data

import org.json.JSONArray
import org.json.JSONObject

/** Compact display projection of QiGuang _persisted_activity; raw native items stay authoritative. */
data class NativeActivity(val kind: String, val title: String, val summary: String, val detail: String,
    val status: String, val filePaths: List<String> = emptyList(), val exitCode: Int? = null)

fun nativeActivity(item: JSONObject): NativeActivity? {
    val type = item.string("type").replace("_", "").lowercase()
    if (type in setOf("usermessage", "imagegeneration", "exitedreviewmode")) return null
    if (type == "agentmessage" && item.string("phase") != "commentary") return null
    val kind = when (type) {
        "imageview" -> "image"; "agentmessage" -> "commentary"; "reasoning" -> "reasoning"; "commandexecution" -> "command"
        "filechange" -> "file"; "mcptoolcall", "dynamictoolcall", "functioncalloutput", "sleep" -> "tool"
        "collabagenttoolcall", "collabtoolcall", "subagentactivity" -> "collaboration"
        "websearch" -> "web"; "plan" -> "plan"; else -> "unknown"
    }
    val title = when (type) {
        "imageview" -> "查看图片"; "agentmessage" -> "过程消息"; "reasoning" -> "分析摘要"; "commandexecution" -> "命令执行"
        "filechange" -> "文件变更"; "mcptoolcall" -> "MCP 工具调用"; "dynamictoolcall" -> "工具调用"
        "functioncalloutput" -> "工具结果"; "sleep" -> "等待"; "websearch" -> "网页检索"
        "collabagenttoolcall", "collabtoolcall", "subagentactivity" -> "协作代理"
        "plan" -> "执行计划"; else -> "Codex 活动 · ${item.string("type")}"
    }
    val paths = if (kind == "file") item.objects("changes").map { it.string("path") }.filter(String::isNotBlank).distinct() else if (type == "imageview") listOf(item.string("path")).filter(String::isNotBlank) else emptyList()
    val summary = when (kind) {
        "image" -> item.string("path").substringAfterLast('/').ifBlank { "图片" }
        "command" -> item.string("command")
        "reasoning" -> nativeReasoningText(item)
        "file" -> paths.joinToString(" · ") { it.substringAfterLast('/').substringAfterLast('\\') }
        "commentary" -> item.string("text")
        "web" -> item.string("query").ifBlank { nativeText(item.opt("action")) }
        "collaboration" -> item.string("prompt").ifBlank { item.string("agentPath") }.ifBlank { "协作代理正在处理" }
        "plan" -> item.string("text").ifBlank { item.objects("plan").joinToString("\n") { it.string("step") } }
        "tool" -> listOf(item.string("server").ifBlank { item.string("namespace") }, item.string("tool").ifBlank { item.string("name") }).filter(String::isNotBlank).joinToString(" / ")
        else -> item.string("text")
    }.ifBlank { if (kind == "reasoning") "" else title }
    val detail = when (type) {
        "commandexecution" -> item.string("aggregatedOutput")
        "filechange" -> item.objects("changes").joinToString("\n\n") { it.string("path") + "\n" + it.string("diff") }
        "functioncalloutput" -> nativeFunctionOutput(item.opt("output"))
        "mcptoolcall", "dynamictoolcall" -> listOf("arguments", "result", "error").mapNotNull { key -> item.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { "$key\n${nativeText(it)}" } }.joinToString("\n\n")
        else -> if (kind == "unknown") item.toString(2) else ""
    }
    val status = when (item.string("status").replace("_", "").lowercase()) {
        "inprogress", "running", "started", "pending" -> "running"
        "failed", "error", "declined", "interrupted", "canceled", "cancelled" -> "failed"
        else -> "completed"
    }
    return NativeActivity(kind, title, summary, detail, status, paths,
        if (kind == "command") (item.opt("exitCode") as? Number)?.toInt() else null)
}

internal fun nativeReasoningText(item: JSONObject): String {
    fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).map { text(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n")
        is JSONObject -> value.opt("text") as? String ?: ""
        else -> ""
    }
    return text(item.opt("summary")).ifBlank { text(item.opt("content")) }.ifBlank { text(item.opt("text")) }
}

private fun nativeText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until value.length()).map { nativeText(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n")
    is JSONObject -> value.toString(2)
    else -> value.toString()
}

private fun nativeFunctionOutput(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> (0 until value.length()).mapNotNull { index -> value.optJSONObject(index)?.let { part -> when (part.string("type")) {
        "input_text" -> part.string("text"); "input_image" -> "[图片]"; "input_audio" -> "[音频]"; "encrypted_content" -> "[加密内容]"
        else -> "[${part.string("type").ifBlank { "未知工具输出" }}]"
    } } }.joinToString("\n")
    else -> ""
}

sealed interface NativeTimelineRow {
    val key: String
    data class Message(val message: NativeMessage): NativeTimelineRow { override val key = "message:${message.turnId}:${message.id}" }
    data class Activities(val messages: List<NativeMessage>): NativeTimelineRow { override val key = "activities:${messages.first().turnId}:${messages.first().id}" }
}

/** Same-turn adjacent activity runs collapse together; user/final/process messages keep their positions. */
fun nativeTimelineRows(messages: List<NativeMessage>, activeTurnId: String? = null): List<NativeTimelineRow> {
    val rows = mutableListOf<NativeTimelineRow>()
    val pending = mutableListOf<NativeMessage>()
    fun flush() { if (pending.isNotEmpty()) { rows += NativeTimelineRow.Activities(pending.toList()); pending.clear() } }
    messages.forEach { message ->
        // Empty native reasoning items carry no displayable summary. Keep raw history intact.
        if (message.activity?.kind == "reasoning" && message.activity.summary.isBlank()) return@forEach
        if (message.activity != null && message.activity.kind != "commentary") {
            if (pending.lastOrNull()?.turnId?.let { it != message.turnId } == true) flush()
            pending += message
        } else { flush(); rows += NativeTimelineRow.Message(message) }
    }
    flush()
    // QiGuang keeps older execution segments collapsed. The latest segment in each
    // turn exposes each tool, grouping only adjacent commands so file/web details
    // remain directly reachable instead of disappearing inside one summary.
    val lastByTurn = rows.withIndex().filter { it.value is NativeTimelineRow.Activities }
        .associate { it.value.let { row -> (row as NativeTimelineRow.Activities).messages.first().turnId } to it.index }
    return rows.flatMapIndexed { index, row ->
        if (row !is NativeTimelineRow.Activities || row.messages.first().turnId != activeTurnId ||
            lastByTurn[row.messages.first().turnId] != index) listOf(row)
        else {
            val groups = mutableListOf<MutableList<NativeMessage>>()
            row.messages.forEach { message ->
                val current = groups.lastOrNull()
                if (message.activity?.kind == "command" && current?.all { it.activity?.kind == "command" } == true) current += message
                else groups += mutableListOf(message)
            }
            groups.map { NativeTimelineRow.Activities(it.toList()) }
        }
    }
}

fun nativeActivityGroupLabel(messages: List<NativeMessage>): String {
    val activities = messages.mapNotNull { it.activity }
    if (activities.isEmpty()) return "Codex 活动"
    val commands = activities.filter { it.kind == "command" }
    val commandLabel = if (commands.isEmpty()) null else {
        val running = commands.count { it.status == "running" }
        val failed = commands.count { it.status != "running" && (it.status == "failed" || (it.exitCode != null && it.exitCode != 0)) }
        val succeeded = commands.size - running - failed
        "运行了 ${commands.size} 条命令 · 成功 $succeeded · 失败 $failed" +
            if (running > 0) " · 进行中 $running" else ""
    }
    if (commands.size == activities.size) return commandLabel!!
    val otherLabels = activities.filter { it.kind != "command" }.joinToString(" · ") { activity ->
        val text = compactActivity(activity.summary)
        val running = activity.status == "running"
        when (activity.kind) {
            "command" -> if (running) "正在运行命令" else "运行了 1 条命令"
            "file" -> if (running) "正在编辑 $text" else "编辑了 $text"
            "web" -> if (running) "正在检索 $text" else "检索了 $text"
            "tool", "collaboration" -> if (running) "正在使用 $text" else "使用了 $text"
            "image" -> if (running) "正在查看图片 $text" else "查看了图片 $text"
            "reasoning" -> "分析摘要"
            else -> text
        }
    }
    return listOfNotNull(commandLabel, otherLabels.takeIf { it.isNotBlank() }).joinToString(" · ")
}
private fun compactActivity(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(110)
