package dev.rivo.relay

import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeActivityProjectionTest {
    private fun snapshot(vararg turns: JSONObject) = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "t").put("turns", JSONArray(turns.toList()))))
    private fun turn(id: String, vararg items: JSONObject) = JSONObject().put("id", id).put("items", JSONArray(items.toList()))
    private fun command(id: String, command: String) = JSONObject().put("id", id).put("type", "commandExecution").put("command", command).put("aggregatedOutput", "result").put("status", "completed")

    @Test fun adjacentToolsCollapseByNativeTurnWithoutLosingIdsOrOutputs() {
        val state = snapshot(turn("one", command("a", "first"), command("b", "second"), command("c", "third")), turn("two", command("d", "fourth")))
        val rows = nativeTimelineRows(state.messages)
        assertEquals(2, rows.size)
        val first = rows.first() as NativeTimelineRow.Activities
        assertEquals(listOf("a", "b", "c"), first.messages.map { it.id })
        assertEquals("运行了 3 条命令", nativeActivityGroupLabel(first.messages))
        assertEquals("result", first.messages.first().activity?.detail)
        assertEquals("运行了 fourth", nativeActivityGroupLabel((rows.last() as NativeTimelineRow.Activities).messages))
    }

    @Test fun processMessagesAndFinalResponsesKeepTheirNativePositionsBetweenGroups() {
        val process = JSONObject().put("id", "p").put("type", "agentMessage").put("phase", "commentary").put("text", "正在检查布局")
        val final = JSONObject().put("id", "f").put("type", "agentMessage").put("phase", "final_answer").put("text", "检查完成")
        val rows = nativeTimelineRows(snapshot(turn("one", command("a", "first"), process, command("b", "second"), final)).messages)
        assertEquals(4, rows.size)
        assertEquals("p", (rows[1] as NativeTimelineRow.Message).message.id)
        assertEquals("commentary", (rows[1] as NativeTimelineRow.Message).message.activity?.kind)
        assertEquals("f", (rows[3] as NativeTimelineRow.Message).message.id)
    }

    @Test fun hookDiagnosticsAreNotConversationAndFunctionOutputDoesNotPrintBinaryPayloads() {
        val hook = JSONObject().put("id", "hook").put("type", "hookPrompt").put("fragments", JSONArray().put("internal hook"))
        val output = JSONObject().put("id", "out").put("type", "functionCallOutput").put("name", "tool")
            .put("output", JSONArray().put(JSONObject().put("type", "input_text").put("text", "readable result"))
                .put(JSONObject().put("type", "encrypted_content").put("data", "opaque payload")))
        val messages = snapshot(turn("one", hook, output)).messages
        assertEquals(listOf("out"), messages.map { it.id })
        assertEquals("readable result\n[加密内容]", messages.single().activity?.detail)
        assertTrue(messages.single().raw.toString().contains("opaque payload"))
    }

    @Test fun imageInspectionIsToolActivityButAttachmentsAndGeneratedImagesStayMessages() {
        for (type in listOf("imageView", "image_view")) {
            val image = JSONObject().put("id", "inspect").put("type", type).put("path", "/tmp/check.png")
            val user = JSONObject().put("id", "user").put("type", "userMessage").put("content", JSONArray().put(JSONObject().put("type", "image").put("url", "data:image/png;base64,test")))
            val generated = JSONObject().put("id", "generated").put("type", "imageGeneration").put("savedPath", "/tmp/result.png")
            val rows = nativeTimelineRows(snapshot(turn("one", command("before", "test"), image, command("after", "done"), user, generated)).messages)
            assertEquals(3, rows.size)
            val activity = (rows.first() as NativeTimelineRow.Activities).messages[1].activity!!
            assertEquals("image", activity.kind)
            assertEquals(listOf("/tmp/check.png"), activity.filePaths)
            assertTrue(rows[1] is NativeTimelineRow.Message)
            assertTrue(rows[2] is NativeTimelineRow.Message)
        }
    }

    @Test fun commandExitCodePreservesZeroNonzeroAndMissingWithoutInventingReason() {
        assertEquals(0, nativeActivity(command("ok", "read").put("exitCode", 0))?.exitCode)
        val failed = nativeActivity(command("bad", "read").put("status", "failed").put("exitCode", 2))!!
        assertEquals(2, failed.exitCode)
        assertEquals("failed", failed.status)
        assertEquals("result", failed.detail)
        assertNull(nativeActivity(command("missing", "read"))?.exitCode)
        assertNull(nativeActivity(command("null", "read").put("exitCode", JSONObject.NULL))?.exitCode)
    }

    @Test fun unknownActivitiesRetainNativeTypeAndRawDetail() {
        val item = JSONObject().put("id", "new").put("type", "futureNativeType").put("value", "keep this")
        val activity = snapshot(turn("one", item)).messages.single().activity!!
        assertTrue(activity.title.contains("futureNativeType")); assertTrue(activity.detail.contains("keep this"))
    }

    @Test fun qiguangTableRuleKeepsShortDataAsGridAndLongNarrowRowsAsCards() {
        val short = parseChatMarkdownBlocks("| A | B |\n|---|---|\n| 1 | 2 |").single() as ChatMarkdownBlock.Table
        val long = parseChatMarkdownBlocks("| A | B |\n|---|---|\n| title | a much longer description |").single() as ChatMarkdownBlock.Table
        assertFalse(shouldRenderMarkdownTableAsCards(short)); assertTrue(shouldRenderMarkdownTableAsCards(long))
    }

    @Test fun compactCommandLabelCollapsesWhitespaceButPreservesRegexEscapes() {
        val original = "rg '\\s+' \n\tfile.txt"
        val message = snapshot(turn("one", command("regex", original))).messages.single()
        assertEquals("运行了 rg '\\s+' file.txt", nativeActivityGroupLabel(listOf(message)))
        assertEquals(original, message.activity?.summary)
        assertEquals(original, message.raw.string("command"))
    }
}
