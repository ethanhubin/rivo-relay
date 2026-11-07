package dev.rivo.relay

import dev.rivo.relay.data.queueTask
import dev.rivo.relay.ui.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexComposerParityTest {
    @Test fun modelSwitchPreservesCommonEffortAndUsesOfficialFallback() {
        val model = JSONObject("""{"supportedReasoningEfforts":[{"reasoningEffort":"medium"},{"reasoningEffort":"high"}],"defaultReasoningEffort":"medium"}""")
        assertEquals("high", reasoningEffortForModel(model, "high"))
        assertEquals("medium", reasoningEffortForModel(model, "xhigh"))
        assertNull(reasoningEffortForModel(null, "high"))
    }
    @Test fun queueWithdrawalPreservesFilesImagesAndSettings() {
        val task = queueTask(JSONObject("""{"id":"queued","text":"old","input":[{"type":"text","text":"first"},{"type":"image","url":"data:image/jpeg;base64,AQID"},{"type":"mention","name":"file","path":"/project/file"},{"type":"text","text":"second"}],"model":"model","effort":"high","mode":"plan","serviceTier":"priority"}"""))
        val draft = queueTaskDraft(task)
        assertEquals("first\nsecond", draft.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), draft.images.single().bytes)
        assertEquals("/project/file", draft.files.single().getString("path"))
        assertEquals("model", draft.model); assertEquals("high", draft.effort)
        assertEquals("plan", draft.mode); assertEquals("priority", draft.serviceTier)
    }
    @Test fun linkedDocumentsDecodeAndNormalizeWithoutTreatingPlusAsSpace() {
        assertEquals("/project/notes/a+b c.md", nativeFilePath("../notes/./a+b%20c.md?download=1#section", "/project/docs"))
        assertNull(nativeFilePath("invalid%zz.md", "/project"))
    }
    @Test fun inlineImagesUseMarkdownAnnotationsAndIgnoreCode() {
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), markdownImageSources("![result](/tmp/a.png)\n[图片](/tmp/b.png)\n`![literal](/tmp/c.png)`\n```\n![code](/tmp/d.png)\n```"))
    }
    @Test fun attachmentOnlyTurnIncludesVisibleFallbackPrompt() {
        val inputs = nativeInputs("", emptyList(), listOf(JSONObject().put("type", "mention").put("path", "/project/a.txt")))
        assertEquals("请阅读附加的文件。", inputs.getJSONObject(0).getString("text"))
        assertEquals("mention", inputs.getJSONObject(1).getString("type"))
    }
    @Test fun malformedNativeApprovalChoicesRemainUnsupportedInsteadOfCrashing() {
        assertNull(approvalLabel(JSONObject().put("applyNetworkPolicyAmendment", JSONObject())))
        assertNull(approvalLabel(JSONObject().put("approved_execpolicy_amendment", true)))
        assertNull(approvalLabel(JSONObject().put("acceptWithExecpolicyAmendment", JSONObject())))
    }
}
