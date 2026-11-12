package dev.rivo.relay

import dev.rivo.relay.ui.chat.contextUsage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextUsageTest {
    @Test fun usesLatestContextInsteadOfCumulativeConsumption() {
        val usage = contextUsage(JSONObject("""{"last":{"totalTokens":25000},"total":{"totalTokens":900000},"modelContextWindow":100000}"""))
        assertEquals(25, usage.percent)
        assertEquals(25000L, usage.used)
        assertEquals(0.25f, usage.fraction!!, 0f)
    }

    @Test fun unavailableIsDifferentFromZero() {
        assertNull(contextUsage(null).percent)
        assertNull(contextUsage(JSONObject("""{"modelContextWindow":100}""")).percent)
        assertNull(contextUsage(JSONObject("""{"last":{"totalTokens":10},"modelContextWindow":0}""")).percent)
        assertEquals(0, contextUsage(JSONObject("""{"last":{"totalTokens":0},"modelContextWindow":100}""")).percent)
    }

    @Test fun clampsProgressButPreservesActualCountAndAllowsCompaction() {
        val json = JSONObject("""{"last":{"totalTokens":120},"modelContextWindow":100}""")
        assertEquals(100, contextUsage(json).percent)
        assertEquals(120L, contextUsage(json).used)
        json.getJSONObject("last").put("totalTokens", 30)
        assertEquals(30, contextUsage(json).percent)
        json.getJSONObject("last").put("totalTokens", -1)
        assertNull(contextUsage(json).percent)
    }
}
