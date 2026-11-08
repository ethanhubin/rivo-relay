@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package dev.rivo.relay.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import dev.rivo.relay.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_HIDE_DELAY_MS = 2_600L
private const val QUICK_SEEK_MS = 60_000L
private const val MAX_SWIPE_SEEK_MS = 120_000L
private val VIDEO_SPEEDS = listOf(0.5f, 1f, 2f, 4f)

private enum class VideoGestureMode { UNDECIDED, SEEK, VERTICAL, ZOOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenVideo(
  video: VideoViewerSource,
  onDismiss: () -> Unit,
  onDownload: (VideoViewerItem) -> Unit,
  onShare: ((VideoViewerItem) -> Unit)? = null,
  onOpenExternal: (() -> Unit)? = null,
) {
  if (video.items.isEmpty()) return
  val currentIndex = video.initialIndex.coerceIn(video.items.indices)
  val currentVideo = video.items[currentIndex]
  val forwardLink = LocalCodexLinkForwarder.current
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val scope = rememberCoroutineScope()
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  val player =
    remember(video) {
      ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(currentVideo.toMediaItem())
        playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        prepare()
      }
    }
  DisposableEffect(lifecycleOwner, player) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) player.pause()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val diagnostics = remember(video) { VideoPlaybackDiagnostics() }
  val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
  val originalOrientation = remember(activity) { activity?.requestedOrientation }
  val originalBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness }
  var videoReady by remember(video) { mutableStateOf(false) }
  var playbackError by remember(video) { mutableStateOf<VideoPlaybackFailure?>(null) }
  var playbackPositionMs by remember(video) { mutableLongStateOf(0L) }
  var playbackDurationMs by remember(video) { mutableLongStateOf(0L) }
  var draggingProgress by remember(video) { mutableStateOf(false) }
  var draggedPositionMs by remember(video) { mutableLongStateOf(0L) }
  var playbackRequested by remember(video) { mutableStateOf(player.playWhenReady) }
  var controlsVisible by remember(video) { mutableStateOf(true) }
  var controlsInteraction by remember(video) { mutableIntStateOf(0) }
  var speedMenuVisible by remember(video) { mutableStateOf(false) }
  var moreMenuVisible by remember(video) { mutableStateOf(false) }
  var selectedSpeed by remember(video) { mutableFloatStateOf(1f) }
  var doubleSpeedActive by remember(video) { mutableStateOf(false) }
  var locked by remember(video) { mutableStateOf(false) }
  var gestureFeedback by remember(video) { mutableStateOf<String?>(null) }
  var zoomScale by remember(video) { mutableFloatStateOf(1f) }



  fun revealControls() {
    if (!locked) controlsVisible = true
    controlsInteraction += 1
  }

  fun showFeedback(message: String) {
    gestureFeedback = message
    scope.launch {
      delay(750)
      if (gestureFeedback == message) gestureFeedback = null
    }
  }

  fun togglePlayback() {
    if (player.playbackState == Player.STATE_ENDED) {
      player.seekTo(0L)
      player.play()
    } else if (player.playWhenReady) player.pause() else player.play()
    playbackRequested = player.playWhenReady
    revealControls()
  }

  fun seekBy(deltaMs: Long) {
    val duration = playbackDurationMs.takeIf { it > 0L } ?: return
    val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
    diagnostics.seekStarted()
    player.seekTo(target)
    playbackPositionMs = target
    revealControls()
    showFeedback(if (deltaMs < 0L) "后跳 1 分钟" else "前跳 1 分钟")
  }

  fun toggleLock() {
    locked = !locked
    speedMenuVisible = false
    moreMenuVisible = false
    if (locked) {
      controlsVisible = false
      activity?.requestedOrientation =
        if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    } else revealControls()
  }

  fun changeOrientation() {
    activity?.requestedOrientation =
      if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
      else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    revealControls()
  }

  fun retryPlayback() {
    videoReady = false
    playbackError = null
    diagnostics.start(currentVideo)
    player.setMediaItem(currentVideo.toMediaItem(), true)
    player.prepare()
    player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
  }

  DisposableEffect(activity, player) {
    val window = activity?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller?.hide(WindowInsetsCompat.Type.systemBars())
    onDispose {
      controller?.show(WindowInsetsCompat.Type.systemBars())
      originalOrientation?.let { orientation -> activity?.requestedOrientation = orientation }
      if (window != null && originalBrightness != null) {
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
      }
      player.release()
    }
  }
  LaunchedEffect(
    controlsVisible,
    controlsInteraction,
    speedMenuVisible,
    moreMenuVisible,
    draggingProgress,
    locked,
  ) {
    if (controlsVisible && !speedMenuVisible && !moreMenuVisible && !draggingProgress && !locked) {
      delay(AUTO_HIDE_DELAY_MS)
      controlsVisible = false
    }
  }
  LaunchedEffect(player, draggingProgress) {
    while (true) {
      val duration = player.duration.takeIf { it > 0L } ?: 0L
      playbackDurationMs = duration
      if (!draggingProgress) {
        playbackPositionMs = player.currentPosition.coerceIn(0L, duration.coerceAtLeast(0L))
      }
      delay(250)
    }
  }
  DisposableEffect(player) {
    diagnostics.start(currentVideo)
    val listener =
      object : Player.Listener {
        override fun onRenderedFirstFrame() {
          videoReady = true
          playbackError = null
          diagnostics.renderedFrame()
        }

        override fun onPlayerError(error: PlaybackException) {
          videoReady = false
          val failure = error.toVideoPlaybackFailure()
          playbackError = failure
          diagnostics.failed(error, failure)
          revealControls()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
          playbackRequested = playWhenReady
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (playbackState == Player.STATE_ENDED) playbackRequested = false
        }
      }
    val analyticsListener =
      object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
          eventTime: AnalyticsListener.EventTime,
          decoderName: String,
          initializedTimestampMs: Long,
          initializationDurationMs: Long,
        ) = diagnostics.decoder("video", decoderName)

        override fun onAudioDecoderInitialized(
          eventTime: AnalyticsListener.EventTime,
          decoderName: String,
          initializedTimestampMs: Long,
          initializationDurationMs: Long,
        ) = diagnostics.decoder("audio", decoderName)
      }
    player.addListener(listener)
    player.addAnalyticsListener(analyticsListener)
    onDispose {
      player.removeListener(listener)
      player.removeAnalyticsListener(analyticsListener)
    }
  }

  Dialog(
    onDismissRequest = { if (!locked) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen_video")) {
      AndroidView(
        factory = { viewContext ->
          (LayoutInflater.from(viewContext).inflate(R.layout.fullscreen_video_player, null, false) as PlayerView)
            .apply {
              this.player = player
              useController = false
              var mode = VideoGestureMode.UNDECIDED
              var downX = 0f
              var downY = 0f
              var seekStartMs = 0L
              var brightnessStart = .5f
              var volumeStart = 0
              var longPressSpeed = false
              val touchSlop = 14f * resources.displayMetrics.density
              val scaleDetector =
                ScaleGestureDetector(
                  viewContext,
                  object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                      mode = VideoGestureMode.ZOOM
                      return !locked
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                      zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 2.5f)
                      gestureFeedback = "画面 ${String.format(Locale.ROOT, "%.1f", zoomScale)}×"
                      return true
                    }
                  },
                )
              val gestureDetector =
                GestureDetector(
                  viewContext,
                  object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                      if (locked) return true
                      controlsVisible = !controlsVisible
                      controlsInteraction += 1
                      speedMenuVisible = false
                      moreMenuVisible = false
                      return true
                    }

                    override fun onDoubleTap(event: MotionEvent): Boolean {
                      if (!locked) seekBy(if (event.x < width / 2f) -QUICK_SEEK_MS else QUICK_SEEK_MS)
                      return true
                    }

                    override fun onLongPress(event: MotionEvent) {
                      if (locked || mode != VideoGestureMode.UNDECIDED) return
                      longPressSpeed = true
                      doubleSpeedActive = true
                      player.setPlaybackSpeed(2f)
                      player.play()
                      gestureFeedback = ">> 2 倍速"
                    }
                  },
                )
              setOnTouchListener { view, event ->
                if (locked) return@setOnTouchListener false
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                  downX = event.x
                  downY = event.y
                  seekStartMs = player.currentPosition
                  brightnessStart = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: .5f
                  volumeStart = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                  mode = VideoGestureMode.UNDECIDED
                }
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (event.pointerCount > 1) mode = VideoGestureMode.ZOOM
                if (event.actionMasked == MotionEvent.ACTION_MOVE && !longPressSpeed) {
                  val deltaX = event.x - downX
                  val deltaY = event.y - downY
                  if (mode == VideoGestureMode.UNDECIDED && maxOf(abs(deltaX), abs(deltaY)) > touchSlop) {
                    mode = if (abs(deltaX) > abs(deltaY)) VideoGestureMode.SEEK else VideoGestureMode.VERTICAL
                    controlsVisible = false
                    speedMenuVisible = false
                    moreMenuVisible = false
                  }
                  when (mode) {
                    VideoGestureMode.SEEK -> if (playbackDurationMs > 0L) {
                      val delta = (deltaX / view.width * MAX_SWIPE_SEEK_MS).toLong()
                      draggedPositionMs = (seekStartMs + delta).coerceIn(0L, playbackDurationMs)
                      draggingProgress = true
                      val signed = draggedPositionMs - seekStartMs
                      gestureFeedback =
                        "${if (signed < 0) "后跳" else "前跳"} ${formatVideoPosition(abs(signed))}  ·  " +
                          "${formatVideoPosition(draggedPositionMs)} / ${formatVideoPosition(playbackDurationMs)}"
                    }
                    VideoGestureMode.VERTICAL -> {
                      val ratio = (-deltaY / view.height).coerceIn(-1f, 1f)
                      if (downX < view.width / 2f) {
                        val brightness = (brightnessStart + ratio).coerceIn(.05f, 1f)
                        activity?.window?.let { window ->
                          window.attributes = window.attributes.apply { screenBrightness = brightness }
                        }
                        gestureFeedback = "亮度 ${(brightness * 100).roundToInt()}%"
                      } else {
                        val maximum = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
                        val volume = (volumeStart + ratio * maximum).roundToInt().coerceIn(0, maximum)
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                        gestureFeedback = "音量 ${(volume * 100f / maximum).roundToInt()}%"
                      }
                    }
                    else -> Unit
                  }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                  if (longPressSpeed) {
                    longPressSpeed = false
                    doubleSpeedActive = false
                    player.setPlaybackSpeed(selectedSpeed)
                    gestureFeedback = null
                  } else if (event.actionMasked == MotionEvent.ACTION_UP && mode == VideoGestureMode.SEEK && draggingProgress) {
                    diagnostics.seekStarted()
                    player.seekTo(draggedPositionMs)
                    playbackPositionMs = draggedPositionMs
                    draggingProgress = false
                    showFeedback("已定位到 ${formatVideoPosition(draggedPositionMs)}")
                  } else if (mode == VideoGestureMode.VERTICAL || mode == VideoGestureMode.ZOOM) {
                    showFeedback(gestureFeedback.orEmpty())
                  }
                  if (event.actionMasked == MotionEvent.ACTION_CANCEL) draggingProgress = false
                  mode = VideoGestureMode.UNDECIDED
                  view.performClick()
                }
                true
              }
            }
        },
        update = { it.player = player },
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              alpha = if (videoReady) 1f else 0f
              scaleX = zoomScale
              scaleY = zoomScale
            }
            .semantics { contentDescription = "全屏视频 ${currentIndex + 1} / ${video.items.size}" }
            .testTag("fullscreen_video_player"),
      )
      if (!videoReady) {
        val preview =
          currentVideo.preview
            ?: ImageRequest.Builder(context).data(currentVideo.original).decoderFactory(VideoFrameDecoder.Factory()).build()
        AsyncImage(
          model = preview,
          contentDescription = "全屏视频预览图",
          modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoomScale; scaleY = zoomScale }
            .testTag("fullscreen_video_preview"),
          contentScale = ContentScale.Fit,
        )
      }
      if (!videoReady && playbackError == null) {
        CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.dp)
      }
      playbackError?.let { failure ->
        VideoPlaybackError(
          failure = failure,
          modifier = Modifier.align(Alignment.Center),
          onRetry = ::retryPlayback,
          onOpenExternal = {
            if (onOpenExternal != null) onOpenExternal() else {
                val uri = Uri.parse(currentVideo.source)
                if (uri.scheme in setOf("http", "https", "content")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setDataAndType(uri, "video/*")) }
            }
          },
        )
      }

      AnimatedVisibility(
        visible = controlsVisible && !locked,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(220)),
        modifier = Modifier.fillMaxSize(),
      ) {
        VideoControls(
          title = videoTitle(currentVideo),
          isLandscape = isLandscape,
          playing = playbackRequested,
          currentPositionMs = playbackPositionMs,
          durationMs = playbackDurationMs,
          preview = currentVideo.preview,
          speed = selectedSpeed,
          speedMenuVisible = speedMenuVisible,
          moreMenuVisible = moreMenuVisible,
          onClose = onDismiss,
          onForwardToCodex = forwardLink?.let { forward -> { forward(CodexLinkAttachment(currentVideo.source, videoTitle(currentVideo))) } },
          onShare = onShare?.let { share -> { revealControls(); share(currentVideo) } },
          onDownload = {
            revealControls()
            onDownload(currentVideo)
          },
          onTogglePlayback = ::togglePlayback,
          onRewind = { seekBy(-QUICK_SEEK_MS) },
          onForward = { seekBy(QUICK_SEEK_MS) },
          onProgressDragStarted = {
            draggingProgress = true
          },
          onProgressFinished = { targetMs ->
            diagnostics.seekStarted()
            player.seekTo(targetMs)
            draggedPositionMs = targetMs
            playbackPositionMs = targetMs
            draggingProgress = false
            revealControls()
          },
          onToggleSpeedMenu = {
            speedMenuVisible = !speedMenuVisible
            moreMenuVisible = false
            revealControls()
          },
          onToggleMoreMenu = {
            moreMenuVisible = !moreMenuVisible
            speedMenuVisible = false
            revealControls()
          },
          onDismissMoreMenu = { moreMenuVisible = false },
          onSpeedSelected = {
            selectedSpeed = it
            player.setPlaybackSpeed(it)
            speedMenuVisible = false
            revealControls()
          },
          onRotate = ::changeOrientation,
          onLock = ::toggleLock,
        )
      }
      gestureFeedback?.takeIf(String::isNotBlank)?.let {
        Surface(
          modifier = Modifier.align(Alignment.Center).testTag("fullscreen_video_gesture_feedback"),
          shape = RoundedCornerShape(50),
          color = Color.Black.copy(alpha = .68f),
          contentColor = Color.White,
        ) {
          Text(it, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
        }
      }
      if (locked) {
        VideoGlyphAction(
          "▣",
          "解锁播放器控制",
          "fullscreen_video_lock",
          Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
          ::toggleLock,
        )
      }
      if (doubleSpeedActive && gestureFeedback == null) {
        Text(
          ">> 2 倍速",
          Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .68f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("fullscreen_video_double_speed"),
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
        )
      }

    }
  }
}

@Composable
private fun VideoControls(
  title: String,
  isLandscape: Boolean,
  playing: Boolean,
  currentPositionMs: Long,
  durationMs: Long,
  preview: String?,
  speed: Float,
  speedMenuVisible: Boolean,
  moreMenuVisible: Boolean,
  onClose: () -> Unit,
  onDownload: () -> Unit,
  onShare: (() -> Unit)?,
  onForwardToCodex: (() -> Unit)?,
  onTogglePlayback: () -> Unit,
  onRewind: () -> Unit,
  onForward: () -> Unit,
  onProgressDragStarted: () -> Unit,
  onProgressFinished: (Long) -> Unit,
  onToggleSpeedMenu: () -> Unit,
  onToggleMoreMenu: () -> Unit,
  onDismissMoreMenu: () -> Unit,
  onSpeedSelected: (Float) -> Unit,
  onRotate: () -> Unit,
  onLock: () -> Unit,
) {
  Box(Modifier.fillMaxSize().testTag("fullscreen_video_controls")) {
    Box(
      Modifier.fillMaxWidth().height(if (isLandscape) 96.dp else 120.dp)
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .72f), Color.Transparent)))
    )
    Row(
      Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      VideoGlyphAction("×", "关闭视频", "fullscreen_video_close", onClick = onClose)
      Text(
        title,
        Modifier.weight(1f).padding(horizontal = 12.dp),
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Box {
        VideoGlyphAction("⋮", "视频操作", "fullscreen_video_more", onClick = onToggleMoreMenu)
        androidx.compose.material3.DropdownMenu(
          expanded = moreMenuVisible,
          onDismissRequest = onDismissMoreMenu,
          modifier = Modifier.testTag("fullscreen_video_more_menu"),
        ) {

          androidx.compose.material3.DropdownMenuItem(
            text = { Text("播放速度 ${speedLabel(speed)}×") },
            modifier = Modifier.testTag("fullscreen_video_speed")
              .semantics { contentDescription = "选择播放速度" },
            onClick = { onDismissMoreMenu(); onToggleSpeedMenu() },
          )
          if (onForwardToCodex != null) {
            androidx.compose.material3.DropdownMenuItem(
              text = { Text("发送到 Codex") },
              modifier = Modifier.testTag("fullscreen_video_forward_codex"),
              onClick = { onDismissMoreMenu(); onForwardToCodex() },
            )
          }
          if (onShare != null) {
            androidx.compose.material3.DropdownMenuItem(
              text = { Text("分享") },
              modifier = Modifier.testTag("fullscreen_video_share"),
              onClick = { onDismissMoreMenu(); onShare() },
            )
          }
          androidx.compose.material3.DropdownMenuItem(
            text = { Text("保存到本机") },
            modifier = Modifier.testTag("fullscreen_video_download"),
            onClick = { onDismissMoreMenu(); onDownload() },
          )
        }
      }
    }

    Box(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (isLandscape) 220.dp else 270.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
    )
    Column(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
        .padding(vertical = 10.dp),
    ) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = if (isLandscape) 28.dp else 16.dp)
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VideoBottomAction("▣", "锁定", "fullscreen_video_lock", onLock)
        VideoRoundIconControl(
          iconRes = R.drawable.ic_video_rewind,
          description = "后跳 1 分钟",
          tag = "fullscreen_video_rewind",
          size = 48,
          iconSize = 25,
          onClick = onRewind,
        )
        VideoRoundIconControl(
          iconRes = if (playing) R.drawable.ic_video_pause else R.drawable.ic_video_play,
          description = if (playing) "暂停视频" else "播放视频",
          tag = "fullscreen_video_play_pause",
          size = 64,
          iconSize = 31,
          onClick = onTogglePlayback,
        )
        VideoRoundIconControl(
          iconRes = R.drawable.ic_video_forward,
          description = "前跳 1 分钟",
          tag = "fullscreen_video_forward",
          size = 48,
          iconSize = 25,
          onClick = onForward,
        )
        VideoOrientationAction(isLandscape = isLandscape, onClick = onRotate)
      }
      VideoProgress(
        currentPositionMs,
        durationMs,
        preview,
        onProgressDragStarted,
        onProgressFinished,
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      )
    }

    if (speedMenuVisible) {
      VideoSpeedMenu(
        speed,
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 14.dp),
        onSpeedSelected,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoProgress(
  positionMs: Long,
  durationMs: Long,
  preview: String?,
  onDragStarted: () -> Unit,
  onValueChangeFinished: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var dragging by remember { mutableStateOf(false) }
  var draggedPositionMs by remember { mutableLongStateOf(positionMs) }
  val displayedPositionMs =
    if (dragging) draggedPositionMs else positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
  BoxWithConstraints(modifier.fillMaxWidth().testTag("fullscreen_video_progress_area")) {
    val fraction = if (durationMs > 0L) (displayedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    if (dragging) {
      val bubbleWidth = 116.dp
      Column(
        Modifier.offset(x = (maxWidth - bubbleWidth) * fraction).padding(bottom = 42.dp).width(bubbleWidth)
          .background(Color.Black.copy(alpha = .78f), RoundedCornerShape(10.dp)).padding(5.dp)
          .testTag("fullscreen_video_seek_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (preview != null) {
          AsyncImage(
            model = preview,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(7.dp)),
            contentScale = ContentScale.Crop,
          )
        }
        Text(
          "${formatVideoPosition(displayedPositionMs)} / ${formatVideoPosition(durationMs)}",
          color = Color.White,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
        )
      }
    }
    Column {
      Slider(
        value = displayedPositionMs.toFloat(),
        onValueChange = {
          draggedPositionMs = it.toLong().coerceIn(0L, durationMs)
          if (!dragging) {
            dragging = true
            onDragStarted()
          }
        },
        onValueChangeFinished = {
          if (dragging) {
            val targetMs = draggedPositionMs
            dragging = false
            onValueChangeFinished(targetMs)
          }
        },
        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
        enabled = durationMs > 0L,
        // Keep the entire band touchable, including the space above and below the track.
        // A fixed height also keeps the track in place when scrubbing starts.
        modifier = Modifier.fillMaxWidth().height(72.dp).testTag("fullscreen_video_progress"),
        colors = SliderDefaults.colors(
          thumbColor = Color.White,
          activeTrackColor = Color.White,
          inactiveTrackColor = Color.White.copy(alpha = .38f),
        ),
        thumb = {
          Box(
            Modifier.offset(y = 3.5.dp)
              .size(if (dragging) 13.dp else 9.dp)
              .background(Color.White, CircleShape)
              .testTag("fullscreen_video_progress_thumb")
          )
        },
        track = { state ->
          SliderDefaults.Track(
            sliderState = state,
            modifier =
              Modifier.height(if (dragging) 9.dp else 7.dp)
                .testTag("fullscreen_video_progress_track"),
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .4f)),
            drawStopIndicator = null,
            thumbTrackGapSize = 0.dp,
            trackInsideCornerSize = 0.dp,
          )
        },
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatVideoPosition(displayedPositionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
        Text(formatVideoPosition(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun VideoSpeedMenu(selected: Float, modifier: Modifier, onSelect: (Float) -> Unit) {
  Surface(
    modifier.testTag("fullscreen_video_speed_menu"),
    shape = RoundedCornerShape(18.dp),
    color = Color(0xEC202124),
    contentColor = Color.White,
    border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
  ) {
    Row(
      Modifier.padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      VIDEO_SPEEDS.forEach { speed ->
        Text(
          "${speedLabel(speed)}×",
          Modifier.width(66.dp)
            .background(if (speed == selected) Color.White.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable { onSelect(speed) }.padding(vertical = 10.dp)
            .semantics { contentDescription = "${speedLabel(speed)} 倍速" }
            .testTag("fullscreen_video_speed_${speedLabel(speed)}"),
          color = Color.White,
          textAlign = TextAlign.Center,
          fontWeight = if (speed == selected) FontWeight.Bold else FontWeight.Normal,
        )
      }
    }
  }
}

@Composable
private fun VideoRoundIconControl(
  iconRes: Int,
  description: String,
  tag: String,
  size: Int,
  iconSize: Int,
  onClick: () -> Unit,
) {
  Surface(
    Modifier.size(size.dp).semantics { contentDescription = description }.clickable(onClick = onClick).testTag(tag),
    shape = CircleShape,
    color = Color.Black.copy(alpha = .34f),
    contentColor = Color.White,
    border = BorderStroke(1.dp, Color.White.copy(alpha = .72f)),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(iconSize.dp),
        tint = Color.White,
      )
    }
  }
}

@Composable
internal fun VideoOrientationAction(isLandscape: Boolean, onClick: () -> Unit) {
  VideoBottomAction(
    label = "",
    text = if (isLandscape) "竖屏" else "横屏",
    description = if (isLandscape) "切换到竖屏" else "切换到横屏",
    tag = "fullscreen_video_rotate",
    onClick = onClick,
    iconRes = R.drawable.ic_video_orientation,
  )
}

@Composable
private fun VideoGlyphAction(
  label: String,
  description: String,
  tag: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  iconRes: Int? = null,
) {
  Surface(
    modifier.size(42.dp).semantics { contentDescription = description }.clickable(onClick = onClick).testTag(tag),
    shape = CircleShape,
    color = Color.Black.copy(alpha = .28f),
    contentColor = Color.White,
  ) {
    Box(contentAlignment = Alignment.Center) {
      when {
        iconRes != null -> Icon(painterResource(iconRes), null, Modifier.size(22.dp))
        "lock" in tag -> Icon(painterResource(R.drawable.ic_video_lock), null, Modifier.size(22.dp))
        else -> Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
      }
    }
  }
}

@Composable
private fun VideoBottomAction(
  label: String,
  text: String,
  tag: String,
  onClick: () -> Unit,
  iconRes: Int? = null,
  description: String? = null,
) {
  Column(
    Modifier.width(72.dp)
      .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
      .clickable(onClick = onClick).padding(vertical = 4.dp).testTag(tag),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when {
      iconRes != null -> Icon(painterResource(iconRes), null, Modifier.size(26.dp), tint = Color.White)
      "lock" in tag -> Icon(painterResource(R.drawable.ic_video_lock), null, Modifier.size(24.dp), tint = Color.White)
      else -> Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
    Text(text, color = Color.White.copy(alpha = .76f), style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun VideoPlaybackError(
  failure: VideoPlaybackFailure,
  modifier: Modifier,
  onRetry: () -> Unit,
  onOpenExternal: () -> Unit,
) {
  Column(
    modifier.background(Color.Black.copy(alpha = .78f), RoundedCornerShape(14.dp)).padding(18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      failure.title,
      color = Color.White,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.testTag("fullscreen_video_error_title"),
    )
    Text(
      failure.message,
      color = Color.White.copy(alpha = .82f),
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.testTag("fullscreen_video_error_message"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = onRetry) { Text("重试") }
      TextButton(onClick = onOpenExternal) { Text("其他应用打开") }
    }
  }
}

private fun formatVideoPosition(milliseconds: Long): String {
  val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
  val hours = totalSeconds / 3_600L
  val minutes = (totalSeconds % 3_600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
  else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

private fun speedLabel(speed: Float): String =
  when (speed) {
    .5f -> "0.5"
    1f -> "1.0"
    2f -> "2.0"
    4f -> "4.0"
    else -> String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
  }

internal fun videoTitle(item: VideoViewerItem): String =
  item.displayName?.takeIf(String::isNotBlank)
    ?: Uri.parse(item.original).lastPathSegment?.substringBefore('?')?.takeIf(String::isNotBlank)
    ?: "视频"

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
