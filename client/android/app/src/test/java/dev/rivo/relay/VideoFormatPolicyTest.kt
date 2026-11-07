package dev.rivo.relay.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test
import org.json.JSONObject

class VideoFormatPolicyTest {
  @Test
  fun phaseTwoAttachmentPolicyIsCentralizedAndCaseInsensitive() {
    assertEquals(
      setOf(
        "mp4", "m4v", "mov", "3gp", "3gpp", "webm", "mkv", "ts", "m2ts", "mts",
        "mpg", "mpeg", "vob", "flv", "avi", "rmvb",
      ),
      VideoFormatPolicy.acceptedExtensions,
    )
    assertEquals(
      setOf(
        "video/mp4", "video/x-m4v", "video/quicktime", "video/3gpp", "video/webm",
        "video/x-matroska", "video/mp2t", "video/mpeg", "video/x-flv", "video/x-msvideo",
      ),
      VideoFormatPolicy.acceptedMimeTypes,
    )
    assertTrue(VideoFormatPolicy.isAcceptedAttachment("CLIP.M4V", "application/octet-stream"))
    assertTrue(VideoFormatPolicy.isAcceptedAttachment("clip.bin", " Video/MP4 "))
    assertTrue(VideoFormatPolicy.isAcceptedAttachment("clip.ts", "video/mp2t"))
    assertTrue(VideoFormatPolicy.isAcceptedAttachment("sample.RMVB", "application/octet-stream"))
  }

  @Test
  fun broadPickerCandidatesStillRejectContainersOutsideTheAttachmentPolicy() {
    assertTrue(VideoFormatPolicy.isCandidate("clip.ts", "video/mp2t"))
    assertTrue(VideoFormatPolicy.isAcceptedAttachment("clip.ts", "video/mp2t"))
    assertTrue(VideoFormatPolicy.isCandidate("clip.wmv", "video/x-ms-wmv"))
    assertFalse(VideoFormatPolicy.isAcceptedAttachment("clip.wmv", "video/x-ms-wmv"))
    assertFalse(VideoFormatPolicy.isCandidate("notes.txt", "text/plain"))
  }

  @Test
  fun playbackMimeAndContainerAreNormalizedFromServiceMetadataOrName() {
    assertEquals("video/mp2t", VideoFormatPolicy.playbackMimeType("movie.bin", " Video/MP2T; charset=binary "))
    assertEquals("video/mp2t", VideoFormatPolicy.playbackMimeType("movie.M2TS", null))
    assertEquals("video/mpeg", VideoFormatPolicy.playbackMimeType("movie.vob?download=1", null))
    assertEquals("mpegps", VideoFormatPolicy.container("movie.vob?download=1"))
    assertEquals("asf", VideoFormatPolicy.container("movie.WMV"))
    assertEquals(null, VideoFormatPolicy.playbackMimeType("movie.bin", "application/octet-stream"))
  }

  @Test
  fun currentProbeCodecKeysAreCaseNormalizedWithoutAliases() {
    assertEquals("h264", VideoFormatPolicy.canonicalCodecName("H264"))
    assertEquals("hevc", VideoFormatPolicy.canonicalCodecName("HEVC"))
    assertEquals("av1", VideoFormatPolicy.canonicalCodecName("av1"))
    assertEquals("aac", VideoFormatPolicy.canonicalCodecName("AAC"))
    assertEquals("eac3", VideoFormatPolicy.canonicalCodecName("eac3"))
    assertEquals("dts", VideoFormatPolicy.canonicalCodecName("dts"))
    assertEquals("hevc (hev1)", VideoFormatPolicy.canonicalCodecName("HEVC (hev1)"))
  }

  @Test
  fun sharedCrossLanguageFixtureMatchesAndroidMetadataPolicy() {
    val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream("video-format-policy-v2.json"))
    val payload = resource.bufferedReader().use { JSONObject(it.readText()) }
    assertEquals(2, payload.getInt("version"))
    val cases = payload.getJSONArray("cases")
    repeat(cases.length()) { index ->
      val item = cases.getJSONObject(index)
      val name = item.getString("name")
      val mimeType = item.getString("mime_type")
      assertEquals(
        "$name candidate classification",
        item.getBoolean("candidate"),
        VideoFormatPolicy.isCandidate(name, mimeType),
      )
      assertEquals(
        "$name attachment classification",
        item.getBoolean("accepted_attachment"),
        VideoFormatPolicy.isAcceptedAttachment(name, mimeType),
      )
    }
  }
}
