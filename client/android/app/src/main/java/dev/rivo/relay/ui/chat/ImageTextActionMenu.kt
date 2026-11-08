package dev.rivo.relay.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class ImageMenuAction(
  val label: String,
  val title: String,
  val tag: String,
  val enabled: Boolean = true,
  val onClick: () -> Unit,
)

@Composable
internal fun ImageTextActionMenu(
  actions: List<ImageMenuAction>,
  position: Offset,
  tag: String,
  onDismiss: () -> Unit,
  focusable: Boolean = true,
) {
  val density = LocalDensity.current
  val maxWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() - 16.dp }
  Popup(
    popupPositionProvider = TextMenuPositionProvider(
      IntOffset(position.x.roundToInt(), position.y.roundToInt()), with(density) { 8.dp.roundToPx() },
    ),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = focusable),
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 1f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      shadowElevation = 10.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 1f)),
      modifier = Modifier.widthIn(max = maxWidth).testTag(tag),
    ) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        actions.forEach { action ->
          Text(
            text = action.label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.enabled) 1f else 0.38f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(24.dp))
              .clickable(enabled = action.enabled) { onDismiss(); action.onClick() }
              .semantics { contentDescription = action.title }
              .padding(horizontal = 18.dp, vertical = 13.dp)
              .testTag(action.tag),
          )
        }
      }
    }
  }
}
