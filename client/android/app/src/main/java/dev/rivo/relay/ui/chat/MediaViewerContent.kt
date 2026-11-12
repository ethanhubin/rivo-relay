package dev.rivo.relay.ui.chat

import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.decode.VideoFrameDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import dev.rivo.relay.data.MediaGenerationInfo
import dev.rivo.relay.data.MediaPromptResult
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ImageViewerItem(
  val preview: String,
  val original: String,
  val mediaId: String? = null,
  val forwardSource: String? = original,
  val tags: List<String> = emptyList(),
  val summary: String = "",
  val taggingState: String = "",
  val hasGenerationInfo: Boolean = false,
)

internal data class ImageViewerSource(
  val items: List<ImageViewerItem>,
  val initialIndex: Int,
  val origin: Rect,
)

@Composable
internal fun AttachmentImageViewer(
  sources: List<String>,
  initialIndex: Int,
  onDismiss: () -> Unit,
) {
  if (sources.isEmpty()) return
  FullScreenImage(
    image =
      ImageViewerSource(
        items = sources.map { source -> ImageViewerItem(source, source) },
        initialIndex = initialIndex.coerceIn(sources.indices),
        origin = Rect.Zero,
      ),
    onDismiss = onDismiss,
    onSave = null,
    onReversePrompt = { _, _ -> error("附件图片不支持提示词反推") },
    onGenerationInfo = { error("附件图片没有生成信息") },
    onCopyPrompt = {},
  )
}

private sealed interface ReversePromptState {
  data object Loading : ReversePromptState

  data class Ready(val result: MediaPromptResult) : ReversePromptState

  data class Failed(val message: String) : ReversePromptState
}

private data class ReversePromptStyle(val id: String, val label: String)

private val ReversePromptStyles =
  listOf(
    ReversePromptStyle("zh-natural", "中文"),
    ReversePromptStyle("en-natural", "英文"),
  )

private data class ReversePromptPanel(
  val mediaId: String,
  val style: ReversePromptStyle,
  val state: ReversePromptState,
)

private sealed interface GenerationInfoState {
  data object Loading : GenerationInfoState

  data class Ready(val info: MediaGenerationInfo) : GenerationInfoState

  data class Failed(val message: String) : GenerationInfoState
}

private data class GenerationInfoPanel(
  val mediaId: String,
  val state: GenerationInfoState,
)

private enum class ImageGesturePath {
  UNDECIDED,
  SWIPE,
  TRANSFORM,
}

private const val IMAGE_PAGE_SWIPE_DISTANCE_FRACTION = 0.12f

internal fun imagePageSwipeDelta(
  offsetX: Float,
  velocityX: Float,
  viewportWidth: Float,
  minimumFlingVelocity: Float,
  currentIndex: Int,
  lastIndex: Int,
): Int {
  val distanceThreshold = viewportWidth * IMAGE_PAGE_SWIPE_DISTANCE_FRACTION
  val requestedDelta = when {
    velocityX <= -minimumFlingVelocity -> 1
    velocityX >= minimumFlingVelocity -> -1
    offsetX <= -distanceThreshold -> 1
    offsetX >= distanceThreshold -> -1
    else -> 0
  }
  return requestedDelta.takeIf { currentIndex + it in 0..lastIndex } ?: 0
}

@Composable
internal fun FullScreenImage(
  image: ImageViewerSource,
  onDismiss: () -> Unit,
  onSave: ((String) -> Unit)?,
  onReversePrompt: (suspend (String, String) -> MediaPromptResult)? = null,
  onGenerationInfo: suspend (String) -> MediaGenerationInfo,
  onCopyPrompt: (String) -> Unit,
  onEditComplete: ((Uri) -> Unit)? = null,
  onShare: ((String) -> Unit)? = null,
) {
  if (image.items.isEmpty()) return
  val viewerIdentity = remember(image.items.map { it.original }, image.initialIndex) { Any() }
  val initialIndex = image.initialIndex.coerceIn(image.items.indices)
  var currentIndex by remember(viewerIdentity) { mutableIntStateOf(initialIndex) }
  val currentImage = image.items[currentIndex]
  var moreMenuOpen by remember(currentImage.original) { mutableStateOf(false) }
  var longPressMenuPosition by remember(currentImage.original) { mutableStateOf<Offset?>(null) }
  val forwardLink = LocalCodexLinkForwarder.current

  var intrinsicImageSize by remember(currentImage.original) {
    mutableStateOf<ComposeSize?>(null)
  }
  var editingSource by remember(viewerIdentity) { mutableStateOf<Uri?>(null) }
  val scope = rememberCoroutineScope()
  val transition = remember(viewerIdentity) { Animatable(0f) }
  var swipeOffset by remember(viewerIdentity) { mutableFloatStateOf(0f) }
  var swipeAnimation by remember(viewerIdentity) { mutableStateOf<Job?>(null) }
  var closing by remember(viewerIdentity) { mutableStateOf(false) }
  var scale by remember(viewerIdentity) { mutableFloatStateOf(1f) }
  var offset by remember(viewerIdentity) { mutableStateOf(Offset.Zero) }
  var panAnimation by remember(viewerIdentity) { mutableStateOf<Job?>(null) }
  var zoomAnimation by remember(viewerIdentity) { mutableStateOf<Job?>(null) }
  val panDecay = rememberSplineBasedDecay<Float>()
  var gesturePath by remember(viewerIdentity) { mutableStateOf(ImageGesturePath.UNDECIDED) }
  var reversePromptVisible by remember(viewerIdentity) { mutableStateOf(false) }
  var reversePromptPanel by remember(viewerIdentity) { mutableStateOf<ReversePromptPanel?>(null) }
  var reversePromptStyle by remember(viewerIdentity) { mutableStateOf(ReversePromptStyles.first()) }
  var generationInfoVisible by remember(viewerIdentity) { mutableStateOf(false) }
  var generationInfoPanel by remember(viewerIdentity) { mutableStateOf<GenerationInfoPanel?>(null) }
  var copyConfirmationVisible by remember(viewerIdentity) { mutableStateOf(false) }
  var copyConfirmationVersion by remember(viewerIdentity) { mutableIntStateOf(0) }
  DisposableEffect(viewerIdentity) {
    onDispose {
      panAnimation?.cancel()
      zoomAnimation?.cancel()
      swipeAnimation?.cancel()
    }
  }
  val copyPromptWithFeedback: (String) -> Unit = { prompt ->
    onCopyPrompt(prompt)
    copyConfirmationVersion += 1
    copyConfirmationVisible = true
  }

  LaunchedEffect(copyConfirmationVersion) {
    if (copyConfirmationVersion > 0) {
      delay(1_500)
      copyConfirmationVisible = false
    }
  }

  fun reverseCurrentImagePrompt(style: ReversePromptStyle = reversePromptStyle) {
    val mediaId = currentImage.mediaId ?: return
    val existing =
      reversePromptPanel?.takeIf { it.mediaId == mediaId && it.style == style }?.state
    if (reversePromptVisible && existing is ReversePromptState.Loading) return
    if (existing is ReversePromptState.Ready) {
      reversePromptVisible = true
      return
    }
    reversePromptVisible = true
    generationInfoVisible = false
    reversePromptStyle = style
    reversePromptPanel = ReversePromptPanel(mediaId, style, ReversePromptState.Loading)
    scope.launch {
      try {
        val result = onReversePrompt?.invoke(mediaId, style.id) ?: return@launch
        if (reversePromptPanel?.let { it.mediaId == mediaId && it.style == style } == true) {
          reversePromptPanel = ReversePromptPanel(mediaId, style, ReversePromptState.Ready(result))
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (reversePromptPanel?.let { it.mediaId == mediaId && it.style == style } == true) {
          reversePromptPanel =
            ReversePromptPanel(
              mediaId,
              style,
              ReversePromptState.Failed(error.message ?: "提示词反推失败"),
            )
        }
      }
    }
  }

  fun showCurrentImageGenerationInfo() {
    val mediaId = currentImage.mediaId ?: return
    val existing = generationInfoPanel?.takeIf { it.mediaId == mediaId }?.state
    reversePromptVisible = false
    generationInfoVisible = true
    if (!currentImage.hasGenerationInfo) return
    if (existing is GenerationInfoState.Ready || existing is GenerationInfoState.Loading) return
    generationInfoPanel = GenerationInfoPanel(mediaId, GenerationInfoState.Loading)
    scope.launch {
      try {
        val info = onGenerationInfo(mediaId)
        if (generationInfoPanel?.mediaId == mediaId) {
          generationInfoPanel = GenerationInfoPanel(mediaId, GenerationInfoState.Ready(info))
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        if (generationInfoPanel?.mediaId == mediaId) {
          generationInfoPanel =
            GenerationInfoPanel(
              mediaId,
              GenerationInfoState.Failed(error.message ?: "读取生成信息失败"),
            )
        }
      }
    }
  }

  fun dismissWithAnimation() {
    if (closing) return
    closing = true
    panAnimation?.cancel()
    zoomAnimation?.cancel()
    scale = 1f
    offset = Offset.Zero
    scope.launch {
      swipeAnimation?.cancel()
      swipeOffset = 0f
      transition.animateTo(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
      )
      onDismiss()
    }
  }

  val menuActions = buildList {
    fun action(label: String, title: String, tag: String, enabled: Boolean = true, block: () -> Unit) {
      add(ImageMenuAction(label, title, tag, enabled, block))
    }

    if (currentImage.taggingState.isNotEmpty() || currentImage.hasGenerationInfo ||
      currentImage.summary.isNotBlank() || currentImage.tags.isNotEmpty()) {
      action("信息", "查看图片信息", "fullscreen_info", block = ::showCurrentImageGenerationInfo)
    }
    if (onReversePrompt != null && currentImage.mediaId != null && !currentImage.hasGenerationInfo) {
      action("提示词", "反推提示词", "fullscreen_reverse_prompt", block = ::reverseCurrentImagePrompt)
    }
    if (forwardLink != null && currentImage.forwardSource != null) action("转发", "转发到 Codex", "fullscreen_forward") { forwardLink(CodexLinkAttachment(currentImage.forwardSource!!, fileDisplayName(currentImage.forwardSource!!))) }
    if (onShare != null) action("分享", "分享图片", "fullscreen_share") { onShare(currentImage.original) }
    if (onSave != null) action("保存", "保存图片", "fullscreen_save") { onSave(currentImage.original) }
    if (onEditComplete != null) action("编辑", "编辑图片", "fullscreen_edit") { editingSource = Uri.parse(currentImage.original) }
  }

  LaunchedEffect(viewerIdentity) {
    transition.animateTo(
      targetValue = 1f,
      animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
    )
  }
  LaunchedEffect(currentImage.mediaId) {
    reversePromptVisible = false
    reversePromptPanel = null
    reversePromptStyle = ReversePromptStyles.first()
    generationInfoVisible = false
    generationInfoPanel = null
  }
  Dialog(
    onDismissRequest = {
      when {
        moreMenuOpen -> moreMenuOpen = false
        longPressMenuPosition != null -> longPressMenuPosition = null
        else -> dismissWithAnimation()
      }
    },
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        decorFitsSystemWindows = false,
      ),
  ) {
 dev.rivo.relay.ui.chat.ThemedTextContextMenu {

    val viewerWindow = (LocalView.current.parent as DialogWindowProvider).window
    DisposableEffect(viewerWindow) {
      viewerWindow.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
      viewerWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
      onDispose { }
    }
    BoxWithConstraints(
      modifier =
        Modifier.fillMaxSize()
          .background(Color.Black.copy(alpha = transition.value))
          .testTag("fullscreen_image"),
    ) {
      val density = LocalDensity.current
      val screenWidthPx = with(density) { maxWidth.toPx() }
      val screenHeightPx = with(density) { maxHeight.toPx() }
      val minimumPageFlingVelocity = with(density) { 700.dp.toPx() }
      val validOrigin =
        image.origin.takeIf { it.width > 1f && it.height > 1f }
          ?: Rect(
            left = screenWidthPx * 0.25f,
            top = screenHeightPx * 0.35f,
            right = screenWidthPx * 0.75f,
            bottom = screenHeightPx * 0.65f,
          )
      val progress = transition.value
      val currentLeft = lerp(validOrigin.left, 0f, progress)
      val currentTop = lerp(validOrigin.top, 0f, progress)
      val currentWidth = lerp(validOrigin.width, screenWidthPx, progress)
      val currentHeight = lerp(validOrigin.height, screenHeightPx, progress)
      val latestScale by rememberUpdatedState(scale)
      val latestViewerSize by rememberUpdatedState(Offset(currentWidth, currentHeight))
      val panBoundsForScale: (Float) -> Offset = { zoom ->
        val viewport = ComposeSize(currentWidth, currentHeight)
        val source = intrinsicImageSize ?: viewport
        val fit = ContentScale.Fit.computeScaleFactor(source, viewport)
        Offset(
          ((source.width * fit.scaleX * zoom - currentWidth) / 2f).coerceAtLeast(0f),
          ((source.height * fit.scaleY * zoom - currentHeight) / 2f).coerceAtLeast(0f),
        )
      }
      val latestPanBoundsForScale by rememberUpdatedState(panBoundsForScale)
      LaunchedEffect(intrinsicImageSize, screenWidthPx, screenHeightPx, currentIndex) {
        panAnimation?.cancel()
        zoomAnimation?.cancel()
        if (gesturePath != ImageGesturePath.TRANSFORM) {
          offset = offset.coerceToImageBounds(panBoundsForScale(scale))
        }
      }

      Box(
        modifier =
          Modifier.offset { IntOffset(currentLeft.roundToInt(), currentTop.roundToInt()) }
            .size(
              width = with(density) { currentWidth.toDp() },
              height = with(density) { currentHeight.toDp() },
            )
            .clip(RoundedCornerShape(lerp(12f, 0f, progress).dp))
            // A focusable popup can consume the gesture release; reset recognizers when it closes.
            .pointerInput(image, longPressMenuPosition != null) {
              awaitEachGesture {
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                val gestureWidth = latestViewerSize.x
                swipeAnimation?.cancel()
                panAnimation?.cancel()
                zoomAnimation?.cancel()
                val velocityTracker = VelocityTracker().apply { addPointerInputChange(firstDown) }
                var velocityPointerId: PointerId? = firstDown.id
                val primaryPointerId = firstDown.id
                var path = if (scale > 1f) ImageGesturePath.TRANSFORM else ImageGesturePath.UNDECIDED
                var pendingDrag = Offset.Zero
                gesturePath = path
                while (true) {
                  val event = awaitPointerEvent()
                  val pressedCount = event.changes.count { it.pressed }
                  if (pressedCount > 1) {
                    velocityTracker.resetTracking()
                    velocityPointerId = null
                  } else {
                    val trackedChange =
                      event.changes.firstOrNull { it.pressed }
                        ?: event.changes.firstOrNull { it.id == velocityPointerId }
                    if (trackedChange != null) {
                      if (velocityPointerId != trackedChange.id) velocityTracker.resetTracking()
                      velocityPointerId = trackedChange.id
                      velocityTracker.addPointerInputChange(trackedChange)
                    }
                  }
                  if (path == ImageGesturePath.UNDECIDED && pressedCount > 1) {
                    path = ImageGesturePath.TRANSFORM
                    gesturePath = path
                    swipeOffset = 0f
                  }

                  val primaryChange = event.changes.firstOrNull { it.id == primaryPointerId }
                  val primaryDelta =
                    primaryChange?.let { it.position - it.previousPosition } ?: Offset.Zero
                  when (path) {
                    ImageGesturePath.TRANSFORM -> {
                      if (pressedCount > 1) {
                        val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val center = latestViewerSize / 2f
                        val zoomOffset =
                          (offset - (centroid - center)) * (newScale / scale) + (centroid - center)
                        scale = newScale
                        offset =
                          if (newScale > 1f) resistedImagePan(
                            offset, zoomOffset - offset + event.calculatePan(),
                            latestPanBoundsForScale(newScale), latestViewerSize,
                          ) else Offset.Zero
                      } else if (scale > 1f) {
                        offset = resistedImagePan(
                          offset, event.calculatePan(), latestPanBoundsForScale(scale), latestViewerSize,
                        )
                      }
                    }

                    ImageGesturePath.UNDECIDED -> {
                      pendingDrag += primaryDelta
                      if (
                        kotlin.math.abs(pendingDrag.x) > viewConfiguration.touchSlop &&
                          kotlin.math.abs(pendingDrag.x) >= kotlin.math.abs(pendingDrag.y)
                      ) {
                        path = ImageGesturePath.SWIPE
                        gesturePath = path
                        val atStart = currentIndex == 0 && pendingDrag.x > 0f
                        val atEnd = currentIndex == image.items.lastIndex && pendingDrag.x < 0f
                        val resistedDelta =
                          if (atStart || atEnd) pendingDrag.x * 0.24f else pendingDrag.x
                        swipeOffset = resistedDelta.coerceIn(-gestureWidth, gestureWidth)
                      }
                    }

                    ImageGesturePath.SWIPE -> {
                      val deltaX = primaryDelta.x
                      val atStart = currentIndex == 0 && deltaX > 0f
                      val atEnd = currentIndex == image.items.lastIndex && deltaX < 0f
                      val resistedDelta = if (atStart || atEnd) deltaX * 0.24f else deltaX
                      swipeOffset =
                        (swipeOffset + resistedDelta).coerceIn(-gestureWidth, gestureWidth)
                    }
                  }
                  event.changes.forEach { change ->
                    if (change.position != change.previousPosition) {
                      change.consume()
                    }
                  }
                  if (event.changes.none { it.pressed }) break
                }

                val velocity = velocityTracker.calculateVelocity()
                if (path == ImageGesturePath.TRANSFORM && scale > 1f && !closing) {
                  panAnimation = scope.launch {
                    settleImagePan(
                      offset, Offset(velocity.x, velocity.y), latestPanBoundsForScale(scale),
                      latestViewerSize, panDecay,
                    ) { offset = it }
                  }
                }
                if (path == ImageGesturePath.SWIPE && scale <= 1f) {
                  val pageDelta = imagePageSwipeDelta(
                    offsetX = swipeOffset,
                    velocityX = velocity.x,
                    viewportWidth = gestureWidth,
                    minimumFlingVelocity = minimumPageFlingVelocity,
                    currentIndex = currentIndex,
                    lastIndex = image.items.lastIndex,
                  )
                  val startOffset = swipeOffset
                  val targetOffset =
                    if (pageDelta == 0) 0f
                    else if (pageDelta > 0) -gestureWidth
                    else gestureWidth
                  swipeAnimation =
                    scope.launch {
                      Animatable(startOffset).animateTo(
                        targetValue = targetOffset,
                        animationSpec =
                          tween(
                            durationMillis = if (pageDelta == 0) 160 else 190,
                            easing = FastOutSlowInEasing,
                          ),
                      ) { swipeOffset = value }
                      if (pageDelta != 0) {
                        currentIndex += pageDelta
                        scale = 1f
                        offset = Offset.Zero
                      }
                      swipeOffset = 0f
                    }
                }
                gesturePath = ImageGesturePath.UNDECIDED
              }
            }
            .pointerInput(currentImage.original, longPressMenuPosition != null) {
              detectTapGestures(
                onLongPress = { position ->
                  panAnimation?.cancel()
                  zoomAnimation?.cancel()
                  moreMenuOpen = false
                  longPressMenuPosition = position
                },
                onTap = { dismissWithAnimation() },
                onDoubleTap = { tapPosition ->
                  panAnimation?.cancel()
                  zoomAnimation?.cancel()
                  val startScale = latestScale
                  val startOffset = offset
                  val targetScale = if (startScale > 1f) 1f else 2.5f
                  val targetOffset =
                    if (targetScale == 1f) Offset.Zero
                    else Offset(
                      (latestViewerSize.x / 2f - tapPosition.x) * (targetScale - 1f),
                      (latestViewerSize.y / 2f - tapPosition.y) * (targetScale - 1f),
                    ).coerceToImageBounds(latestPanBoundsForScale(targetScale))
                  zoomAnimation = scope.launch {
                    // Animate scale and translation together to keep the tapped focus stationary.
                    Animatable(0f).animateTo(
                      1f,
                      animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    ) {
                      scale = lerp(startScale, targetScale, value)
                      offset = startOffset + (targetOffset - startOffset) * value
                    }
                  }
                },
              )
            }
            .semantics {
              stateDescription = if (scale > 1f) "图片已放大" else "图片原始尺寸"
            }
            .testTag("fullscreen_image_content")
            // Keep gesture coordinates in the stationary viewport, outside the transformed layer.
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = offset.x
              translationY = offset.y
            },
      ) {
        val visiblePages =
          if (gesturePath == ImageGesturePath.TRANSFORM || scale > 1f) 0..0 else -1..1
        visiblePages.forEach { relativePage ->
          val pageIndex = currentIndex + relativePage
          if (pageIndex in image.items.indices && editingSource == null) {
            FullScreenGalleryPage(
              item = image.items[pageIndex],
              contentDescription =
                if (relativePage == 0) "全屏图片 ${currentIndex + 1} / ${image.items.size}" else null,
              loadOriginal = relativePage == 0,
              screenWidthPx = screenWidthPx,
              screenHeightPx = screenHeightPx,
              onImageSize = { if (relativePage == 0) intrinsicImageSize = it },
              modifier =
                Modifier.fillMaxSize()
                  .offset {
                    IntOffset(
                      (relativePage * currentWidth + swipeOffset).roundToInt(),
                      0,
                    )
                  }
                  .testTag(
                    if (relativePage == 0) "fullscreen_current_page"
                    else "fullscreen_adjacent_page"
                  ),
            )
          }
        }
      }

      if (reversePromptVisible || generationInfoVisible) {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .pointerInput(currentImage.mediaId) {
                detectTapGestures(
                  onTap = {
                    reversePromptVisible = false
                    generationInfoVisible = false
                  }
                )
              }
              .semantics { contentDescription = "隐藏图片信息面板" }
              .testTag("fullscreen_info_panel_dismiss"),
        )
      }

      Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
        .graphicsLayer { alpha = progress }) {
        ImageViewerAction(
          label = "⋮", description = "图片操作", tag = "fullscreen_more",
          onClick = { longPressMenuPosition = null; moreMenuOpen = true },
        )
        androidx.compose.material3.DropdownMenu(
          expanded = moreMenuOpen,
          properties = androidx.compose.ui.window.PopupProperties(focusable = true),
          onDismissRequest = { moreMenuOpen = false },
          modifier = Modifier.testTag("fullscreen_more_menu"),
        ) {
          menuActions.forEach { action ->
            androidx.compose.material3.DropdownMenuItem(
              text = { Text(action.title) }, enabled = action.enabled,
              modifier = Modifier.testTag(action.tag).semantics { contentDescription = action.title },
              onClick = { moreMenuOpen = false; action.onClick() },
            )
          }
        }
      }
      longPressMenuPosition?.let { position ->
        ImageTextActionMenu(
          actions = menuActions.filterNot { it.tag == "fullscreen_reverse_prompt" },
          position = position, tag = "fullscreen_image_actions",
          onDismiss = { longPressMenuPosition = null },
        )
      }

      if (reversePromptVisible) {
        Surface(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .fillMaxWidth()
              .padding(16.dp)
              .testTag("fullscreen_reverse_prompt_panel"),
          shape = RoundedCornerShape(16.dp),
          color = Color.Black.copy(alpha = 0.78f),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            val originalPrompt =
              (reversePromptPanel?.takeIf { it.mediaId == currentImage.mediaId }?.state
                as? ReversePromptState.Ready)
                ?.result
                ?.takeIf { it.source == "original" }
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                if (originalPrompt != null) "原始提示词" else "反推提示词",
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
              )
              ReversePromptStyles.forEach { style ->
                TextButton(onClick = { reverseCurrentImagePrompt(style) }) {
                  Text(
                    style.label,
                    color = if (style == reversePromptStyle) Color.White else Color.White.copy(alpha = 0.58f),
                    fontWeight = if (style == reversePromptStyle) FontWeight.SemiBold else FontWeight.Normal,
                  )
                }
              }
            }
            when (val state = reversePromptPanel?.takeIf { it.mediaId == currentImage.mediaId }?.state) {
              ReversePromptState.Loading ->
                Row(
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                  )
                  Text("正在反推提示词…", color = Color.White)
                }
              is ReversePromptState.Ready -> {
                SelectableReadOnlyText(
                  rendered = AnnotatedString(state.result.prompt),
                  modifier =
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                  testTag = "reverse_prompt_text",
                  onCopyText = copyPromptWithFeedback,
                  textStyle =
                    MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.9f)),
                )
                Button(
                  onClick = { copyPromptWithFeedback(state.result.prompt) },
                  modifier = Modifier.align(Alignment.End),
                ) {
                  Text("复制")
                }
              }
              is ReversePromptState.Failed -> {
                Text(state.message, color = Color.White)
                TextButton(onClick = ::reverseCurrentImagePrompt) { Text("重试") }
              }
              null -> Unit
            }
          }
        }
      }
      if (generationInfoVisible) {
        Surface(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .fillMaxWidth()
              .padding(16.dp)
              .heightIn(max = maxHeight * 0.75f)
              .testTag("fullscreen_info_panel"),
          shape = RoundedCornerShape(16.dp),
          color = Color.Black.copy(alpha = 0.78f),
        ) {
          Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("图片信息", color = Color.White, fontWeight = FontWeight.SemiBold)
            if (currentImage.taggingState.isNotEmpty() || currentImage.summary.isNotBlank() || currentImage.tags.isNotEmpty()) {
              Text("图片描述与标签", color = Color.White.copy(alpha = 0.65f))
              Text(currentImage.summary.ifBlank {
                when (currentImage.taggingState) {
                  "failed" -> "服务不可用"
                  "succeeded" -> "暂无图片描述"
                  else -> "等待自动标签分析"
                }
              }, color = Color.White)
              val tags = currentImage.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
              if (tags.isNotEmpty()) Text(tags.joinToString("  ·  "), color = Color.White.copy(alpha = 0.82f))
            }
            when (
              val state =
                generationInfoPanel?.takeIf { it.mediaId == currentImage.mediaId }?.state
            ) {
              GenerationInfoState.Loading ->
                Row(
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                  )
                  Text("正在读取生成信息…", color = Color.White)
                }
              is GenerationInfoState.Ready -> {
                Text(
                  "生成提示词",
                  color = Color.White.copy(alpha = 0.65f),
                  style = MaterialTheme.typography.labelMedium,
                )
                SelectableReadOnlyText(
                  rendered = AnnotatedString(state.info.prompt),
                  modifier = Modifier,
                  testTag = "generation_prompt_text",
                  onCopyText = copyPromptWithFeedback,
                  textStyle =
                    MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.9f)),
                )
                if (state.info.negativePrompt.isNotEmpty()) {
                  Text(
                    "负面提示词：${state.info.negativePrompt}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
                Column(
                  verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                  generationInfoRows(state.info).forEach { (label, value) ->
                    Text(
                      "$label：$value",
                      color = Color.White.copy(alpha = 0.72f),
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                }
                Button(
                  onClick = { copyPromptWithFeedback(state.info.prompt) },
                  modifier = Modifier.align(Alignment.End),
                ) {
                  Text("复制提示词")
                }
              }
              is GenerationInfoState.Failed -> {
                Text(state.message, color = Color.White)
                TextButton(onClick = ::showCurrentImageGenerationInfo) { Text("重试") }
              }
              null -> Unit
            }
          }
        }
      }
      AnimatedVisibility(
        visible = copyConfirmationVisible,
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
            .testTag("prompt_copy_confirmation"),
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(180)),
      ) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color.Black.copy(alpha = 0.82f),
          contentColor = Color.White,
          shadowElevation = 4.dp,
        ) {
          Text(
            text = "已复制",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }

}
}
  editingSource?.let { source ->
    MediaAttachmentEditor(
      source = source,
      onDismiss = { editingSource = null },
      onComplete = { edited ->
        onEditComplete?.invoke(edited)
        editingSource = null
        onDismiss()
      },
    )
  }
}

private fun generationInfoRows(info: MediaGenerationInfo): List<Pair<String, String>> {
  val labels =
    linkedMapOf(
      "engine" to "引擎",
      "width" to "宽度",
      "height" to "高度",
      "seed" to "种子",
      "quality" to "质量",
      "realism_enabled" to "写实增强",
      "lighting_enhancement_enabled" to "光影增强",
      "gpt_style_enabled" to "风格增强",
      "prompt_id" to "任务 ID",
    )
  val rows = mutableListOf<Pair<String, String>>()
  if (!info.parameters.containsKey("engine") && info.source.isNotEmpty()) {
    rows += "引擎" to info.source
  }
  labels.forEach { (key, label) ->
    info.parameters[key]?.let { value ->
      rows += label to when (value) {
        "true" -> "开启"
        "false" -> "关闭"
        else -> value
      }
    }
  }
  val displayedPrompts = setOf(info.prompt.trim(), info.negativePrompt.trim()).filter { it.isNotEmpty() }.toSet()
  val hiddenKeys = setOf("prompt", "positive_prompt", "negative_prompt", "negative", "source")
  info.parameters.forEach { (key, value) ->
    if (key !in labels && key !in hiddenKeys && value.trim().isNotEmpty() && value.trim() !in displayedPrompts) {
      rows += key to value
    }
  }
  return rows.distinct()
}

@Composable
private fun FullScreenGalleryPage(
  item: ImageViewerItem,
  contentDescription: String?,
  loadOriginal: Boolean,
  screenWidthPx: Float,
  screenHeightPx: Float,
  onImageSize: (ComposeSize) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val mediaClient = LocalCodexMediaClient.current
  // Use the same fitted bounds for previews, originals and zoom/pan calculations.
  val contentScale = ContentScale.Fit
  val originalImageRequest =
    run {
      (mediaClient?.imageRequest(context, item.original, false) ?: ImageRequest.Builder(context).data(item.original))
        .size((screenWidthPx * 2).toInt().coerceIn(1, 4096), (screenHeightPx * 2).toInt().coerceIn(1, 4096))
        .scale(coil.size.Scale.FIT)
        .precision(coil.size.Precision.INEXACT)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(false)
        .build()
    }
  var retryVersion by remember(item.original, mediaClient) { mutableIntStateOf(0) }
  var versionRetryUsed by remember(item.original, mediaClient) { mutableStateOf(false) }
  var versionRefreshPending by remember(item.original, mediaClient) { mutableStateOf(false) }
  var originalLoaded by remember(item.original, mediaClient) {
    mutableStateOf(item.original == item.preview && mediaClient == null)
  }
  var originalFailed by remember(item.original, mediaClient) { mutableStateOf(false) }
  var validationFailed by remember(item.original, mediaClient) { mutableStateOf(false) }
  var validationBlocked by remember(item.original, mediaClient) { mutableStateOf(false) }
  LaunchedEffect(item.original, mediaClient, mediaClient?.revision, retryVersion) {
    try {
      mediaClient?.verify(item.original)
      validationFailed = false
      validationBlocked = false
      versionRefreshPending = false
    }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (error: Exception) {
      validationFailed = true
      if (!dev.rivo.relay.data.isTransientMediaFailure(error)) {
        validationBlocked = true
        originalLoaded = false
      }
    }
  }
  val reportImageSize: (AsyncImagePainter.State.Success) -> Unit = { state ->
    val drawable = state.result.drawable
    if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
      onImageSize(ComposeSize(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat()))
    }
  }
  Box(modifier) {
    if (item.original == item.preview && mediaClient == null) {
      AsyncImage(
        imageLoader = mediaClient?.imageLoader ?: context.imageLoader,
        model = originalImageRequest,
        contentDescription = contentDescription,
        onSuccess = reportImageSize,
        modifier = Modifier.fillMaxSize().testTag("fullscreen_original_loaded"),
        contentScale = contentScale,
      )
    }
    if (!validationBlocked && !originalLoaded && (item.original != item.preview || mediaClient != null)) {
      // Replace the preview atomically: keeping it behind a smaller or transparent
      // original exposes a second image. The original deliberately has no crossfade.
      AsyncImage(
        imageLoader = mediaClient?.imageLoader ?: context.imageLoader,
        model = mediaClient?.imageRequest(context, item.preview, true)?.build() ?: item.preview,
        contentDescription = if (originalLoaded) null else contentDescription,
        modifier = Modifier.fillMaxSize().testTag("fullscreen_thumbnail"),
        // Preview pixels describe the downsampled bitmap, not the original.
        // Fit it immediately so loading the full image does not expand a tiny preview.
        contentScale = ContentScale.Fit,
      )
    }
    if (!validationBlocked && !versionRefreshPending && loadOriginal && (item.original != item.preview || mediaClient != null)) {
      key(retryVersion, item.original, mediaClient) { AsyncImage(
        imageLoader = mediaClient?.imageLoader ?: context.imageLoader,
        model = originalImageRequest,
        contentDescription = if (originalLoaded) contentDescription else null,
        onLoading = {
          originalLoaded = false
          originalFailed = false
        },
        onSuccess = {
          originalLoaded = true
          originalFailed = false
          reportImageSize(it)
        },
        onError = {
          originalFailed = true
          if (!versionRetryUsed && dev.rivo.relay.data.isMediaVersionMismatch(it.result.throwable)) {
            versionRetryUsed = true
            versionRefreshPending = true
            retryVersion++
          }
        },
        modifier =
          Modifier.fillMaxSize()
            .testTag(
              if (originalLoaded) "fullscreen_original_loaded" else "fullscreen_original_loading"
            ),
        contentScale = contentScale,
      ) }
    }
    if (loadOriginal && !originalLoaded && !originalFailed && !validationFailed) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).size(24.dp),
        color = Color.White,
        strokeWidth = 2.dp,
      )
    }
    if (loadOriginal && (originalFailed || validationFailed)) {
      TextButton(
        onClick = { originalFailed = false; versionRetryUsed = false; retryVersion++ },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
          .testTag("fullscreen_original_error"),
      ) { Text(if (originalFailed) "原图加载失败 · 重试" else "图片版本校验失败 · 重试", color = Color.White, style = MaterialTheme.typography.labelMedium) }
    }
  }
}

@Composable
private fun ImageViewerAction(
  label: String,
  description: String,
  tag: String,
  onClick: () -> Unit,
) {
  Surface(
    modifier =
      Modifier.size(44.dp)
        .semantics { contentDescription = description }
        .clickable(onClick = onClick)
        .testTag(tag),
    shape = CircleShape,
    color = Color.White.copy(alpha = 0.12f),
    contentColor = Color.White,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        label,
        style = if (label.length > 1) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Light,
      )
    }
  }
}

internal fun lerp(start: Float, end: Float, fraction: Float): Float =
  start + (end - start) * fraction

@Composable
internal fun VideoPreview(
  source: String,
  previewSource: String?,
  onOpen: (preview: String?, original: String, origin: Rect) -> Unit,
) {
  val context = LocalContext.current
  val mediaClient = LocalCodexMediaClient.current
  val density = LocalDensity.current
  val windowSize = LocalWindowInfo.current.containerSize
  val maxWidth = with(density) { windowSize.width.toDp() * (2f / 3f) }
  val maxHeight = with(density) { windowSize.height.toDp() * (2f / 3f) }
  var videoBounds by remember(source) { mutableStateOf(Rect.Zero) }
  var videoAspectRatio by remember(source, previewSource) { mutableFloatStateOf(16f / 9f) }
  val previewWidth = minOf(maxWidth, maxHeight * videoAspectRatio)
  val previewHeight = previewWidth / videoAspectRatio
  val previewModel =
    previewSource
      ?: ImageRequest.Builder(context)
        .data(source)
        .decoderFactory(VideoFrameDecoder.Factory())
        .build()

  Surface(
    modifier =
      Modifier.size(previewWidth, previewHeight)
        .onGloballyPositioned { videoBounds = it.boundsInWindow() }
        .clickable { onOpen(previewSource, source, videoBounds) }
        .testTag("video_preview"),
    shape = RoundedCornerShape(14.dp),
    color = Color.Black,
  ) {
    Box(contentAlignment = Alignment.Center) {
      AsyncImage(
        imageLoader = mediaClient?.imageLoader ?: context.imageLoader,
        model = previewModel,
        contentDescription = "视频预览图",
        onSuccess = { state ->
          val drawable = state.result.drawable
          val width = drawable.intrinsicWidth
          val height = drawable.intrinsicHeight
          if (width > 0 && height > 0) {
            videoAspectRatio = width.toFloat() / height.toFloat()
          }
        },
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
      Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
      ) {
        Text(
          "▶",
          modifier = Modifier.padding(start = 17.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
          style = MaterialTheme.typography.titleLarge,
        )
      }
      Text(
        "点击全屏播放",
        modifier =
          Modifier.align(Alignment.BottomStart)
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
      )
    }
  }
}
