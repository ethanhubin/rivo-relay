package dev.rivo.relay

import dev.rivo.relay.connection.PairingParser
import org.junit.Assert.*
import org.junit.Test

class PairingParserTest {
    @Test fun scanAndManualResolveSameConnection() {
        val scanned = PairingParser.parse("https://node.example:8787/pair#v=1&code=short-lived-code")
        val manual = PairingParser.parse("https://node.example:8787", "short-lived-code")
        assertEquals(manual, scanned)
        assertEquals("https://node.example:8787", scanned.baseUrl)
        assertFalse(scanned.baseUrl.contains("code"))
    }
    @Test fun releaseRejectsCleartextAndDebugAllowsOnlyLoopback() {
        assertThrows(IllegalArgumentException::class.java) { PairingParser.parse("http://10.0.2.2:8787", "code") }
        assertEquals("http://10.0.2.2:8787", PairingParser.parse("http://10.0.2.2:8787", "code", true).baseUrl)
        assertThrows(IllegalArgumentException::class.java) { PairingParser.parse("http://192.168.1.2:8787", "code", true) }
    }
    @Test fun rejectUnknownVersionCredentialsAndQuery() {
        listOf("https://node.example/pair#v=2&code=x", "https://user:secret@node.example/pair#v=1&code=x", "https://node.example/pair?code=x").forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) { PairingParser.parse(uri, "x") }
        }
    }
    @Test fun prefixAndEncodedCodeAreHandledWithoutLeakingCodeIntoBase() {
        val value = PairingParser.parse("https://node.example/relay/pair#v=1&code=a%2Bb")
        assertEquals("https://node.example/relay", value.baseUrl)
        assertEquals("a+b", value.code)
    }
}
