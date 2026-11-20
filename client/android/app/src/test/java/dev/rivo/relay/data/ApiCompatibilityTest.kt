package dev.rivo.relay.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiCompatibilityTest {
    @Test fun strictVersionsAndUnknownAdditions() {
        for (json in listOf("{}", "{apiVersions:[]}", "{apiVersions:[0]}", "{apiVersions:[1,1]}", "{apiVersions:['1']}", "{apiVersions:[true]}", "{apiVersions:[1.5]}"))
            assertNull(ApiCompatibility.versions(JSONObject(json)))
        val parsed = ApiCompatibility.parse(JSONObject("{apiVersions:[1,2],features:{'1':['voice.direct','future.feature']},extra:42}"))!!
        assertEquals(1, parsed.selected(setOf(1)))
        assertEquals(2, parsed.selected(setOf(1,2,3)))
        assertNull(parsed.selected(setOf(3)))
        assertTrue(parsed.supports("voice.direct"))
        assertFalse(parsed.supports("session.goals"))
        assertNull(ApiCompatibility.parse(JSONObject("{apiVersions:[1],features:{'1':[true]}}")))
    }
    private fun node(versions: String = "[1]", id: String = "n") = JSONObject("{id:'$id',compatibility:{apiVersions:$versions}}")
    @Test fun releaseOrderDoesNotAffectCompatibilityAndIdentityIsRequired() {
        assertEquals(CompatibilityState.COMPATIBLE, assessNode("n", setOf(1,3), node("[1,2]")).state)
        assertEquals(CompatibilityState.INCOMPATIBLE, assessNode("n", setOf(3), node()).state)
        assertEquals(CompatibilityState.UNKNOWN, assessNode("n", setOf(1), node(id="other")).state)
        assertEquals(CompatibilityState.UNKNOWN, assessNode("n", setOf(1), JSONObject("{id:n,releaseCode:999}")).state)
    }
    @Test fun updateAssessmentNeverIncludesNodeNamesOrIdsInMessages() {
        val good = assessNode("private-machine-id", setOf(1), node(id="private-machine-id"))
        val bad = assessNode("private-machine-id", setOf(2), node(id="private-machine-id"))
        val unknown = assessNode("private-machine-id", null, node(id="private-machine-id"))
        assertTrue(UpdateCompatibility().allowed)
        assertFalse(UpdateCompatibility(candidateKnown = false).allowed)
        assertTrue(UpdateCompatibility(good).allowed)
        assertEquals("当前节点兼容", UpdateCompatibility(good).summary)
        assertFalse(UpdateCompatibility(bad).allowed)
        assertFalse(UpdateCompatibility(unknown).allowed)
        val message = UpdateCompatibility(bad).summary
        assertFalse(message.contains("private-machine-id"))
        assertFalse(message.contains(bad.reason))
    }
}
