package dev.rivo.relay

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rivo.relay.codex.TaskNotificationTarget
import dev.rivo.relay.ui.theme.RivoTheme

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotification(intent)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(state.requestNotificationPermission) {
                if (state.requestNotificationPermission) {
                    model.notificationPermissionLaunched()
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            RivoTheme {
                val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
                        navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.argb(230, 255, 255, 255), android.graphics.Color.argb(128, 27, 27, 27)) { darkTheme },
                    )
                }
                RelayApp(model)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        model.onForeground()
    }
    override fun onStop() {
        model.onBackground()
        super.onStop()
    }
    private fun handleNotification(intent: Intent?) {
        val task = TaskNotificationTarget.from(intent)
        if (task != null) model.openNotification(task)
        else if (intent?.getBooleanExtra("openTodos", false) == true) model.openTodoNotification(intent.getStringExtra("todoNodeBase").orEmpty())
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotification(intent)
    }
}
