package dev.rivo.relay.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow

// Matches QiGuang ChatTimelineContent: 1550ms linear sweep, theme-aware text gradient.
@Composable
internal fun ShimmeringStageText(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
  textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
  var widthPx by remember(text) { mutableIntStateOf(1) }
  val transition = rememberInfiniteTransition(label = "assistant_stage_shimmer")
  val position by
    transition.animateFloat(
      initialValue = -0.55f,
      targetValue = 1.55f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1_550, easing = LinearEasing),
        ),
      label = "assistant_stage_shimmer_position",
    )
  val width = widthPx.coerceAtLeast(1).toFloat()
  val center = position * width
  val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
  val highlight = MaterialTheme.colorScheme.onSurface
  val brush =
    Brush.linearGradient(
      0.00f to base,
      0.36f to base,
      0.50f to highlight,
      0.64f to base,
      1.00f to base,
      start = Offset(center - width * 0.48f, 0f),
      end = Offset(center + width * 0.48f, 0f),
    )
  // Render the animated text brush directly.
  BasicText(
    text = text,
    modifier = modifier.onSizeChanged { widthPx = it.width },
    style = LocalTextStyle.current.copy(brush = brush,
      textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
    maxLines = maxLines,
    overflow = overflow,
  )
}
