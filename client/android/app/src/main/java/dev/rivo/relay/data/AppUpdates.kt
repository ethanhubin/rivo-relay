package dev.rivo.relay.data

data class AppRelease(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val preview: Boolean, val sha256: String = "", val size: Long = 0)

/** Compare release version numbers numerically, never lexicographically. */
internal fun versionParts(value: String): List<Int>? {
    if (!Regex("v?\\d+\\.\\d+\\.\\d+").matches(value)) return null
    return value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
}
internal fun compareVersions(left: String, right: String): Int {
    val a = requireNotNull(versionParts(left))
    val b = requireNotNull(versionParts(right))
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
}
