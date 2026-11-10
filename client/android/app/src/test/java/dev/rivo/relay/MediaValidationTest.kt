package dev.rivo.relay

import dev.rivo.relay.data.*
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaValidationTest {
    @Test fun transientFailureKeepsImageButPermanentFailureClearsIt() {
        val image = Any()
        assertSame(image, imageAfterValidationFailure(image, IOException("offline")))
        assertSame(image, imageAfterValidationFailure(image, ApiException(503, "unavailable", "retry")))
        assertNull(imageAfterValidationFailure(null, IOException("offline")))
        assertNull(imageAfterValidationFailure(image, ApiException(401, "unauthorized", "denied")))
        assertNull(imageAfterValidationFailure(image, ApiException(404, "media_not_found", "deleted")))
        assertNull(imageAfterValidationFailure(image, ApiException(404, "not_found", "Unknown endpoint")))
    }
    @Test fun coilHttpFailuresUseStatusInsteadOfExceptionSuperclass() {
        val image = Any()
        for (status in listOf(401, 403, 404, 412, 429, 502, 503, 504)) {
            val response = okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("https://example.test/image").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("failure").build()
            val error = coil.network.HttpException(response)
            if (status == 429 || status in 502..504) assertSame(image, imageAfterValidationFailure(image, error))
            else assertNull(imageAfterValidationFailure(image, error))
        }
    }

    @Test fun sharedValidationSurvivesOneWaiterCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flights = MediaValidationFlights(backgroundScope)
        var calls = 0
        val first = async { flights.run("image") { calls++; gate.await() } }
        runCurrent()
        val second = async { flights.run("image") { calls++; gate.await() } }
        runCurrent()
        first.cancel()
        gate.complete(Unit)
        second.await()
        assertEquals(1, calls)
    }
    @Test fun versionMismatchRefreshesOnceAndDoesNotLoop() = runTest {
        var loads = 0
        var refreshes = 0
        val result = retryChangedMedia(refresh = { refreshes++ }) {
            loads++
            if (loads == 1) throw ApiException(412, "media_changed", "changed")
            "new image"
        }
        assertEquals("new image", result)
        assertEquals(2, loads)
        assertEquals(1, refreshes)
        loads = 0
        try {
            retryChangedMedia(refresh = {}) { loads++; throw ApiException(412, "media_changed", "changed") }
            fail("Expected second mismatch")
        } catch (_: ApiException) { assertEquals(2, loads) }
    }
}
