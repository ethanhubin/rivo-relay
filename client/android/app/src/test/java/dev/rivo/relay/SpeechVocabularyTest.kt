package dev.rivo.relay

import dev.rivo.relay.data.*
import org.junit.Assert.*
import org.junit.Test

class SpeechVocabularyTest {
    @Test fun vocabularyPrecedesDraftAndMultilineWordsStayOnOneHintLine() {
        assertEquals("固定词表：ComfyUI、LoRA\n编辑图片", speechContext(" ComfyUI \n\n LoRA ", "编辑图片"))
        assertEquals("固定词表：词表", speechContext("词表", ""))
    }
    @Test fun fullVocabularyIsRetainedAndOnlyDraftTailIsTrimmed() {
        val context = speechContext("词".repeat(390), "草稿".repeat(400))
        assertEquals(400, context.length)
        assertEquals("固定词表：" + "词".repeat(390) + "\n草稿草稿", context)
        assertEquals("固定词表：" + "词".repeat(395), speechContext("词".repeat(395), "草稿"))
    }
    @Test fun clearingVocabularyLeavesOnlyBoundedDraft() {
        assertEquals("", speechContext(" \n ", ""))
        assertEquals("稿".repeat(400), speechContext("", "稿".repeat(500)))
    }
}
