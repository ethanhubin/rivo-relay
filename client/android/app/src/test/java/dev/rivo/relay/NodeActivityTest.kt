package dev.rivo.relay
import dev.rivo.relay.codex.*
import org.junit.Assert.*
import org.junit.Test

class NodeActivityTest {
    @Test fun absenceMeansIdleOnlyWhenConfirmedAndKnown() {
        assertNull(NodeActivity().status("t"))
        assertEquals("idle", NodeActivity(confirmed = true, known = setOf("t")).status("t"))
        assertNull(NodeActivity(unknown = setOf("t"), confirmed = true).status("t"))
    }
}
