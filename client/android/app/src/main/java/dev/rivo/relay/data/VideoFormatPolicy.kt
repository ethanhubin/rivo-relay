package dev.rivo.relay.data

/** Metadata policy for video attachment candidates.
 *
 * A matching extension or MIME type only makes a document a video candidate.
 * The server remains responsible for validating the actual container header.
 */
object VideoFormatPolicy {
  const val ACCEPTED_FORMATS_DESCRIPTION =
    "MP4/M4V、MOV、WebM/MKV、3GP、TS/M2TS、MPEG/VOB、FLV、AVI 或 RMVB"

  internal val acceptedExtensions =
    setOf(
      "mp4", "m4v", "mov", "3gp", "3gpp", "webm", "mkv", "ts", "m2ts", "mts",
      "mpg", "mpeg", "vob", "flv", "avi", "rmvb",
    )
  internal val acceptedMimeTypes =
    setOf(
      "video/mp4", "video/x-m4v", "video/quicktime", "video/3gpp", "video/webm",
      "video/x-matroska", "video/mp2t", "video/mpeg", "video/x-flv", "video/x-msvideo",
    )

  private val playbackMimeByExtension =
    mapOf(
      "mp4" to "video/mp4",
      "m4v" to "video/mp4",
      "mov" to "video/quicktime",
      "3gp" to "video/3gpp",
      "3gpp" to "video/3gpp",
      "webm" to "video/webm",
      "mkv" to "video/x-matroska",
      "ts" to "video/mp2t",
      "m2ts" to "video/mp2t",
      "mts" to "video/mp2t",
      "mpg" to "video/mpeg",
      "mpeg" to "video/mpeg",
      "vob" to "video/mpeg",
      "flv" to "video/x-flv",
      "avi" to "video/x-msvideo",
      "rmvb" to "video/vnd.rn-realvideo",
      "asf" to "video/x-ms-asf",
      "wmv" to "video/x-ms-wmv",
    )

  private val containerByExtension =
    mapOf(
      "mp4" to "mp4", "m4v" to "mp4", "mov" to "quicktime", "3gp" to "3gpp",
      "3gpp" to "3gpp", "webm" to "webm", "mkv" to "matroska", "ts" to "mpegts",
      "m2ts" to "mpegts", "mts" to "mpegts", "mpg" to "mpegps", "mpeg" to "mpegps",
      "vob" to "mpegps", "flv" to "flv", "avi" to "avi", "asf" to "asf", "wmv" to "asf", "rmvb" to "realvideo",
    )

  fun isCandidate(name: String, mimeType: String): Boolean =
    normalizedMimeType(mimeType).startsWith("video/") || extension(name) in acceptedExtensions

  fun isAcceptedAttachment(name: String, mimeType: String): Boolean =
    normalizedMimeType(mimeType) in acceptedMimeTypes || extension(name) in acceptedExtensions

  /** Prefer a trustworthy service MIME and otherwise infer the container MIME from the source name. */
  fun playbackMimeType(name: String, mimeType: String?): String? {
    val normalized = normalizedMimeType(mimeType.orEmpty()).substringBefore(';').trim()
    return normalized.takeIf { it.startsWith("video/") } ?: playbackMimeByExtension[extension(name)]
  }

  fun container(name: String): String? = containerByExtension[extension(name)]

  fun canonicalCodecName(value: String?): String? {
    return value?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
  }

  internal fun normalizedMimeType(mimeType: String): String = mimeType.trim().lowercase()

  internal fun extension(name: String): String =
    name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
}
