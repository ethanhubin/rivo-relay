package dev.rivo.relay.data

/** Only the latest started validation may publish a version for a source. */
internal class MediaCheckOrder {
    private var sequence = 0L
    private val latest = mutableMapOf<String, Long>()
    @Synchronized fun begin(source: String): Long = (++sequence).also { latest[source] = it }
    @Synchronized fun isLatest(source: String, check: Long): Boolean = latest[source] == check
}
