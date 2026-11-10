package dev.rivo.relay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A compact, non-blocking notice; persistent recovery actions belong outside it. */
@Composable
internal fun TransientNotice(message: String, modifier: Modifier = Modifier, onExpired: () -> Unit = {}) {
    var visible by remember(message) { mutableStateOf(true) }
    val expire by rememberUpdatedState(onExpired)
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 4_000L, containsIcons = false, containsText = true, containsControls = false,
    ) ?: 4_000L
    LaunchedEffect(message, timeout) {
        delay(timeout)
        visible = false
        expire()
    }
    if (visible) Box(modifier) { Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("transient_notice").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
    }
    }
}
