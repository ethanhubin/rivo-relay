package dev.rivo.relay.data

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun comparesNumericVersions() {
        assertTrue(compareVersions("0.2.10", "0.2.9") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, compareVersions("v0.2.10", "0.2.10"))
        assertTrue(compareVersions("0.2.9", "0.2.10") < 0)
    }

    @Test fun rejectsMalformedVersions() {
        assertNull(versionParts("vgarbage"))
        assertNull(versionParts("0.2.999999999999999"))
        assertNull(versionParts("0.2"))
    }
}
