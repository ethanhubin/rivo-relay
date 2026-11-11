package dev.rivo.relay.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePromptNotesTest {
    @Test fun removesValidationAndEverythingAfterIt() {
        for (heading in listOf("验证：回归通过", "验证: passed", "## 验证", "**验证**：通过")) {
            assertEquals("- 修复：更新提示", updatePromptNotes("- 修复：更新提示\n\n$heading\n\n节点说明\n真机范围"))
        }
    }

    @Test fun preservesChangesMentioningValidation() {
        val notes = "# 0.2.115\n\n- 修复：支持进程身份验证。\n- 优化：保留已验证路由。"
        assertEquals(notes, updatePromptNotes(notes))
        assertEquals("", updatePromptNotes("验证：通过"))
        assertEquals("", updatePromptNotes("  "))
    }
}
