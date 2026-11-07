package dev.rivo.relay

import dev.rivo.relay.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SentImageCacheTest {
    @Test fun onlyMatchingReceiptSuppliesPreviewAndCacheSurvivesReceiptRemoval() {
        val item = JSONObject().put("id", "i").put("type", "userMessage").put("clientId", "c")
            .put("content", JSONArray().put(JSONObject().put("type", "localImage").put("path", "/remote/image.png")))
        val snapshot = sessionSnapshot(JSONObject().put("thread", JSONObject().put("id", "t").put("turns",
            JSONArray().put(JSONObject().put("id", "turn").put("items", JSONArray().put(item))))))
        val receipt = PendingNativeMessage("s", "wrong", "t", listOf(JSONObject().put("type", "image").put("url", "data:image/png;base64,AQID")))
        val cache = SentImageCache()
        cache.reconcile(snapshot, listOf(receipt))
        assertNull(cache.get("/remote/image.png"))
        cache.reconcile(snapshot, listOf(receipt.copy(clientId = "c")))
        cache.reconcile(snapshot, emptyList())
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("/remote/image.png"))
        assertNull(SentImageCache().get("/remote/image.png"))
    }
}
