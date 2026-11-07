package dev.rivo.relay

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import dev.rivo.relay.ui.chat.previewBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ImageDecodeParityTest {
    @Test fun copiedImmutableImageHasRealProviderMime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("immutable-image-", ".complete", context.cacheDir)
        try {
            source.writeBytes(byteArrayOf(1, 2, 3))
            val typed = dev.rivo.relay.ui.chat.typedMediaCache(context, source, "image/png")
            try {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", typed, "图片.png")
                assertEquals("image/png", context.contentResolver.getType(uri))
                val clip = android.content.ClipData.newUri(context.contentResolver, "图片", uri)
                assertTrue(clip.description.hasMimeType("image/png"))
            } finally { typed.delete() }
        } finally { source.delete() }
    }
    @Test fun publishedDownloadCanBeOpenedThroughReadonlyFileProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
        val file = File.createTempFile("provider-test-", ".complete", directory)
        try {
            file.writeText("download fixture")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file, "report.txt")
            assertEquals("content", uri.scheme)
            assertEquals("download fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { file.delete() }
    }

    @Test fun originalLargerThanJsonPreviewLimitDecodesAtWindowBoundedSize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("large-image-", ".png", context.cacheDir)
        try {
            val random = Random(47)
            val original = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
            val row = IntArray(2048)
            repeat(2048) { y ->
                for (x in row.indices) row[x] = random.nextInt() or 0xff000000.toInt()
                original.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            original.recycle()
            assertTrue(file.length() > 4 * 1024 * 1024)
            val saved = File.createTempFile("inline-action-", ".png", context.cacheDir)
            try {
                val dataUri = "data:image/png;base64," + android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                saved.outputStream().use { output -> dev.rivo.relay.ui.chat.downloadFileSource(dataUri, output, { _, _ -> }, { _, _, _ -> error("inline image must remain local") }) }
                assertEquals(file.length(), saved.length())
            } finally { saved.delete() }
            val timeline = previewBitmap(file, 1080)!!
            assertEquals(1024, timeline.width); assertEquals(1024, timeline.height)
            val fullscreen = previewBitmap(file, 4096)!!
            assertEquals(2048, fullscreen.width); assertEquals(2048, fullscreen.height)
            file.writeText("invalid image")
            assertNull(previewBitmap(file, 1080))
        } finally { file.delete() }
    }
}
