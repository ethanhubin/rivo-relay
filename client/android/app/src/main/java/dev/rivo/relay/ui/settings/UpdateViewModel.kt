package dev.rivo.relay.ui.settings

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rivo.relay.data.AppRelease
import dev.rivo.relay.data.NodeAppUpdates
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity-owned: switching tabs does not cancel an update or discard its status. */
class UpdateViewModel : ViewModel() {
    var downloading by mutableStateOf(false); private set
    var downloadedBytes by mutableLongStateOf(0L); private set
    var bytesPerSecond by mutableLongStateOf(0L); private set
    var checking by mutableStateOf(false); private set
    var checked by mutableStateOf(false); private set
    var release by mutableStateOf<AppRelease?>(null); private set
    var error by mutableStateOf<String?>(null)
    var readyFile by mutableStateOf<File?>(null); private set

    fun check(checkUpdate: suspend () -> AppRelease?) {
        if (checking || downloading) return
        checking = true; checked = false; error = null
        viewModelScope.launch {
            try {
                val latest = checkUpdate()
                if (latest != release) readyFile = null
                release = latest; checked = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "检查更新失败，请重试" }
            finally { checking = false }
        }
    }

    fun download(context: Context, latest: AppRelease, customInstall: (suspend (AppRelease) -> Unit)? = null) {
        if (downloading) return
        downloadedBytes = 0L; bytesPerSecond = 0L; downloading = true; error = null
        viewModelScope.launch {
            val speed = launch {
                var previousBytes = 0L
                var previousTime = SystemClock.elapsedRealtime()
                while (true) {
                    delay(500)
                    val now = SystemClock.elapsedRealtime()
                    bytesPerSecond = (downloadedBytes - previousBytes).coerceAtLeast(0) * 1000 / (now - previousTime).coerceAtLeast(1)
                    previousBytes = downloadedBytes; previousTime = now
                }
            }
            try {
                if (customInstall != null) { customInstall(latest); return@launch }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    error = "请允许安装未知应用，然后再次点击下载并安装"
                    return@launch
                }
                val file = readyFile?.takeIf { it.isFile } ?: NodeAppUpdates.configured(context).download(latest, File(context.cacheDir, "downloads")) { bytes, _ ->
                    viewModelScope.launch { downloadedBytes = bytes }
                }.also { readyFile = it }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "下载安装包失败，请重试" }
            finally { speed.cancel(); downloading = false }
        }
    }
}
