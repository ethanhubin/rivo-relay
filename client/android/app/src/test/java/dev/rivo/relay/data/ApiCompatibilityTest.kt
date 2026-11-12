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
        assertEquals(CompatibilityState.COMPATIBLE, assessNode("n", "节点", setOf(1,3), node("[1,2]")).state)
        assertEquals(CompatibilityState.INCOMPATIBLE, assessNode("n", "节点", setOf(3), node()).state)
        assertEquals(CompatibilityState.UNKNOWN, assessNode("n", "节点", setOf(1), node(id="other")).state)
        assertEquals(CompatibilityState.UNKNOWN, assessNode("n", "节点", setOf(1), JSONObject("{id:n,releaseCode:999}")).state)
    }
    @Test fun updateMatrixAndConsentBinding() {
        val good = assessNode("n", "节点", setOf(1), node())
        val bad = assessNode("n", "节点", setOf(2), node())
        val unknown = assessNode("n", "节点", null, node())
        assertTrue(UpdateCompatibility(emptyList()).allowed)
        assertFalse(UpdateCompatibility(emptyList(), false).allowed)
        assertTrue(UpdateCompatibility(listOf(good)).allowed)
        assertFalse(UpdateCompatibility(listOf(good)).needsConfirmation)
        assertFalse(UpdateCompatibility(listOf(bad)).allowed)
        assertFalse(UpdateCompatibility(listOf(unknown)).allowed)
        assertFalse(UpdateCompatibility(listOf(bad, unknown)).allowed)
        val partial = UpdateCompatibility(listOf(good, bad))
        assertTrue(partial.allowed)
        assertTrue(partial.needsConfirmation)
        val release = AppRelease("1.0.0", "", "", "", false, sha256="a", apiVersions=setOf(1))
        assertNotEquals(partial.confirmationKey(release), partial.confirmationKey(release.copy(sha256="b")))
        assertNotEquals(partial.confirmationKey(release), partial.copy(nodes=listOf(good, unknown)).confirmationKey(release))
    }
}
