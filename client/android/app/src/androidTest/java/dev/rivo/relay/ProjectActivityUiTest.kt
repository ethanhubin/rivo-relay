package dev.rivo.relay

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.ui.projects.ProjectSummary
import dev.rivo.relay.ui.projects.ProjectsScreen
import dev.rivo.relay.ui.theme.RivoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated activity fixture; no connected node, polling or native submission. */
@RunWith(AndroidJUnit4::class)
class ProjectActivityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeBorderMovesAndDisappearsWhenWorkEndsWithoutBlockingProjectSelection() {
        compose.mainClock.autoAdvance = false
        var outstanding by mutableIntStateOf(1)
        val selected = mutableListOf<String>()
        compose.setContent {
            RivoTheme {
                Surface {
                    ProjectsScreen(
                        projects = listOf(
                            ProjectSummary("active", "正在处理的项目", "/fixture/active", 2, outstanding),
                            ProjectSummary("idle", "空闲项目", "/fixture/idle", 1),
                        ),
                        nodeName = "UI 验证",
                        loading = false,
                        onProject = { selected += it.id },
                        onConnection = {},
                        onRefresh = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("project_active_running_border").assertIsDisplayed()
        compose.onNodeWithTag("project_idle_running_border").assertDoesNotExist()
        val activeStart = compose.onNodeWithTag("project_active_running_border").captureToImage()
        val idleStart = compose.onNodeWithTag("project_idle").captureToImage()
        evidence("project-activity-start", compose.onRoot().captureToImage())

        compose.mainClock.advanceTimeBy(450)
        val activeLater = compose.onNodeWithTag("project_active_running_border").captureToImage()
        val idleLater = compose.onNodeWithTag("project_idle").captureToImage()
        assertTrue("Active border should visibly sweep along the card", changedPixels(activeStart, activeLater) > 20)
        assertEquals("Idle project must remain still", 0, changedPixels(idleStart, idleLater))
        evidence("project-activity-later", compose.onRoot().captureToImage())

        compose.onNodeWithTag("project_active").performClick()
        compose.onNodeWithTag("project_idle").performClick()
        compose.runOnIdle {
            assertEquals(listOf("active", "idle"), selected)
            outstanding = 0
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("project_active_running_border").assertDoesNotExist()
        compose.onNodeWithTag("project_active").assertIsDisplayed()
    }

    private fun changedPixels(before: ImageBitmap, after: ImageBitmap): Int {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (a[x, y] != b[x, y]) changed++
        }
        return changed
    }

    private fun evidence(name: String, image: ImageBitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
