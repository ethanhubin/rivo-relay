package dev.rivo.relay.ui.chat

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class VideoPlaybackTest {
  @Test
  fun nasDiagnosticsRecordFirstFrameDecoderAndOneCompletedSeekWithoutSourcePath() {
    var now = 100L
    val events = mutableListOf<String>()
    val diagnostics = VideoPlaybackDiagnostics(clock = { now }, sink = events::add)
    diagnostics.start(
      VideoViewerItem(
        preview = null,
        original = "https://example.invalid/private/path.mkv",
        container = "matroska",
        videoCodec = "hevc",
        audioCodec = "truehd",
        mediaId = "a".repeat(32),
      )
    )
    diagnostics.decoder("video", "c2.vendor.hevc.decoder")
    now = 350L
    diagnostics.renderedFrame()
    now = 400L
    diagnostics.seekStarted()
    now = 480L
    diagnostics.renderedFrame()

    assertTrue(events.any { "event=first_frame" in it && "elapsed_ms=250" in it })
    assertTrue(events.any { "event=decoder" in it && "c2.vendor.hevc.decoder" in it })
    assertTrue(events.any { "event=seek_ready" in it && "elapsed_ms=80" in it })
    assertTrue(events.none { "private/path" in it })
  }

  @Test
  fun mediaItemResolvesReliableContainerMimeType() {
    val item =
      VideoViewerItem(
        preview = null,
        original = "https://example.invalid/movie.m2ts?token=redacted",
        mimeType = "application/octet-stream",
        container = "mpegts",
      )

    assertEquals("video/mp2t", item.resolvedPlaybackMimeType())
  }

  @Test
  fun playbackErrorsMapToStableUserFacingCategories() {
    assertEquals(
      VideoPlaybackFailureKind.NETWORK,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).kind,
    )
    assertEquals(
      VideoPlaybackFailureKind.CONTAINER,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).kind,
    )
    assertEquals(
      VideoPlaybackFailureKind.EXCEEDS_CAPABILITIES,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES).kind,
    )
    assertEquals(
      VideoPlaybackFailureKind.AUDIO_DECODER,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, C.TRACK_TYPE_AUDIO).kind,
    )
    assertEquals(
      VideoPlaybackFailureKind.VIDEO_DECODER,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, C.TRACK_TYPE_VIDEO).kind,
    )
    assertEquals(
      VideoPlaybackFailureKind.DRM,
      classifyVideoPlaybackFailure(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED).kind,
    )
    assertEquals(VideoPlaybackFailureKind.UNKNOWN, classifyVideoPlaybackFailure(1000).kind)
  }
}
