package dev.rivo.relay.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.data.MediaPromptResult
import dev.rivo.relay.ui.theme.RivoTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageViewerPanUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val viewer get() = compose.onNodeWithTag("fullscreen_image")

  @Test
  fun doubleTapAnimatesBothZoomDirectionsAroundTheTappedPoint() {
    showImage(zoom = false)
    val original = markerCenter(pixels())
    val originalWidth = markerWidth(pixels())
    val width = pixels().width
    viewer.performTouchInput { doubleClick(percentOffset(0.6f, 0.5f)) }
    compose.mainClock.advanceTimeBy(96)
    val enlarging = markerCenter(pixels())
    assertTrue("scale increases gradually", markerWidth(pixels()) > originalWidth)
    assertTrue("scale is still below the zoom target", markerWidth(pixels()) < originalWidth * 2.5f - 1f)
    assertTrue("zoom in has an intermediate position", enlarging.x < original.x - 3f)
    assertTrue("zoom in does not jump to its final position", enlarging.x > original.x - width * 0.15f + 3f)
    captureDeviceScreenshot("image-viewer-double-tap-animating.png")
    compose.mainClock.advanceTimeBy(400)
    val enlarged = markerCenter(pixels())
    val enlargedWidth = markerWidth(pixels())
    assertEquals(originalWidth * 2.5f, enlargedWidth.toFloat(), 2f)
    assertEquals(original.x - width * 0.15f, enlarged.x, 2f)
    viewer.performTouchInput { doubleClick() }
    compose.mainClock.advanceTimeBy(96)
    val shrinking = markerCenter(pixels())
    assertTrue("scale decreases gradually", markerWidth(pixels()) < enlargedWidth)
    assertTrue("scale is still above the original size", markerWidth(pixels()) > originalWidth)
    assertTrue("zoom out has an intermediate position", shrinking.x > enlarged.x + 3f)
    assertTrue("zoom out does not jump back to center", shrinking.x < original.x - 3f)
    compose.onNodeWithTag("fullscreen_adjacent_page").assertDoesNotExist()
    compose.mainClock.advanceTimeBy(400)
    assertEquals(original.x, markerCenter(pixels()).x, 2f)
    assertEquals(originalWidth, markerWidth(pixels()))
    compose.onNodeWithTag("fullscreen_adjacent_page").assertExists()
  }

  @Test
  fun touchInterruptsDoubleTapAnimationAndContinuesDragging() {
    showImage(zoom = false)
    viewer.performTouchInput { doubleClick(percentOffset(0.6f, 0.5f)) }
    compose.mainClock.advanceTimeBy(96)
    val intermediate = markerCenter(pixels())
    viewer.performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(160)
    assertEquals("touch stops the zoom animation immediately", intermediate.x, markerCenter(pixels()).x, 2f)
    viewer.performTouchInput {
      moveTo(center + Offset(40f, 0f), delayMillis = 48)
      advanceEventTime(200)
      up()
    }
    compose.mainClock.advanceTimeBy(400)
    assertEquals("drag takes over without a jump", intermediate.x + 40f, markerCenter(pixels()).x, 3f)
  }

  @Test
  fun zoomedDragContinuesAfterReleaseAndNewTouchStopsIt() {
    showImage()
    val before = markerCenter(pixels())
    val width = pixels().width
    val height = pixels().height
    viewer.performTouchInput {
      down(percentOffset(0.3f, 0.4f))
      for (step in 1..8) {
        moveTo(percentOffset(0.3f + step * 0.015f, 0.4f + step * 0.005f), delayMillis = 16)
      }
    }
    compose.mainClock.advanceTimeByFrame()
    val dragged = markerCenter(pixels())
    assertEquals("horizontal pan follows the finger in screen pixels", width * 0.12f, dragged.x - before.x, 3f)
    assertEquals("vertical pan follows the finger in screen pixels", height * 0.04f, dragged.y - before.y, 3f)
    viewer.performTouchInput { up() }
    compose.mainClock.advanceTimeBy(80)
    val coasting = markerCenter(pixels())
    assertTrue("horizontal inertia continues after release", coasting.x > dragged.x + 8f)
    assertTrue("vertical inertia continues after release", coasting.y > dragged.y + 4f)

    viewer.performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(200)
    val caught = markerCenter(pixels())
    assertEquals(coasting.x, caught.x, 2f)
    assertEquals(coasting.y, caught.y, 2f)
    viewer.performTouchInput {
      moveTo(center + Offset(-40f, 0f), delayMillis = 48)
      advanceEventTime(200)
      up()
    }
    compose.mainClock.advanceTimeBy(1_000)
    val stopped = markerCenter(pixels())
    assertEquals("a held release does not reuse the old fling velocity", caught.x - 40f, stopped.x, 3f)
    compose.onNodeWithContentDescription("全屏图片 1 / 2").assertExists()
    compose.onNodeWithTag("fullscreen_adjacent_page").assertDoesNotExist()
  }

  @Test
  fun draggingPastEdgeHasResistanceAndSpringsBack() {
    showImage()
    val width = pixels().width
    viewer.performTouchInput {
      down(percentOffset(0.05f, 0.5f))
      for (step in 1..18) moveTo(percentOffset(0.05f + step * 0.05f, 0.5f), delayMillis = 24)
    }
    compose.mainClock.advanceTimeByFrame()
    val stretched = imageLeft(pixels())
    assertTrue("the image can travel beyond its left edge", stretched > 8)
    assertTrue("overscroll is resisted", stretched < width * 0.15f)
    captureDeviceScreenshot("image-viewer-pan-overshoot.png")
    viewer.performTouchInput {
      advanceEventTime(200)
      up()
    }
    compose.mainClock.advanceTimeBy(96)
    assertTrue("spring moves back toward the image boundary", imageLeft(pixels()) < stretched)
    compose.mainClock.advanceTimeBy(1_500)
    assertEquals("image settles flush against its edge", 0, imageLeft(pixels()))
    captureDeviceScreenshot("image-viewer-pan-settled.png")
    compose.onNodeWithContentDescription("全屏图片 1 / 2").assertExists()
  }

  @Test
  fun flingOvershootsTheEdgeThenSettles() {
    showImage()
    viewer.performTouchInput {
      down(percentOffset(0.2f, 0.5f))
      for (step in 1..8) moveTo(percentOffset(0.2f + step * 0.075f, 0.5f), delayMillis = 12)
      up()
    }
    var largestOvershoot = 0
    repeat(24) {
      compose.mainClock.advanceTimeByFrame()
      largestOvershoot = maxOf(largestOvershoot, imageLeft(pixels()))
    }
    assertTrue("fling retains momentum when it reaches an edge", largestOvershoot > 8)
    assertTrue("fling overshoot stays limited", largestOvershoot < pixels().width / 4)
    compose.mainClock.advanceTimeBy(1_500)
    assertEquals(0, imageLeft(pixels()))
  }

  @Test
  fun letterboxedImageReturnsToCenterAndDoubleTapResetsPan() {
    showImage(wide = true)
    val before = markerCenter(pixels())
    viewer.performTouchInput {
      down(center)
      for (step in 1..8) moveTo(center + Offset(0f, step * 16f), delayMillis = 16)
    }
    compose.mainClock.advanceTimeByFrame()
    val stretched = markerCenter(pixels())
    assertTrue(stretched.y > before.y + 8f)
    assertTrue("an image shorter than the viewport resists vertical pan", stretched.y < before.y + 128f)
    viewer.performTouchInput { up() }
    compose.mainClock.advanceTimeBy(1_500)
    assertEquals("letterbox axis returns to center", before.y, markerCenter(pixels()).y, 2f)
    viewer.performTouchInput {
      down(center)
      for (step in 1..6) moveTo(center + Offset(step * 20f, 0f), delayMillis = 16)
      up()
    }
    compose.mainClock.advanceTimeBy(64)
    viewer.performTouchInput { doubleClick() }
    compose.mainClock.advanceTimeBy(1_500)
    val reset = markerCenter(pixels())
    assertEquals("double tap cancels horizontal momentum", before.x, reset.x, 2f)
    assertEquals(before.y, reset.y, 2f)
    compose.onNodeWithTag("fullscreen_adjacent_page").assertExists()
  }

  private fun showImage(wide: Boolean = false, zoom: Boolean = true) {
    val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
    val width = bounds.width()
    val height = if (wide) bounds.height() / 8 else bounds.height()
    val file = File(compose.activity.cacheDir, "viewer-pan-$wide.png")
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(44, 113, 131))
      val paint = Paint()
      Canvas(this).apply {
        paint.color = Color.GREEN
        drawRect(0f, height / 2f - 3f, width.toFloat(), height / 2f + 3f, paint)
        paint.color = Color.RED
        drawRect(width / 2f - 3f, 0f, width / 2f + 3f, height.toFloat(), paint)
      }
      file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
      recycle()
    }
    val source = file.toURI().toString()
    compose.setContent {
      RivoTheme {
        FullScreenImage(
          image = ImageViewerSource(
            listOf(ImageViewerItem(source, source), ImageViewerItem(source, source)), 0, Rect.Zero,
          ),
          onDismiss = {}, onSave = null,
          onReversePrompt = { _, _ -> MediaPromptResult("") },
          onGenerationInfo = { error("unused") }, onCopyPrompt = {},
        )
      }
    }
    compose.waitUntil(10_000) {
      val pixels = pixels()
      pixels[pixels.width / 2, pixels.height / 2].red > 0.95f
    }
    SystemClock.sleep(500)
    if (zoom) viewer.performTouchInput { doubleClick() }
    compose.waitForIdle()
    compose.mainClock.autoAdvance = false
  }

  private fun pixels(): PixelMap = viewer.captureToImage().toPixelMap()

  private fun markerWidth(pixels: PixelMap): Int =
    (0 until pixels.width).count { x ->
      // Include the antialiased edge when measuring the geometric width of the scaled stripe.
      pixels[x, pixels.height / 2].let { it.red > 0.5f && it.green < 0.5f }
    }

  private fun markerCenter(pixels: PixelMap): Offset {
    val red = (0 until pixels.width).filter { x ->
      pixels[x, pixels.height / 2].let { it.red > 0.9f && it.green < 0.1f }
    }
    val green = (0 until pixels.height).filter { y ->
      pixels[pixels.width / 4, y].let { it.green > 0.9f && it.red < 0.1f }
    }
    if (red.isEmpty() || green.isEmpty()) {
      val file = File(compose.activity.getExternalFilesDir(null), "image-marker-failure.png")
      file.outputStream().use { viewer.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    check(red.isNotEmpty() && green.isNotEmpty()) { "Image reference markers must remain visible (red=${red.size}, green=${green.size})" }
    return Offset((red.first() + red.last()) / 2f, (green.first() + green.last()) / 2f)
  }

  private fun imageLeft(pixels: PixelMap): Int =
    (0 until pixels.width).first { x ->
      pixels[x, pixels.height / 2].let { it.red > 0.1f || it.green > 0.1f || it.blue > 0.1f }
    }

  private fun captureDeviceScreenshot(name: String) {
    if (!InstrumentationRegistry.getArguments().getString("captureScreenshots", "false").toBoolean()) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(compose.activity.getExternalFilesDir(null), name)
    file.outputStream().use {
      instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
    ).use { it.readBytes() }
  }
}
