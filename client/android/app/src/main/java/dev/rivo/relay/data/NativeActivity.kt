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
        "websearch" -> "web"; "plan" -> "plan"; "enteredreviewmode" -> "review"; "contextcompaction" -> "context"; else -> "unknown"
    }
    val title = when (type) {
        "imageview" -> "查看图片"; "agentmessage" -> "过程消息"; "reasoning" -> "分析摘要"; "commandexecution" -> "命令执行"
        "filechange" -> "文件变更"; "mcptoolcall" -> "MCP 工具调用"; "dynamictoolcall" -> "工具调用"
        "functioncalloutput" -> "工具结果"; "sleep" -> "等待"; "websearch" -> "网页检索"
        "collabagenttoolcall", "collabtoolcall", "subagentactivity" -> "协作代理"
        "plan" -> "执行计划"; "enteredreviewmode" -> "进入审查"; "contextcompaction" -> "上下文压缩"; else -> "Codex 活动 · ${item.string("type")}"
    }
    val paths = if (kind == "file") item.objects("changes").map { it.string("path") }.filter(String::isNotBlank).distinct() else if (type == "imageview") listOf(item.string("path")).filter(String::isNotBlank) else emptyList()
    val summary = when (kind) {
        "image" -> item.string("path").substringAfterLast('/').ifBlank { "图片" }
        "command" -> item.string("command")
        "reasoning" -> nativeReasoningText(item)
        "file" -> paths.joinToString(" · ") { it.substringAfterLast('/').substringAfterLast('\\') }
        "commentary" -> item.string("text")
        "web" -> item.string("query").ifBlank { nativeText(item.opt("action")) }
        "collaboration" -> item.string("prompt").ifBlank { item.string("task") }.ifBlank { item.string("agentPath") }.ifBlank { item.string("agentThreadId") }.ifBlank { item.string("tool") }.ifBlank { "协作代理记录" }
        "plan" -> item.string("text").ifBlank { item.objects("plan").joinToString("\n") { it.string("step") } }
        "review" -> item.string("review")
        "context" -> "原生上下文压缩记录"
        "tool" -> if (type == "sleep") (item.opt("durationMs") as? Number)?.toLong()?.takeIf { it >= 0 }
            ?.let { "等待 $it 毫秒" }.orEmpty() else listOf(item.string("server").ifBlank { item.string("namespace") }, item.string("tool").ifBlank { item.string("name") }).filter(String::isNotBlank).joinToString(" / ")
        else -> item.string("text")
    }.ifBlank { if (kind == "reasoning") "" else title }
    val detail = when (type) {
        "commandexecution" -> item.string("aggregatedOutput")
        "filechange" -> listOf(item.objects("changes").joinToString("\n\n") { it.string("path") + "\n" + it.string("diff") },
            item.string("output")).filter(String::isNotBlank).joinToString("\n\n")
        "functioncalloutput" -> nativeFunctionOutput(item.opt("output"))
        "mcptoolcall", "dynamictoolcall" -> listOf("arguments", "contentItems", "result", "error").mapNotNull { key ->
            item.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { value ->
                val text = if (key in setOf("contentItems", "result")) nativeFunctionOutput(value) else nativeText(value)
                text.takeIf(String::isNotBlank)?.let { "$key\n$it" }
            }
        }.joinToString("\n\n")
        "collabagenttoolcall", "collabtoolcall", "subagentactivity" -> nativeFields(item,
            listOf("tool", "kind", "agentPath", "agentThreadId", "senderThreadId", "receiverThreadIds", "agentsStates", "model", "reasoningEffort"))
        "websearch" -> nativeFields(item, listOf("action", "results"))
        else -> if (kind == "unknown") item.toString(2) else ""
    }
    val rawStatus = (if (type == "subagentactivity") item.string("kind") else item.string("status")).replace("_", "").lowercase()
    val stateless = type in setOf("agentmessage", "reasoning", "functioncalloutput", "plan", "websearch", "imageview", "sleep", "enteredreviewmode", "contextcompaction")
    val explicitFailure = (type == "dynamictoolcall" && item.opt("success") == false) ||
        (type == "mcptoolcall" && (item.optJSONObject("result")?.optBoolean("isError") == true ||
            item.opt("error")?.takeUnless { it == JSONObject.NULL }?.let { nativeText(it).isNotBlank() } == true)) ||
        (kind == "command" && (item.opt("exitCode") as? Number)?.toInt()?.let { it != 0 } == true)
    val status = when {
        kind == "unknown" -> "unknown"
        explicitFailure -> "failed"
        rawStatus in setOf("inprogress", "running", "started", "pending", "pendinginit") -> "running"
        rawStatus in setOf("failed", "error", "errored", "declined", "interrupted", "canceled", "cancelled", "notfound") -> "failed"
        rawStatus in setOf("completed", "succeeded", "success") -> "completed"
        rawStatus == "interacted" && type == "subagentactivity" -> "completed"
        rawStatus.isBlank() && stateless -> "completed"
        else -> "unknown"
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

/** A terminal turn stops stale tool spinners without asserting that the tool succeeded. */
internal fun nativeActivityForTurn(item: JSONObject, turnStatus: String): NativeActivity? = nativeActivity(item)?.let { activity ->
    if (activity.status == "running" && turnStatus in setOf("completed", "failed", "interrupted", "canceled", "cancelled"))
        activity.copy(status = if (turnStatus == "completed") "unknown" else "failed") else activity
}

private fun nativeFields(item: JSONObject, keys: List<String>): String = keys.mapNotNull { key ->
    item.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { value -> nativeText(value).takeIf(String::isNotBlank)?.let { "$key\n$it" } }
}.joinToString("\n\n")

private fun nativeText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until value.length()).map { nativeText(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n")
    is JSONObject -> value.toString(2)
    else -> value.toString()
}

/** Render content payloads without printing binary data as JSON or dropping structured results. */
private fun nativeFunctionOutput(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value
    is JSONArray -> (0 until value.length()).map { nativeFunctionOutput(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n")
    is JSONObject -> when (value.string("type").replace("_", "").lowercase()) {
        "inputtext", "outputtext", "text" -> value.string("text")
        "inputimage", "image" -> "[图片]"
        "inputaudio", "audio" -> "[音频]"
        "encryptedcontent" -> "[加密内容]"
        "resourcelink" -> listOf(value.string("name"), value.string("uri")).filter(String::isNotBlank).joinToString("\n").ifBlank { "[资源链接]" }
        "resource" -> value.optJSONObject("resource")?.let { resource ->
            listOf(resource.string("uri"), resource.string("text").ifBlank { "[二进制资源]" }).filter(String::isNotBlank).joinToString("\n")
        } ?: "[资源]"
        else -> if (value.has("content") || value.has("structuredContent"))
            listOf(nativeFunctionOutput(value.opt("content")), nativeText(value.opt("structuredContent"))).filter(String::isNotBlank).joinToString("\n\n")
                .ifBlank { value.toString(2) }
            else value.toString(2)
    }
    else -> value.toString()
}

sealed interface NativeTimelineRow {
    val key: String
    data class Message(val message: NativeMessage): NativeTimelineRow { override val key = "message:${message.turnId}:${message.id}" }
    data class Activities(val messages: List<NativeMessage>): NativeTimelineRow { override val key = "activities:${messages.first().turnId}:${messages.first().id}" }
    data class Notice(val turn: NativeTurnSettings): NativeTimelineRow { override val key = "notice:${turn.turnId}" }
}

/** Same-turn adjacent activity runs collapse together; user/final/process messages keep their positions. */
fun nativeTimelineRows(messages: List<NativeMessage>, activeTurnId: String? = null,
    turnSettings: List<NativeTurnSettings> = emptyList()): List<NativeTimelineRow> {
    val rows = mutableListOf<NativeTimelineRow>()
    val pending = mutableListOf<NativeMessage>()
    fun flush() { if (pending.isNotEmpty()) { rows += NativeTimelineRow.Activities(pending.toList()); pending.clear() } }
    messages.forEach { message ->
        // Internal reasoning and sleep stay in history without splitting visible tool groups.
        if (message.activity?.kind == "reasoning" || message.type in setOf("reasoning", "sleep")) return@forEach
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
    val grouped = rows.flatMapIndexed { index, row ->
        if (row !is NativeTimelineRow.Activities || row.messages.first().turnId != activeTurnId ||
            lastByTurn[row.messages.first().turnId] != index) listOf(row)
        else {
            val groups = mutableListOf<MutableList<NativeMessage>>()
            row.messages.forEach { message ->
                val current = groups.lastOrNull()
                // Only command groups ever grow; other activities stay singletons.
                // Checking their first item avoids rescanning long command runs.
                if (message.activity?.kind == "command" && current?.firstOrNull()?.activity?.kind == "command") current += message
                else groups += mutableListOf(message)
            }
            groups.map { NativeTimelineRow.Activities(it.toList()) }
        }
    }
    if (turnSettings.isEmpty()) return grouped
    fun rowTurn(row: NativeTimelineRow): String = when (row) {
        is NativeTimelineRow.Message -> row.message.turnId
        is NativeTimelineRow.Activities -> row.messages.first().turnId
        is NativeTimelineRow.Notice -> row.turn.turnId
    }
    val nativeTurns = turnSettings.distinctBy { it.turnId }
    val firstRow = mutableMapOf<String, Int>()
    val lastRow = mutableMapOf<String, Int>()
    grouped.forEachIndexed { index, row ->
        val turnId = rowTurn(row)
        firstRow.putIfAbsent(turnId, index)
        lastRow[turnId] = index
    }
    val notices = mutableMapOf<Int, MutableList<NativeTimelineRow.Notice>>()
    val followingRow = arrayOfNulls<Int>(nativeTurns.size)
    var nextRow: Int? = null
    for (index in nativeTurns.indices.reversed()) {
        followingRow[index] = nextRow
        firstRow[nativeTurns[index].turnId]?.let { nextRow = it }
    }
    var precedingEnd: Int? = null
    nativeTurns.forEachIndexed { turnIndex, turn ->
        if (turn.status in setOf("failed", "interrupted", "canceled", "cancelled") || turn.error != null) {
            // Insert around existing rows, never rebuild them by turn membership:
            // pending overlays already have meaningful startsThread/item anchors.
            val boundary = lastRow[turn.turnId]?.plus(1)
                ?: followingRow[turnIndex]
                ?: precedingEnd
                ?: grouped.size
            notices.getOrPut(boundary) { mutableListOf() }.add(NativeTimelineRow.Notice(turn))
        }
        lastRow[turn.turnId]?.let { precedingEnd = it + 1 }
    }
    return buildList {
        for (index in 0..grouped.size) {
            addAll(notices[index].orEmpty())
            if (index < grouped.size) add(grouped[index])
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
        val unknown = commands.count { it.status == "unknown" && (it.exitCode == null || it.exitCode == 0) }
        val succeeded = commands.size - running - failed - unknown
        "运行了 ${commands.size} 条命令 · 成功 $succeeded · 失败 $failed" +
            (if (running > 0) " · 进行中 $running" else "") + (if (unknown > 0) " · 状态未知 $unknown" else "")
    }
    if (commands.size == activities.size) return commandLabel!!
    val otherLabels = activities.filter { it.kind != "command" }.joinToString(" · ") { activity ->
        val text = compactActivity(activity.summary)
        val running = activity.status == "running"
        if (activity.status == "unknown") return@joinToString "$text（状态未知）"
        if (activity.status == "failed") return@joinToString "$text（未完成）"
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
