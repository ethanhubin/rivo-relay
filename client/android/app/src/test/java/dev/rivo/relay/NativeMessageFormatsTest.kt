package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMessageFormatsTest {
    private fun message(item: JSONObject, status: String = "completed"): NativeMessage = sessionSnapshot(JSONObject()
        .put("thread", JSONObject().put("id", "thread").put("turns", JSONArray().put(JSONObject()
            .put("id", "turn").put("status", status).put("items", JSONArray().put(item)))))).messages.single()

    @Test fun dynamicOutputsKeepTextAndDoNotDumpBinaryAndFailureWinsOverCompletedStatus() {
        val raw = JSONObject("""{"id":"tool","type":"dynamicToolCall","tool":"render","status":"completed","success":false,
          "arguments":{"size":1},"contentItems":[{"type":"inputText","text":"render failed"},{"type":"inputImage","imageUrl":"data:image/png;base64,opaque"},{"type":"inputAudio","audioUrl":"data:audio/wav;base64,opaque"}]}""")
        val parsed = message(raw)
        assertEquals("failed", parsed.activity!!.status)
        assertTrue(parsed.activity!!.detail.contains("render failed"))
        assertTrue(parsed.activity!!.detail.contains("[图片]"))
        assertTrue(parsed.activity!!.detail.contains("[音频]"))
        assertFalse(parsed.text.contains("base64"))
        assertTrue(parsed.raw.toString().contains("base64"))
    }

    @Test fun mcpContentAndStructuredResultsRemainReadableWithoutBinaryDump() {
        val parsed = message(JSONObject("""{"id":"mcp","type":"mcpToolCall","status":"completed","server":"files","tool":"read",
          "result":{"content":[{"type":"text","text":"result text"},{"type":"image","mimeType":"image/png","data":"opaque-binary"},{"type":"resource","resource":{"uri":"fixture://notes","text":"resource text"}}],"structuredContent":{"count":2}}}"""))
        assertTrue(parsed.activity!!.detail.contains("result text"))
        assertTrue(parsed.activity!!.detail.contains("resource text"))
        assertTrue(parsed.activity!!.detail.contains("count"))
        assertFalse(parsed.activity!!.detail.contains("opaque-binary"))
        assertEquals("completed", parsed.activity!!.status)
        parsed.raw.put("error", JSONObject().put("message", "tool rejected"))
        assertEquals("failed", nativeActivity(parsed.raw)!!.status)
    }

    @Test fun functionOutputSupportsMixedAndObjectResultsWithoutSilentlyDroppingParts() {
        val parsed = message(JSONObject("""{"id":"out","type":"functionCallOutput","name":"fixture","output":["raw text",{"type":"text","text":"text part"},{"type":"futurePart","value":"retain"},17]}"""))
        for (expected in listOf("raw text", "text part", "futurePart", "retain", "17")) assertTrue(parsed.activity!!.detail.contains(expected))
        parsed.raw.put("output", JSONObject().put("summary", "structured result"))
        assertTrue(nativeActivity(parsed.raw)!!.detail.contains("structured result"))
    }

    @Test fun futureAndMissingStatusesAreUnknownAndNeverCountAsSuccess() {
        val missing = message(JSONObject("""{"id":"cmd","type":"commandExecution","command":"check"}"""))
        val future = message(JSONObject("""{"id":"future","type":"futureTool","status":"completed","detail":"keep"}"""))
        assertEquals("unknown", missing.activity!!.status)
        assertEquals("unknown", future.activity!!.status)
        assertTrue(future.activity!!.detail.contains("keep"))
        assertEquals("运行了 1 条命令 · 成功 0 · 失败 0 · 状态未知 1", nativeActivityGroupLabel(listOf(missing)))
    }

    @Test fun terminalTurnProjectionSurvivesLateOutputDeltasWithoutMutatingRawStatus() {
        for (terminal in listOf("completed", "interrupted")) {
            val parsed = message(JSONObject("""{"id":"cmd","type":"commandExecution","command":"check","status":"inProgress","aggregatedOutput":"old"}"""), terminal)
            val state = SessionSnapshot("thread", "", "idle", "", listOf(parsed), emptyList(), JSONObject())
            val updated = state.applyLiveDelta(JSONObject("""{"method":"item/commandExecution/outputDelta","params":{"threadId":"thread","turnId":"turn","itemId":"cmd","delta":" new","relayOffset":3}}"""))!!.messages.single()
            assertEquals(if (terminal == "completed") "unknown" else "failed", updated.activity!!.status)
            assertEquals("inProgress", updated.raw.string("status"))
            assertEquals("old new", updated.raw.string("aggregatedOutput"))
        }
    }

    @Test fun subagentKindAndCollaborationResultsAreNotLost() {
        val started = message(JSONObject("""{"id":"child","type":"subAgentActivity","kind":"started","agentPath":"worker","agentThreadId":"child-thread"}"""), "inProgress")
        assertEquals("running", started.activity!!.status)
        assertTrue(started.activity!!.detail.contains("child-thread"))
        val failed = message(JSONObject("""{"id":"call","type":"collabAgentToolCall","status":"interrupted","tool":"wait","agentsStates":{"child-thread":{"status":"errored","message":"failure reason"}}}"""))
        assertEquals("failed", failed.activity!!.status)
        assertTrue(failed.activity!!.detail.contains("failure reason"))
    }

    @Test fun webResultsReviewAndCompactionHaveUsefulProjection() {
        val web = message(JSONObject("""{"id":"web","type":"webSearch","query":"query","action":{"type":"search"},"results":[{"url":"https://example.test/result","title":"Result"}]}"""))
        assertTrue(web.activity!!.detail.contains("https://example.test/result"))
        val review = message(JSONObject("""{"id":"review","type":"enteredReviewMode","review":"review the patch"}"""))
        assertEquals("review", review.activity!!.kind)
        assertEquals("review the patch", review.activity!!.summary)
        assertEquals("context", message(JSONObject("""{"id":"compact","type":"contextCompaction"}""")).activity!!.kind)
    }

    @Test fun fileChangeRetainsBothDiffAndStreamedOutput() {
        val parsed = message(JSONObject("""{"id":"patch","type":"fileChange","status":"failed","changes":[{"path":"/tmp/file","diff":"+line"}],"output":"patch conflict details"}"""))
        assertTrue(parsed.activity!!.detail.contains("+line"))
        assertTrue(parsed.activity!!.detail.contains("patch conflict details"))
        assertTrue(parsed.text.contains("patch conflict details"))
        assertTrue(nativeActivityGroupLabel(listOf(parsed)).contains("未完成"))
    }
}
