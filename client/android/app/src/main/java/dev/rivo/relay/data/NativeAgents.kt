package dev.rivo.relay.data

import org.json.JSONObject

/** Tool completion and child execution are deliberately separate states. */
data class NativeAgent(val threadId: String, val name: String, val status: String, val message: String)
data class NativeAgentActivity(val summary: String, val prompt: String, val model: String, val agents: List<NativeAgent>)

private fun agentName(path: String): String = path.trim().trimEnd('/').substringAfterLast('/').takeUnless { it == "root" }.orEmpty()

fun nativeAgentActivity(item: JSONObject): NativeAgentActivity? {
    val type = item.string("type").replace("_", "").lowercase()
    if (type !in setOf("collabagenttoolcall", "collabtoolcall", "subagentactivity")) return null
    if (type == "subagentactivity") {
        val name = agentName(item.string("agentPath"))
        val kind = item.string("kind")
        val summary = when (kind) {
            "started" -> "${name.ifBlank { "代理" }} 开始工作"
            "completed" -> "${name.ifBlank { "代理" }} 已完成"
            "interrupted" -> "${name.ifBlank { "代理" }} 已中断"
            "interacted" -> if (name.isBlank()) "已向父代理发送消息" else "已向 $name 发送消息"
            else -> "${name.ifBlank { "代理" }} 状态更新"
        }
        return NativeAgentActivity(summary, "", "", listOf(NativeAgent(item.string("agentThreadId"),
            name.ifBlank { "代理" }, when (kind) { "started" -> "running"; "completed" -> "completed"; "interrupted" -> "interrupted"; else -> "unknown" }, "")))
    }
    val states = item.optJSONObject("agentsStates") ?: JSONObject()
    val receivers = item.optJSONArray("receiverThreadIds")
    val ids = buildList {
        if (receivers != null) for (i in 0 until receivers.length()) (receivers.opt(i) as? String)?.takeIf(String::isNotBlank)?.let(::add)
        addAll(states.keys().asSequence().toList())
    }.distinct()
    val threads = item.objects("receiverThreads").associateBy { it.string("threadId") }
    val agents = ids.map { id ->
        val thread = threads[id]?.optJSONObject("thread")
        val state = states.optJSONObject(id)
        NativeAgent(id, thread?.string("agentNickname")?.takeIf(String::isNotBlank)
            ?: thread?.string("name")?.takeIf(String::isNotBlank) ?: "代理 ${id.take(8)}",
            state?.string("status").orEmpty().ifBlank { "unknown" }, state?.string("message").orEmpty())
    }
    val action = when (item.string("tool")) {
        "spawnAgent", "spawn_agent" -> "创建代理"
        "sendInput", "send_input" -> "发送消息"
        "resumeAgent", "resume_agent" -> "恢复代理"
        "closeAgent", "close_agent" -> "关闭代理"
        "wait" -> "等待代理"
        else -> "代理操作"
    }
    val verb = when (item.string("status")) {
        "inProgress", "in_progress", "running" -> "正在$action"
        "completed" -> "已$action"
        "failed" -> "$action 失败"
        else -> "$action · 状态待核验"
    }
    return NativeAgentActivity(verb + if (agents.isEmpty()) "" else " · ${agents.joinToString("、") { it.name }}",
        item.string("prompt").ifBlank { item.string("task") },
        listOf(item.string("model"), item.string("reasoningEffort")).filter(String::isNotBlank).joinToString(" · "), agents)
}

fun nativeAgentStatusLabel(status: String): String = when (status) {
    "pendingInit" -> "等待启动"; "running" -> "工作中"; "completed" -> "已完成"
    "interrupted" -> "已中断"; "shutdown" -> "已关闭"; "errored", "failed" -> "失败"; "notFound" -> "未找到"
    else -> "状态待核验"
}

/** Hidden wait records still update the child states shown in that turn's cards. */
fun nativeAgentDetailsByMessage(messages: List<NativeMessage>): Map<Pair<String, String>, NativeAgentActivity> {
    val result = linkedMapOf<Pair<String, String>, NativeAgentActivity>()
    messages.groupBy { it.turnId }.forEach { (turn, items) ->
        val latest = linkedMapOf<String, NativeAgent>()
        items.forEach messageLoop@{ message ->
            val activity = nativeAgentActivity(message.raw) ?: return@messageLoop
            result[turn to message.id] = activity
            activity.agents.forEach agentLoop@{ agent ->
                val previous = latest[agent.threadId]
                // Messaging a running child does not finish or reset its execution.
                if (message.raw.string("kind") == "interacted") return@agentLoop
                latest[agent.threadId] = agent.copy(
                    name = if (agent.name == "代理" || agent.name == "代理 ${agent.threadId.take(8)}") previous?.name ?: agent.name else agent.name,
                    status = agent.status.takeUnless { it == "unknown" } ?: previous?.status ?: "unknown",
                    message = agent.message.ifBlank { if (agent.status in setOf("running", "pendingInit")) "" else previous?.message.orEmpty() })
            }
        }
        items.forEach detailLoop@{ message ->
            val key = turn to message.id
            val activity = result[key] ?: return@detailLoop
            result[key] = activity.copy(agents = activity.agents.map { agent ->
                val current = latest[agent.threadId] ?: agent
                if (current.status in setOf("running", "pendingInit") && message.turnStatus in setOf("completed", "failed", "interrupted", "canceled", "cancelled"))
                    current.copy(status = "unknown") else current
            })
        }
    }
    return result
}
