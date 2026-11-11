package dev.rivo.relay.ui.chat

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** Null means the end is outside the measured window, not that we reached it. */
internal fun LazyListLayoutInfo.distanceFromBottom(): Int? {
    val last = visibleItemsInfo.lastOrNull()?.takeIf { it.index == totalItemsCount - 1 } ?: return null
    return (last.offset + last.size + afterContentPadding - viewportEndOffset).coerceAtLeast(0)
}

/**
 * Commit the anchor before measuring new text. Observing the resulting layout and only then
 * requesting the end shows one frame with grown content followed by a corrective jump.
 * Keep the ordinary top/key anchor when reading history; only explicit user intent resumes follow.
 */
@Composable
internal fun FollowChatContent(
    list: LazyListState,
    following: Boolean,
    lastIndex: Int,
    contentRevision: Any,
) {
    val revision = remember(list) { arrayOfNulls<Any>(1) }
    SideEffect {
        if (revision[0] != contentRevision) {
            revision[0] = contentRevision
            if (following && !list.isScrollInProgress && lastIndex >= 0) list.requestScrollToItem(lastIndex)
        }
    }
    // Async media and viewport resizing do not necessarily change the text revision.
    // Keep this fallback; routine streaming is already anchored in the same measurement above.
    val follow by rememberUpdatedState(following)
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            listOf(follow, list.isScrollInProgress, list.canScrollForward,
                layout.totalItemsCount, layout.viewportEndOffset, layout.afterContentPadding,
                layout.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size })
        }.collect {
            if (follow && !list.isScrollInProgress && list.canScrollForward && list.layoutInfo.totalItemsCount > 0)
                list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
}
