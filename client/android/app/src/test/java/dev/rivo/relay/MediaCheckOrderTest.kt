package dev.rivo.relay

import dev.rivo.relay.data.MediaCheckOrder
import org.junit.Assert.*
import org.junit.Test

class MediaCheckOrderTest {
    @Test fun lateValidationCannotRestoreOldVersion() {
        val checks = MediaCheckOrder()
        val old = checks.begin("same.png")
        val other = checks.begin("other.png")
        val fresh = checks.begin("same.png")
        assertFalse(checks.isLatest("same.png", old))
        assertTrue(checks.isLatest("same.png", fresh))
        assertTrue(checks.isLatest("other.png", other))
        assertFalse(checks.isLatest("other.png", fresh))
    }
}
