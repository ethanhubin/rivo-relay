package dev.rivo.relay.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Ephemeral draft; never persisted with node credentials or written to logs. */
class DraftImage(val id: String, val bytes: ByteArray) {
    val mimeType: String get() = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }
    fun input() = JSONObject().put("type", "image").put("url", "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
}

internal suspend fun prepareImage(context: Context, uri: Uri): DraftImage = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 20 * 1024 * 1024) { "单张图片最大支持 20 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("无法读取图片")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
        "请选择 JPEG、PNG 或 WebP 图片。"
    }
    DraftImage(UUID.randomUUID().toString(), bytes)
}

/** Compress without taking ownership of the caller's bitmap. */
internal fun compressDraftBitmap(source: Bitmap): ByteArray {
    var bitmap = source
    try {
        var quality = 88
        var bytes: ByteArray
        while (true) {
            bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output); output.toByteArray() }
            if (bytes.size <= 20 * 1024 * 1024) break
            if (quality > 55) quality -= 11 else {
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .75f).toInt().coerceAtLeast(1), (bitmap.height * .75f).toInt().coerceAtLeast(1), true)
                if (smaller !== bitmap && bitmap !== source) bitmap.recycle()
                bitmap = smaller
            }
        }
        return bytes
    } finally { if (bitmap !== source) bitmap.recycle() }
}

internal fun nativeInputs(text: String, images: List<DraftImage>, references: List<JSONObject>): JSONArray = JSONArray().apply {
    require(images.size <= 10 && images.all { it.bytes.size <= 20 * 1024 * 1024 } && images.sumOf { it.bytes.size.toLong() } <= 40L * 1024 * 1024) { "最多 10 张图片，单张 20 MiB，总计 40 MiB" }
    val prompt = text.takeIf(String::isNotBlank) ?: when { images.isNotEmpty() && references.isNotEmpty() -> "请分析附加的图片和文件。"; images.isNotEmpty() -> "请查看附加的图片。"; references.isNotEmpty() -> "请阅读附加的文件。"; else -> "" }
    if (prompt.isNotBlank()) put(JSONObject().put("type", "text").put("text", prompt).put("text_elements", JSONArray()))
    images.forEach { put(it.input()) }
    references.forEach { put(it) }
}

internal fun nativeFilePath(target: String, cwd: String): String? {
    val decoded = when {
        target.startsWith("file://") -> runCatching { java.net.URI(target).path }.getOrNull() ?: return null
        target.contains("://") || target.startsWith("data:") || target.startsWith("#") -> return null
        else -> runCatching { java.net.URLDecoder.decode(target.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    }
    val path = decoded.substringBefore('#').replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (path.isBlank() || path.any(Char::isISOControl)) return null
    return when {
        path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) || path.startsWith("\\\\") -> path
        else -> cwd.takeIf { it.isNotBlank() }?.trimEnd('/', '\\')?.let { root ->
            val segments = mutableListOf<String>()
            "$root/$path".split('/').forEach { segment -> when (segment) { "", "." -> Unit; ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex); else -> segments += segment } }
            (if (root.startsWith('/')) "/" else "") + segments.joinToString("/")
        }
    }
}

internal data class PreparedFile(val name: String, val file: java.io.File)

internal suspend fun prepareFile(context: Context, uri: Uri): PreparedFile = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "attachment"
    require(name.length <= 200 && name !in setOf(".", "..") && name.none { it in "/\\:" || it.isISOControl() }) { "文件名无效" }
    val target = java.io.File.createTempFile("relay-upload-", ".tmp", context.cacheDir)
    try {
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var size = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 1024L * 1024 * 1024) { "手机文件最大支持 1 GiB" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取文件")
        PreparedFile(name, target)
    } catch (e: Exception) { target.delete(); throw e }
}

internal fun orientDraftBitmap(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = runCatching { bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
    val matrix = Matrix().apply { when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
    } }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
}
