package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.data.*
import dev.rivo.relay.ui.chat.*
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageFormatBoundaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun balancedFileLinkOpensTheCompleteNativePath() {
        val files = mutableListOf<String>()
        compose.setContent { RivoTheme { Surface {
            MessageContent("[源文件](</tmp/a (final).kt:12> \"说明\")", onFile = { files += it })
        } } }
        compose.onNodeWithTag("message_text").performTouchInput { click(Offset(18f, 18f)) }
        compose.runOnIdle { assertEquals(listOf("/tmp/a (final).kt:12"), files) }
    }

    @Test fun fencedImageExamplesDoNotCreateMediaAndTablesRemainVisible() {
        compose.setContent { RivoTheme { Surface { Column {
            MessageContent("~~~markdown\n![example](/tmp/hidden.png)\n~~~\n\nA | B | C\n---|---|---\nshort | row",
                inlineImage = { Text(it, Modifier.testTag("unexpected_inline_image")) })
        } } } }
        compose.onNodeWithTag("unexpected_inline_image").assertDoesNotExist()
        compose.onNodeWithTag("message_text").assertTextContains("![example](/tmp/hidden.png)", substring = true)
        compose.onNodeWithTag("markdown_table_message_0_1").assertExists()
    }

    @Test fun unknownActivityHasExplicitUnverifiedStatusInsteadOfSuccess() {
        val item = NativeMessage("future", "turn", "futureTool", "", JSONObject(),
            activity = NativeActivity("tool", "原生扩展", "尚未支持的原生结果", "保留的结果", "unknown"))
        compose.setContent { RivoTheme { Surface { NativeActivityGroup(NativeTimelineRow.Activities(listOf(item))) {} } } }
        compose.onNodeWithTag("activity_unknown_count", useUnmergedTree = true).assertTextEquals("1 项状态待核验")
        compose.onNodeWithTag("native_activity_toggle").performClick()
        compose.onNodeWithText("状态待核验").assertExists()
        compose.onNodeWithText("保留的结果").assertExists()
        compose.onNodeWithText("已完成").assertDoesNotExist()
    }
}
