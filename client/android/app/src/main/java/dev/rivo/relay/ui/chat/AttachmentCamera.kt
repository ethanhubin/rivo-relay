package dev.rivo.relay.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Both composers request the manifest camera permission before starting the external camera. */
@Composable
internal fun rememberAttachmentCamera(onCaptured: (Uri) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val captured by rememberUpdatedState(onCaptured)
    val reportError by rememberUpdatedState(onError)
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri; pendingUri = null
        if (success && uri != null) captured(Uri.parse(uri))
    }
    fun launchCamera() {
        try {
            val file = File.createTempFile("camera-", ".jpg", File(context.cacheDir, "previews").apply { mkdirs() })
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            pendingUri = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) {
            pendingUri = null
            reportError("无法打开相机")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else reportError("未获得相机权限，可在系统设置中开启。")
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
}
