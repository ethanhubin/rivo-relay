package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.rivo.relay.data.NativeTurnSettings
import dev.rivo.relay.data.sessionSnapshot
import dev.rivo.relay.codex.SessionState
import dev.rivo.relay.ui.chat.ChatScreen
import dev.rivo.relay.ui.chat.NativeTurnNotice
import dev.rivo.relay.ui.theme.RivoTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class NativeTurnNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedTurnWithoutAssistantTextShowsNativeErrorAsLiteralText() {
        compose.setContent { RivoTheme { Surface {
            NativeTurnNotice(NativeTurnSettings("failed-turn", status = "failed", error = JSONObject()
                .put("message", "请求失败：无法连接模型服务")
                .put("additionalDetails", "[诊断](https://example.invalid)")))
        } } }
        compose.onNodeWithTag("native_turn_notice_failed-turn").assertIsDisplayed()
        compose.onNodeWithText("本轮执行失败").assertIsDisplayed()
        compose.onNodeWithText("请求失败：无法连接模型服务").assertIsDisplayed()
        compose.onNodeWithText("[诊断](https://example.invalid)").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun interruptedTurnDoesNotClaimSuccessOrInventFailureDetails() {
        compose.setContent { RivoTheme { Surface { NativeTurnNotice(NativeTurnSettings("interrupted-turn", status = "interrupted")) } } }
        compose.onNodeWithText("本轮已中断").assertIsDisplayed()
        compose.onNodeWithText("本轮执行失败").assertDoesNotExist()
    }

    @Test fun chatShowsEmptyFailedTurnWithoutEmptyConversationPrompt() {
        val snapshot = sessionSnapshot(JSONObject("""{"thread":{"id":"failed-chat","turns":[{"id":"failed-turn","status":"failed","items":[],"error":{"message":"模型服务不可用"}}]},"writerOwner":"relay"}"""))
        compose.setContent { RivoTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            ChatScreen(SessionState(id = snapshot.id, snapshot = snapshot), false, false, emptyList(), {},
                onSend = { _, _, _, _ -> error("must not submit") }, onStop = {}, onRespond = { _, _ -> },
                onRefresh = {}, onLoadModels = {}, onEarlier = {}, readFile = { JSONObject() })
        } } }
        compose.onNodeWithTag("native_turn_notice_failed-turn").assertIsDisplayed()
        compose.onNodeWithText("模型服务不可用").assertIsDisplayed()
        compose.onNodeWithText("继续这个项目中的工作").assertDoesNotExist()
    }
}
