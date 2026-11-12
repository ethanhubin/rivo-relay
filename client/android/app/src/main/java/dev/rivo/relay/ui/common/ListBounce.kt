package dev.rivo.relay.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

@Composable
internal fun Modifier.listBounce(): Modifier {
    val limit = with(LocalDensity.current) { 72.dp.toPx() }
    val bounce = remember(limit) { ListBounce(limit) }
    return clipToBounds().nestedScroll(bounce).graphicsLayer { translationY = bounce.offset }
}

private class ListBounce(private val limit: Float) : NestedScrollConnection {
    var offset by mutableFloatStateOf(0f)
        private set

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || offset * available.y >= 0f) return Offset.Zero
        val consumed = available.y.coerceIn(-kotlin.math.abs(offset), kotlin.math.abs(offset))
        offset += consumed
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        val resistance = 0.35f * (1f - kotlin.math.abs(offset) / limit)
        offset = (offset + available.y * resistance).coerceIn(-limit, limit)
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (offset != 0f) {
            animate(offset, 0f, animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )) { value, _ -> offset = value }
            offset = 0f
        }
        return Velocity.Zero
    }
}
