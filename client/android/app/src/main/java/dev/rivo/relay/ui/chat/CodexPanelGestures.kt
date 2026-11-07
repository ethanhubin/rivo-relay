package dev.rivo.relay.ui.chat

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal fun Modifier.codexPanelSwipe(
  swipeKey: Any?,
  expanded: Boolean,
  canExpand: Boolean = true,
  onExpandedChange: (Boolean) -> Unit,
): Modifier =
  pointerInput(swipeKey, expanded, canExpand) {
    val swipeThreshold = 16.dp.toPx()
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      var latest = down
      var swipeTarget: Boolean? = null
      while (latest.pressed) {
        val event = awaitPointerEvent()
        latest = event.changes.firstOrNull { it.id == down.id } ?: break
        val movement = latest.position - down.position
        val elapsed = latest.uptimeMillis - down.uptimeMillis
        val isVerticalSwipe =
          abs(movement.y) >= swipeThreshold && abs(movement.y) > abs(movement.x)
        if (
          swipeTarget == null &&
            elapsed < ViewConfiguration.getLongPressTimeout() &&
            isVerticalSwipe
        ) {
          swipeTarget =
            when {
              movement.y < 0f && canExpand && !expanded -> true
              movement.y > 0f && expanded -> false
              else -> null
            }
        }
        if (swipeTarget != null) latest.consume()
      }
      swipeTarget?.let(onExpandedChange)
    }
  }
