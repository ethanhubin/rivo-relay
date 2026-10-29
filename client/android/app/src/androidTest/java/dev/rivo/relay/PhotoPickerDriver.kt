package dev.rivo.relay

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Inserts a fixture into Android's media collection; selection still happens through the real picker UI. */
internal fun createRedPhoto(): Uri {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "RivoRelayRed-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RivoRelayVerification")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    try { requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    finally { bitmap.recycle() }
    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return uri
}

/** Observes accessibility bounds and actual thumbnail pixels before selecting the red test fixture. */
internal fun clickVisibleRedPickerPhoto(): Boolean {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = automation.rootInActiveWindow ?: return false
    val owner = root.packageName?.toString().orEmpty()
    if (!owner.contains("providers.media") && !owner.contains("photopicker") && !owner.contains("documentsui")) return false
    val captured = automation.takeScreenshot() ?: return false
    val screenshot = if (captured.config == Bitmap.Config.HARDWARE) requireNotNull(captured.copy(Bitmap.Config.ARGB_8888, false)).also { captured.recycle() } else captured
    try {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && bounds.width() >= 60 && bounds.height() >= 60 && bounds.width() * bounds.height() < screenshot.width * screenshot.height / 2 &&
                (node.className?.toString()?.contains("Image") == true || !node.contentDescription.isNullOrBlank() || node.viewIdResourceName?.contains("thumbnail") == true)) candidates += node to bounds
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        for ((node, bounds) in candidates.sortedBy { it.second.width() * it.second.height() }) {
            var red = 0
            var total = 0
            for (x in 2..7) for (y in 2..7) {
                val px = (bounds.left + bounds.width() * x / 10).coerceIn(0, screenshot.width - 1)
                val py = (bounds.top + bounds.height() * y / 10).coerceIn(0, screenshot.height - 1)
                val pixel = screenshot.getPixel(px, py)
                if (Color.red(pixel) > 190 && Color.green(pixel) < 90 && Color.blue(pixel) < 90) red++
                total++
            }
            if (red < total * .85) continue
            savePickerEvidence()
            var clickable: AccessibilityNodeInfo? = node
            repeat(6) {
                val current = clickable ?: return@repeat
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                clickable = current.parent
            }
        }
        return false
    } finally { screenshot.recycle() }
}

internal fun confirmPickerSelectionIfPresent() {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
    if (root.packageName?.toString()?.let { it.contains("providers.media") || it.contains("photopicker") } != true) return
    fun visit(node: AccessibilityNodeInfo): Boolean {
        val label = node.text?.toString().orEmpty()
        if (node.isClickable && node.isEnabled && Regex("^(Add|添加|选择|完成)(\\s*\\(?1\\)?)?$", RegexOption.IGNORE_CASE).matches(label))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        for (index in 0 until node.childCount) if (node.getChild(index)?.let(::visit) == true) return true
        return false
    }
    visit(root)
}

internal fun savePickerEvidence() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
    instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
        try { File(folder, "system-photo-picker.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
    val lines = mutableListOf<String>()
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect().also(node::getBoundsInScreen)
        lines += "${" ".repeat(depth)}${node.packageName} ${node.className} ${node.viewIdResourceName} text=${node.text} description=${node.contentDescription} bounds=$bounds clickable=${node.isClickable}"
        for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
    }
    instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it, 0) }
    File(folder, "system-photo-picker.txt").writeText(lines.joinToString("\n"))
}
