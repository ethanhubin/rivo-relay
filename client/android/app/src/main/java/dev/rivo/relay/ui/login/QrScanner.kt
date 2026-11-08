package dev.rivo.relay.ui.login

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class QrCaptureActivity : CaptureActivity()

/** Camera frames are decoded by ZXing. Scan and paste both return the same pairing URI. */
@Composable
fun rememberQrScanner(onDecoded: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onDecoded)
    }
    fun scan() = scanner.launch(ScanOptions()
        .setCaptureActivity(QrCaptureActivity::class.java)
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setOrientationLocked(false)
        .setBeepEnabled(false)
        .setPrompt("扫描电脑上 Rivo Relay 显示的二维码"))
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else onError("未获得相机权限，仍可从相册识别或手动输入连接。")
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else permission.launch(Manifest.permission.CAMERA)
    }
}

/** System picker grants access to the selected image without storage permission. */
@Composable
fun rememberQrImagePicker(onDecoded: (String) -> Unit, onError: (String) -> Unit, onBusy: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoded by rememberUpdatedState(onDecoded)
    val error by rememberUpdatedState(onError)
    val busy by rememberUpdatedState(onBusy)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy(true)
            try {
                val text = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
                    }
                    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: throw IllegalArgumentException("Unable to read image")
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        decodeQrPixels(bitmap.width, bitmap.height, pixels)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (text != null) decoded(text) else error("图片中未识别到二维码，请选择清晰的配对二维码图片。")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error("无法读取所选图片，请重新选择。")
            } finally {
                busy(false)
            }
        }
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
