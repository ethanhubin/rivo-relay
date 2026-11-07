@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package dev.rivo.relay.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import dev.rivo.relay.data.VideoFormatPolicy

internal data class VideoViewerItem(
  val preview: String?,
  val original: String,
  val source: String = original,
  val displayName: String? = null,
  val mimeType: String? = null,
  val container: String? = null,
  val videoCodec: String? = null,
  val audioCodec: String? = null,
  val mediaId: String? = null,
)

internal data class VideoViewerSource(
  val items: List<VideoViewerItem>,
  val initialIndex: Int,
  val origin: Rect,
)

internal enum class VideoPlaybackFailureKind {
  NETWORK,
  CONTAINER,
  VIDEO_DECODER,
  AUDIO_DECODER,
  DRM,
  EXCEEDS_CAPABILITIES,
  UNKNOWN,
}

internal data class VideoPlaybackFailure(
  val kind: VideoPlaybackFailureKind,
  val title: String,
  val message: String,
)

internal fun VideoViewerItem.toMediaItem(): MediaItem {
  return MediaItem.Builder()
    .setUri(original)
    .apply { resolvedPlaybackMimeType()?.let(::setMimeType) }
    .build()
}

internal fun VideoViewerItem.resolvedPlaybackMimeType(): String? =
  VideoFormatPolicy.playbackMimeType(original, mimeType)

internal fun PlaybackException.toVideoPlaybackFailure(): VideoPlaybackFailure {
  val sampleMimeType = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
  val rendererType =
    when {
      MimeTypes.isAudio(sampleMimeType) -> C.TRACK_TYPE_AUDIO
      MimeTypes.isVideo(sampleMimeType) -> C.TRACK_TYPE_VIDEO
      else -> null
    }
  return classifyVideoPlaybackFailure(errorCode, rendererType)
}

internal fun classifyVideoPlaybackFailure(
  errorCode: Int,
  rendererType: Int? = null,
): VideoPlaybackFailure =
  when {
    errorCode in 2000..2999 ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.NETWORK,
        "视频网络连接失败",
        "请检查连接后重试；也可以交给其他应用打开。",
      )
    errorCode in 3000..3999 ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.CONTAINER,
        "视频容器无法解析",
        "文件可能损坏，或当前播放器不支持该容器；可尝试兼容版本或其他应用。",
      )
    errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.EXCEEDS_CAPABILITIES,
        "视频超出设备能力",
        "当前设备无法稳定解码这段视频；可准备兼容版本或交给其他应用。",
      )
    errorCode in 6000..6999 ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.DRM,
        "受保护视频无法播放",
        "当前 App 无法播放该 DRM 内容。",
      )
    errorCode in 4000..4999 && rendererType == C.TRACK_TYPE_AUDIO ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.AUDIO_DECODER,
        "音频编码不受支持",
        "画面容器可以读取，但设备缺少所需音频解码器。",
      )
    errorCode in 4000..4999 ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.VIDEO_DECODER,
        "视频编码不受支持",
        "设备缺少所需视频解码器；可准备兼容版本或交给其他应用。",
      )
    else ->
      VideoPlaybackFailure(
        VideoPlaybackFailureKind.UNKNOWN,
        "视频播放失败",
        "未能确定失败原因，请重试或交给其他应用打开。",
      )
  }

/** Small, logcat-only diagnostic session for real NAS playback acceptance. */
internal class VideoPlaybackDiagnostics(
  private val clock: () -> Long = SystemClock::elapsedRealtime,
  private val sink: (String) -> Unit = { Log.i("QiGuangVideo", it) },
) {
  private var mediaId: String? = null
  private var attemptStartedAtMs = 0L
  private var seekStartedAtMs = 0L
  private var firstFrameRecorded = false
  private var videoDecoder: String? = null
  private var audioDecoder: String? = null

  fun start(item: VideoViewerItem) {
    mediaId = item.mediaId
    attemptStartedAtMs = clock()
    seekStartedAtMs = 0L
    firstFrameRecorded = false
    videoDecoder = null
    audioDecoder = null
    emit(
      "attempt",
      "container" to item.container,
      "video_codec" to item.videoCodec,
      "audio_codec" to item.audioCodec,
    )
  }

  fun decoder(track: String, name: String) {
    if (track == "video") videoDecoder = name else if (track == "audio") audioDecoder = name
    emit("decoder", "track" to track, "name" to name)
  }

  fun seekStarted() {
    if (mediaId != null) seekStartedAtMs = clock()
  }

  fun renderedFrame() {
    val now = clock()
    when {
      seekStartedAtMs > 0L -> {
        emit("seek_ready", "elapsed_ms" to now - seekStartedAtMs)
        seekStartedAtMs = 0L
      }
      !firstFrameRecorded -> {
        emit("first_frame", "elapsed_ms" to now - attemptStartedAtMs)
        firstFrameRecorded = true
      }
    }
  }

  fun failed(error: PlaybackException, failure: VideoPlaybackFailure) {
    emit(
      "failure",
      "error_code" to error.errorCode,
      "kind" to failure.kind.name.lowercase(),
      "video_decoder" to videoDecoder,
      "audio_decoder" to audioDecoder,
    )
  }

  private fun emit(event: String, vararg fields: Pair<String, Any?>) {
    val id = mediaId ?: return
    val details =
      fields.mapNotNull { (key, value) ->
        value?.toString()?.takeIf(String::isNotBlank)?.let {
          "$key=${it.replace(Regex("\\s+"), "_")}"
        }
      }
    sink((listOf("event=$event", "media_id=$id") + details).joinToString(" "))
  }
}
