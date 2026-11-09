package dev.rivo.relay

import dev.rivo.relay.ui.chat.markdownCopyPlainText
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPlainTextTest {
    @Test fun removesFormattingAndKeepsLinks() {
        assertEquals("标题\n\n• 粗体和代码\n文档 (https://example.com)",
            markdownCopyPlainText("# 标题\n\n- **粗体**和`代码`\n[文档](https://example.com)"))
    }
    @Test fun preservesLiteralCodeAndCredentialCharacters() {
        assertEquals("a_b_c + / =\n**literal**", markdownCopyPlainText("```text\na_b_c + / =\n**literal**\n```"))
    }
    @Test fun tablesBecomeTabSeparatedText() {
        assertEquals("名称\t值\n密钥\tabc_def", markdownCopyPlainText("| 名称 | 值 |\n| --- | --- |\n| **密钥** | `abc_def` |"))
    }
}
