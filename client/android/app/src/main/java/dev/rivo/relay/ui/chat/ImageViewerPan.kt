package dev.rivo.relay.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun Offset.coerceToImageBounds(bounds: Offset): Offset =
  Offset(x.coerceIn(-bounds.x, bounds.x), y.coerceIn(-bounds.y, bounds.y))

internal fun resistedImagePan(
  offset: Offset,
  delta: Offset,
  bounds: Offset,
  viewport: Offset,
): Offset =
  Offset(
    resistedPanAxis(offset.x, delta.x, bounds.x, viewport.x),
    resistedPanAxis(offset.y, delta.y, bounds.y, viewport.y),
  )

private fun resistedPanAxis(position: Float, delta: Float, bound: Float, viewport: Float): Float {
  val limit = (viewport * 0.25f).coerceAtLeast(1f)
  // Undo the rubber band before applying the next finger delta. This keeps resistance independent
  // of event frequency and lets reversing direction (or catching a spring) move without a jump.
  val excess = (abs(position) - bound).coerceIn(0f, limit * 0.999f)
  val raw =
    if (excess == 0f) position
    else sign(position) * (bound + excess / (0.55f * (1f - excess / limit)))
  val next = raw + delta
  val nextExcess = (abs(next) - bound).coerceAtLeast(0f)
  return if (nextExcess == 0f) next
  else sign(next) * (bound + limit * nextExcess * 0.55f / (limit + nextExcess * 0.55f))
}

internal suspend fun settleImagePan(
  offset: Offset,
  velocity: Offset,
  bounds: Offset,
  viewport: Offset,
  decay: DecayAnimationSpec<Float>,
  onOffset: (Offset) -> Unit,
) = coroutineScope {
  var position = offset
  // Each axis hits its own edge independently, so a diagonal fling can continue along an edge.
  launch {
    settlePanAxis(offset.x, velocity.x, bounds.x, viewport.x, decay) {
      position = position.copy(x = it)
      onOffset(position)
    }
  }
  launch {
    settlePanAxis(offset.y, velocity.y, bounds.y, viewport.y, decay) {
      position = position.copy(y = it)
      onOffset(position)
    }
  }
}

private suspend fun settlePanAxis(
  position: Float,
  velocity: Float,
  bound: Float,
  viewport: Float,
  decay: DecayAnimationSpec<Float>,
  onPosition: (Float) -> Unit,
) {
  val animation = Animatable(position)
  val maxSpringVelocity = viewport * 8f
  var springVelocity = velocity.coerceIn(-maxSpringVelocity, maxSpringVelocity)
  if (abs(position) <= bound) {
    if (abs(velocity) < 1f) return
    animation.updateBounds(lowerBound = -bound, upperBound = bound)
    val result = animation.animateDecay(velocity, decay) { onPosition(value) }
    if (result.endReason != AnimationEndReason.BoundReached) return
    // Keep the impact velocity when switching from decay to spring, allowing a short overshoot.
    springVelocity = result.endState.velocity.coerceIn(-maxSpringVelocity, maxSpringVelocity)
    animation.updateBounds(lowerBound = null, upperBound = null)
  } else {
    springVelocity *= 0.55f
  }
  animation.animateTo(
    targetValue = animation.value.coerceIn(-bound, bound),
    animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
    initialVelocity = springVelocity,
  ) { onPosition(value) }
}
