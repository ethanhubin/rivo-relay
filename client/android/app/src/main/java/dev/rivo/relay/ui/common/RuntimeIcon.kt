package dev.rivo.relay.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.rivo.relay.R

/** ic_dsh.png is the unmodified official DeepSeek Harness macOS Resources/icon.png.
 * Branding only; runtime capabilities remain the authority for all available actions. */
@Composable
fun RuntimeIcon(runtime: String, modifier: Modifier = Modifier) {
    when (runtime) {
        "dsh" -> Image(painterResource(R.drawable.ic_dsh), "DeepSeek Harness", modifier.size(24.dp))
        "codex" -> Icon(painterResource(R.drawable.ic_openai), "Codex", modifier.size(24.dp))
        else -> Text("◇", modifier.size(24.dp))
    }
}
