package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAgentsTest {
    private fun item(text: String) = JSONObject(text)
    private fun messages(vararg items: JSONObject) = sessionSnapshot(JSONObject().put("thread", JSONObject()
        .put("id", "parent").put("turns", org.json.JSONArray().put(JSONObject().put("id", "turn")
            .put("status", "inProgress").put("items", org.json.JSONArray(items.toList())))))).messages

    @Test fun completedSpawnDoesNotMeanCompletedChild() {
        val activity = nativeAgentActivity(item("""{"type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","prompt":"检查服务端","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}},"receiverThreads":[{"threadId":"child","thread":{"agentNickname":"审核员"}}]}"""))!!
        assertEquals("running", activity.agents.single().status)
        assertEquals("审核员", activity.agents.single().name)
        assertEquals("检查服务端", activity.prompt)
        assertEquals("已创建代理 · 审核员", activity.summary)
    }

    @Test fun lifecycleAndParentMessagesHaveSpecificLabels() {
        for ((kind, label) in mapOf("started" to "开始工作", "completed" to "已完成", "interrupted" to "已中断")) {
            assertEquals("review $label", nativeAgentActivity(item("""{"type":"subAgentActivity","agentPath":"/root/review","kind":"$kind"}"""))!!.summary)
        }
        assertEquals("已向父代理发送消息", nativeAgentActivity(item("""{"type":"subAgentActivity","agentPath":"/root","kind":"interacted"}"""))!!.summary)
    }

    @Test fun hiddenWaitUpdatesResultsAndAgentsRemainSeparateFromTools() {
        val items = messages(
            item("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}}}"""),
            item("""{"id":"cmd","type":"commandExecution","command":"pwd","status":"completed"}"""),
            item("""{"id":"wait","type":"collabAgentToolCall","tool":"wait","status":"completed","agentsStates":{"child":{"status":"errored","message":"检查失败"}}}"""))
        val rows = nativeTimelineRows(items)
        assertEquals(2, rows.size)
        assertEquals("spawn", (rows.first() as NativeTimelineRow.Activities).messages.single().id)
        val agent = nativeAgentDetailsByMessage(items)["turn" to "spawn"]!!.agents.single()
        assertEquals("errored", agent.status)
        assertEquals("检查失败", agent.message)
        assertEquals(3, items.size)
    }

    @Test fun missingStateIsUnknownAndMultipleReceiversAreRetained() {
        val activity = nativeAgentActivity(item("""{"type":"collabAgentToolCall","tool":"sendInput","status":"completed","receiverThreadIds":["one","two","one"],"agentsStates":{"three":{"status":"shutdown"}}}"""))!!
        assertEquals(listOf("one", "two", "three"), activity.agents.map { it.threadId })
        assertEquals("unknown", activity.agents.first().status)
        assertEquals("已关闭", nativeAgentStatusLabel(activity.agents.last().status))
    }
    @Test fun terminalParentDoesNotInventChildSuccess() {
        val original = messages(item("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}}}""")).single()
        val terminal = original.copy(turnStatus = "completed")
        assertEquals("unknown", nativeAgentDetailsByMessage(listOf(terminal))["turn" to "spawn"]!!.agents.single().status)
    }

    @Test fun childRestartClearsOldResultAndInterruptedIsNotToolFailure() {
        val events = messages(
            item("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"completed","message":"旧结果"}}}"""),
            item("""{"id":"restart","type":"subAgentActivity","kind":"started","agentThreadId":"child","agentPath":"/root/review"}"""))
        val child = nativeAgentDetailsByMessage(events)["turn" to "spawn"]!!.agents.single()
        assertEquals("running", child.status)
        assertEquals("", child.message)
        assertEquals("review", child.name)
        assertEquals("interrupted", nativeActivity(item("""{"type":"subAgentActivity","kind":"interrupted"}"""))!!.status)
    }

    @Test fun terminalStatusChangeDoesNotCarryEarlierErrorIntoSuccess() {
        val events = messages(
            item("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"errored","message":"旧错误"}}}"""),
            item("""{"id":"done","type":"subAgentActivity","kind":"completed","agentThreadId":"child","agentPath":"/root/review"}"""))
        val child = nativeAgentDetailsByMessage(events)["turn" to "spawn"]!!.agents.single()
        assertEquals("completed", child.status)
        assertEquals("", child.message)
    }

    @Test fun repeatedTerminalEventRetainsResultForSameStatus() {
        val events = messages(
            item("""{"id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed","receiverThreadIds":["child"],"agentsStates":{"child":{"status":"completed","message":"检查通过"}}}"""),
            item("""{"id":"done","type":"subAgentActivity","kind":"completed","agentThreadId":"child","agentPath":"/root/review"}"""))
        assertEquals("检查通过", nativeAgentDetailsByMessage(events)["turn" to "spawn"]!!.agents.single().message)
    }

}
