package dev.rivo.relay.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import dev.rivo.relay.data.MediaPromptResult
import dev.rivo.relay.ui.theme.RivoTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImageViewerSizingUiTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun smallPreviewFillsViewportWhileOriginalIsUnavailable() =
    verifyImageSize(120, 80, true, previewOnly = true)

  @Test fun smallOriginalExpandsToFit() = verifyImageSize(120, 80, false)
  @Test fun largeOriginalShrinksToFit() = verifyImageSize(2400, 3600, true)

  @Test fun halfScreenOriginalExpandsToFit() {
    val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
    verifyImageSize(bounds.width() / 2, bounds.height() / 2, false)
  }

  @Test fun widerThanHalfScreenOriginalExpandsToFit() {
    val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
    verifyImageSize(bounds.width() / 2 + 1, bounds.height() / 4, false)
  }

  @Test fun tallerThanHalfScreenOriginalExpandsToFitWithPreview() {
    val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
    verifyImageSize(bounds.width() / 4, bounds.height() / 2 + 1, true)
  }

  @Test fun smallOriginalDoesNotShrinkAfterPreviewLoads() {
    fun png(width: Int, height: Int, color: Int): ByteArray =
      ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
          eraseColor(color)
          compress(Bitmap.CompressFormat.PNG, 100, output)
          recycle()
        }
        output.toByteArray()
      }
    val preview = File(compose.activity.cacheDir, "viewer-transition-preview.png")
    preview.writeBytes(png(60, 40, Color.RED))
    val original = png(120, 80, Color.BLUE)
    val releaseOriginal = CountDownLatch(1)
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (!releaseOriginal.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
        return MockResponse().setHeader("Content-Type", "image/png")
          .setBody(Buffer().write(original))
      }
    }
    server.start()
    try {
      val source = server.url("/small-original.png").toString()
      compose.setContent {
        RivoTheme {
          FullScreenImage(
            image = ImageViewerSource(listOf(ImageViewerItem(preview.toURI().toString(), source)), 0, Rect.Zero),
            onDismiss = {}, onSave = null,
            onReversePrompt = { _, _ -> MediaPromptResult("") },
            onGenerationInfo = { error("unused") }, onCopyPrompt = {},
          )
        }
      }
      val node = compose.onNodeWithTag("fullscreen_image")
      fun waitForColor(blue: Boolean) {
        compose.waitUntil(10_000) {
          val pixels = node.captureToImage().toPixelMap()
          val center = pixels[pixels.width / 2, pixels.height / 2]
          if (blue) center.blue > 0.95f && center.red < 0.05f
          else center.red > 0.95f && center.blue < 0.05f
        }
      }
      fun coloredBounds(blue: Boolean): List<Int> {
        val pixels = node.captureToImage().toPixelMap()
        fun colored(x: Int, y: Int) = pixels[x, y].let {
          if (blue) it.blue > 0.95f && it.red < 0.05f
          else it.red > 0.95f && it.blue < 0.05f
        }
        val xs = (0 until pixels.width).filter { colored(it, pixels.height / 2) }
        val ys = (0 until pixels.height).filter { colored(pixels.width / 2, it) }
        return listOf(xs.first(), xs.last(), ys.first(), ys.last())
      }
      waitForColor(blue = false)
      SystemClock.sleep(500)
      compose.waitForIdle()
      val previewBounds = coloredBounds(blue = false)
      releaseOriginal.countDown()
      waitForColor(blue = true)
      assertEquals("loading the original preserves the displayed bounds", previewBounds, coloredBounds(blue = true))
    } finally {
      releaseOriginal.countDown()
      server.shutdown()
      preview.delete()
    }
  }

  private fun verifyImageSize(width: Int, height: Int, separatePreview: Boolean, previewOnly: Boolean = false) {
    val file = File(compose.activity.cacheDir, "viewer-sizing-$width.png")
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.RED)
      file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
      recycle()
    }
    val source = file.toURI().toString()
    compose.setContent {
      RivoTheme {
        FullScreenImage(
          image = ImageViewerSource(
            listOf(ImageViewerItem(
              if (previewOnly || !separatePreview) source else "file:///missing-preview.png",
              if (previewOnly) "file:///missing-original.png" else source,
            )),
            0, Rect.Zero,
          ),
          onDismiss = {}, onSave = null,
          onReversePrompt = { _, _ -> MediaPromptResult("") },
          onGenerationInfo = { error("unused") }, onCopyPrompt = {},
        )
      }
    }
    val node = compose.onNodeWithTag("fullscreen_image")
    compose.waitUntil(10_000) {
      val pixels = node.captureToImage().toPixelMap()
      val center = pixels[pixels.width / 2, pixels.height / 2]
      center.red > 0.95f && center.green < 0.05f
    }
    // Finish Coil crossfade and the platform dialog entrance animation.
    SystemClock.sleep(500)
    compose.waitForIdle()
    val pixels = node.captureToImage().toPixelMap()
    fun isRed(x: Int, y: Int): Boolean = pixels[x, y].let {
      it.red > 0.95f && it.green < 0.05f && it.blue < 0.05f
    }
    val horizontal = (0 until pixels.width).filter { isRed(it, pixels.height / 2) }
    val vertical = (0 until pixels.height).filter { isRed(pixels.width / 2, it) }
    val fitFactor = minOf(pixels.width.toFloat() / width, pixels.height.toFloat() / height)
    val factor = fitFactor
    assertEquals("rendered width", (width * factor).roundToInt().toDouble(), horizontal.size.toDouble(), 2.0)
    assertEquals("rendered height", (height * factor).roundToInt().toDouble(), vertical.size.toDouble(), 2.0)
    assertEquals("horizontal centering", pixels.width / 2.0, (horizontal.first() + horizontal.last()) / 2.0, 1.0)
    assertEquals("vertical centering", pixels.height / 2.0, (vertical.first() + vertical.last()) / 2.0, 1.0)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (InstrumentationRegistry.getArguments().getString("captureScreenshots", "false").toBoolean()) {
      val screenshot = File(compose.activity.getExternalFilesDir(null), "viewer-sizing-$width.png")
      screenshot.outputStream().use {
        instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("cp ${screenshot.absolutePath} /sdcard/Download/${screenshot.name}")
      ).use { it.readBytes() }
    }
  }
}
