package dev.rivo.relay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.rivo.relay.ui.chat.FollowChatContent
import dev.rivo.relay.ui.chat.distanceFromBottom
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatScrollFollowingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun growingTailAndInsertedFooterArePinnedOnEveryPlacedLayout() {
        val height = mutableIntStateOf(60)
        val footer = mutableStateOf(false)
        val bottomGaps = mutableListOf<Int>()
        compose.setContent {
            val list = rememberLazyListState()
            FollowChatContent(list, true, if (footer.value) 31 else 30, height.intValue to footer.value)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp),
                modifier = Modifier.height(300.dp).fillMaxWidth().onGloballyPositioned {
                    bottomGaps += list.layoutInfo.distanceFromBottom() ?: Int.MAX_VALUE
                }) {
                items(29, key = { "history-$it" }) { Text("历史 $it", Modifier.height(40.dp)) }
                item(key = "tail") { Text("正文", Modifier.height(height.intValue.dp)) }
                if (footer.value) item(key = "footer") { Text("任务完成", Modifier.height(32.dp)) }
                item(key = "anchor") { Spacer(Modifier.height(1.dp)) }
            }
        }
        compose.waitForIdle()
        for (size in listOf(120, 360, 700, 900)) {
            compose.runOnIdle { bottomGaps.clear(); height.intValue = size }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Expected a placed layout", bottomGaps.isNotEmpty())
                assertTrue("Visible corrective jump: $bottomGaps", bottomGaps.all { it <= 1 })
            }
        }
        compose.runOnIdle { bottomGaps.clear(); footer.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Footer moved the bottom: $bottomGaps", bottomGaps.isNotEmpty() && bottomGaps.all { it <= 1 }) }
    }

    @Test fun readingInsideAGrowingMessageKeepsItsTopPosition() {
        val height = mutableIntStateOf(700)
        val follow = mutableStateOf(true)
        compose.setContent {
            val list = rememberLazyListState()
            FollowChatContent(list, follow.value, 1, height.intValue)
            LazyColumn(state = list, modifier = Modifier.height(300.dp).testTag("history")) {
                item(key = "tail") { Text("正在阅读的段落", Modifier.height(height.intValue.dp).testTag("tail")) }
                item(key = "anchor") { Spacer(Modifier.height(1.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { follow.value = false }
        compose.onNodeWithTag("history").performTouchInput { swipeDown() }
        val before = compose.onNodeWithTag("tail").fetchSemanticsNode().positionInRoot.y
        compose.runOnIdle { height.intValue = 1000 }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("tail").fetchSemanticsNode().positionInRoot.y, 1f)
    }
}
