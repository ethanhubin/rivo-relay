package dev.rivo.relay

import dev.rivo.relay.data.NativeMessage
import dev.rivo.relay.data.SessionSnapshot
import dev.rivo.relay.ui.chat.StreamingTextBuffer
import dev.rivo.relay.ui.chat.shouldBufferMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamingTextBufferTest {
    @Test fun burstIsRevealedMonotonicallyAndCatchesUpDuringAnArrivalPause() {
        val buffer = StreamingTextBuffer("已有文字", true)
        val target = "已有文字" + "新增正文".repeat(60)
        buffer.update(target, true, 0)
        assertEquals("已有文字", buffer.text)
        val first = buffer.advance(50)
        assertTrue(first.length in 5 until target.length)
        var previous = first
        for (time in 100L..3000L step 50) {
            val text = buffer.advance(time)
            assertTrue(text.startsWith(previous))
            assertTrue(target.startsWith(text))
            previous = text
        }
        assertEquals(target, buffer.text)
        assertFalse(buffer.pending)
    }

    @Test fun frequentArrivalsDoNotRestartTheDrainClock() {
        val buffer = StreamingTextBuffer("", true)
        repeat(30) { index ->
            buffer.update("字".repeat(index + 1), true, index * 10L)
            if (index % 5 == 4) buffer.advance(index * 10L)
        }
        assertTrue(buffer.text.isNotEmpty())
        assertTrue("字".repeat(30).startsWith(buffer.text))
    }

    @Test fun completionCancellationAndDisabledAnimationFlushTheFullText() {
        val buffer = StreamingTextBuffer("", true)
        buffer.update("待显示".repeat(100), true, 0)
        buffer.advance(50)
        buffer.update("最终完整文本", false, 55)
        assertEquals("最终完整文本", buffer.text)
        assertFalse(buffer.pending)
        buffer.update("重新开始的文本", true, 60)
        assertEquals("重新开始的文本", buffer.text)
    }

    @Test fun correctionsReplaceTheBufferAndExistingHistoryDoesNotReplay() {
        val buffer = StreamingTextBuffer("打开时已有历史", true)
        assertEquals("打开时已有历史", buffer.text)
        buffer.update("打开时已有历史，后续内容", true, 0)
        buffer.update("服务端修正了整段内容", true, 20)
        assertEquals("服务端修正了整段内容", buffer.text)
        assertFalse(buffer.pending)
    }

    @Test fun emojiNeverPublishesAnUnpairedSurrogate() {
        val buffer = StreamingTextBuffer("", true)
        val target = "😀汉字🚀".repeat(25)
        buffer.update(target, true, 0)
        for (time in 50L..3000L step 50) {
            val text = buffer.advance(time)
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            assertTrue(target.startsWith(text))
        }
        assertEquals(target, buffer.text)
    }

    @Test fun statelessItemCompletionFlushesWhileTheTurnRemainsActive() {
        fun snapshot(completed: Boolean) = dev.rivo.relay.data.sessionSnapshot(JSONObject("""
            {"status":"running","activeTurnId":"r","thread":{"id":"t","turns":[
              {"id":"r","status":"inProgress","items":[
                {"id":"a","type":"agentMessage","text":"final answer","relayCompleted":$completed}
              ]}
            ]}}
        """))
        val active = snapshot(false)
        val buffer = StreamingTextBuffer("", true)
        buffer.update(active.messages.single().text, shouldBufferMessage(active.messages.single(), active), 0)
        buffer.advance(50)
        assertTrue(buffer.pending)
        val completed = snapshot(true)
        assertTrue(completed.isOngoing)
        assertFalse(completed.messages.single().raw.has("status"))
        assertFalse(shouldBufferMessage(completed.messages.single(), completed))
        buffer.update(completed.messages.single().text, shouldBufferMessage(completed.messages.single(), completed), 60)
        assertEquals("final answer", buffer.text)
        assertFalse(buffer.pending)
    }

    @Test fun onlyCurrentUnfinishedAssistantOutputIsBuffered() {
        val message = NativeMessage("m", "turn", "agentMessage", "回答", JSONObject().put("phase", "final_answer"), turnStatus = "inProgress")
        val snapshot = SessionSnapshot("s", "", "running", "turn", listOf(message), emptyList(), JSONObject())
        assertTrue(shouldBufferMessage(message, snapshot))
        assertFalse(shouldBufferMessage(message.copy(raw = JSONObject().put("phase", "commentary")), snapshot))
        assertFalse(shouldBufferMessage(message.copy(turnStatus = "completed"), snapshot))
        assertFalse(shouldBufferMessage(message.copy(turnStatus = "interrupted"), snapshot))
        for (terminal in listOf("failed", "canceled", "cancelled")) {
            assertFalse(shouldBufferMessage(message.copy(turnStatus = terminal), snapshot))
            assertFalse(shouldBufferMessage(message.copy(raw = JSONObject().put("status", terminal)), snapshot))
        }
        assertFalse(shouldBufferMessage(message.copy(raw = JSONObject().put("status", "completed")), snapshot))
        assertFalse(shouldBufferMessage(message.copy(turnId = "old"), snapshot))
        assertFalse(shouldBufferMessage(message, snapshot.copy(status = "idle", activeTurnId = "")))
    }
}
