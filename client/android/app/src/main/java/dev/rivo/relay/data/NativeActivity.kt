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
        "reasoning" -> nativeText(item.opt("summary")).ifBlank { nativeText(item.opt("content")) }.ifBlank { item.string("text") }
        "file" -> paths.joinToString(" · ") { it.substringAfterLast('/').substringAfterLast('\\') }
        "commentary" -> item.string("text")
        "web" -> item.string("query").ifBlank { nativeText(item.opt("action")) }
        "collaboration" -> item.string("prompt").ifBlank { item.string("agentPath") }.ifBlank { "协作代理正在处理" }
        "plan" -> item.string("text").ifBlank { item.objects("plan").joinToString("\n") { it.string("step") } }
        "tool" -> listOf(item.string("server").ifBlank { item.string("namespace") }, item.string("tool").ifBlank { item.string("name") }).filter(String::isNotBlank).joinToString(" / ")
        else -> item.string("text")
    }.ifBlank { title }
    val detail = when (type) {
        "commandexecution" -> item.string("aggregatedOutput")
        "filechange" -> item.objects("changes").joinToString("\n\n") { it.string("path") + "\n" + it.string("diff") }
        "functioncalloutput" -> nativeFunctionOutput(item.opt("output"))
        "mcptoolcall", "dynamictoolcall" -> listOf("arguments", "result", "error").mapNotNull { key -> item.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { "$key\n${nativeText(it)}" } }.joinToString("\n\n")
        else -> if (kind == "unknown") item.toString(2) else ""
    }
    val status = when (item.string("status").replace("_", "").lowercase()) {
        "inprogress", "running", "started", "pending" -> "running"
        "failed", "error", "declined", "interrupted" -> "failed"
        else -> "completed"
    }
    return NativeActivity(kind, title, summary, detail, status, paths,
        if (kind == "command") (item.opt("exitCode") as? Number)?.toInt() else null)
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
fun nativeTimelineRows(messages: List<NativeMessage>): List<NativeTimelineRow> {
    val rows = mutableListOf<NativeTimelineRow>()
    val pending = mutableListOf<NativeMessage>()
    fun flush() { if (pending.isNotEmpty()) { rows += NativeTimelineRow.Activities(pending.toList()); pending.clear() } }
    messages.forEach { message ->
        if (message.activity != null && message.activity.kind != "commentary") {
            if (pending.lastOrNull()?.turnId?.let { it != message.turnId } == true) flush()
            pending += message
        } else { flush(); rows += NativeTimelineRow.Message(message) }
    }
    flush(); return rows
}

fun nativeActivityGroupLabel(messages: List<NativeMessage>): String {
    val activities = messages.mapNotNull { it.activity }
    if (activities.isEmpty()) return "Codex 活动"
    if (activities.all { it.kind == "command" } && activities.size > 1) {
        val last = activities.last()
        return if (last.status == "running") "正在运行 ${compactActivity(last.summary)} · 本组 ${activities.size} 条命令" else "运行了 ${activities.size} 条命令"
    }
    return activities.joinToString(" · ") { activity ->
        val text = compactActivity(activity.summary)
        val running = activity.status == "running"
        when (activity.kind) {
            "command" -> if (running) "正在运行 $text" else "运行了 $text"
            "file" -> if (running) "正在编辑 $text" else "编辑了 $text"
            "web" -> if (running) "正在检索 $text" else "检索了 $text"
            "tool", "collaboration" -> if (running) "正在使用 $text" else "使用了 $text"
            "image" -> if (running) "正在查看图片 $text" else "查看了图片 $text"
            "reasoning" -> "分析摘要"
            else -> text
        }
    }
}
private fun compactActivity(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(110)
