package dev.rivo.relay.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import dev.rivo.relay.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rivo.relay.codex.PINNED_COMPLETION
import dev.rivo.relay.codex.PINNED_SOUND
import dev.rivo.relay.codex.PINNED_VIBRATION
import dev.rivo.relay.codex.notificationPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationSettingsEditor(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(modifier = Modifier.fillMaxSize().testTag("notification_settings_page"), topBar = {
        CenterAlignedTopAppBar(title = { Text("通知") }, navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("close_notification_settings")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回设置")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)) {
            PinnedCompletionSettings()
        }
    }
}

@Composable
internal fun PinnedCompletionSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { notificationPreferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(PINNED_COMPLETION, false)) }
    var sound by remember { mutableStateOf(preferences.getBoolean(PINNED_SOUND, true)) }
    var vibration by remember { mutableStateOf(preferences.getBoolean(PINNED_VIBRATION, true)) }
    fun setEnabled(value: Boolean) {
        enabled = value
        preferences.edit().putBoolean(PINNED_COMPLETION, value).apply()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setEnabled(it) }

    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp)) {
            Text("通知", style = MaterialTheme.typography.titleMedium)
            NotificationToggle("置顶任务完成通知", enabled) { checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    setEnabled(checked)
                }
            }
            Text("应用运行并连接节点时，置顶任务成功完成后发送通知。", style = MaterialTheme.typography.bodySmall)
            if (enabled) {
                NotificationToggle("提示音", sound) {
                    sound = it
                    preferences.edit().putBoolean(PINNED_SOUND, it).apply()
                }
                NotificationToggle("震动", vibration) {
                    vibration = it
                    preferences.edit().putBoolean(PINNED_VIBRATION, it).apply()
                }
            }
        }
    }
}

@Composable
private fun NotificationToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked, onCheckedChange = null)
    }
}
