package dev.rivo.relay.ui.login

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
        if (granted) scan() else onError("未获得相机权限，仍可通过手动输入连接。")
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else permission.launch(Manifest.permission.CAMERA)
    }
}
