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
    fun input() = JSONObject().put("type", "image").put("url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
}

internal suspend fun prepareImage(context: Context, uri: Uri): DraftImage = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请选择 JPEG、PNG 或 WebP 图片。" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalArgumentException("无法读取这张图片")
    val orientation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }
    if (!matrix.isIdentity) {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        bitmap = rotated
    }
    try { DraftImage(UUID.randomUUID().toString(), compressDraftBitmap(bitmap)) }
    finally { bitmap.recycle() }
}

/** Compress without taking ownership of the caller's bitmap. */
internal fun compressDraftBitmap(source: Bitmap): ByteArray {
    var bitmap = source
    try {
        var quality = 88
        var bytes: ByteArray
        while (true) {
            bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output); output.toByteArray() }
            if (bytes.size <= 1024 * 1024) break
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
    if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text).put("text_elements", JSONArray()))
    images.forEach { put(it.input()) }
    references.forEach { put(it) }
}

internal fun nativeFilePath(target: String, cwd: String): String? {
    val decoded = when {
        target.startsWith("file://") -> runCatching { java.net.URI(target).path }.getOrNull() ?: return null
        target.contains("://") || target.startsWith("data:") || target.startsWith("#") -> return null
        else -> target
    }
    val path = decoded.substringBefore('#').replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (path.isBlank() || path.any(Char::isISOControl)) return null
    return when {
        path.startsWith('/') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) || path.startsWith("\\\\") -> path
        else -> cwd.takeIf { it.isNotBlank() }?.trimEnd('/', '\\')?.let { "$it/$path" }
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
