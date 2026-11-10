package dev.rivo.relay

import dev.rivo.relay.data.decodeInlineImage
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InlineImageTest {
    @Test fun pendingImagePayloadIsDecodedWithoutChangingBytes() {
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), decodeInlineImage("data:image/png;base64,AAEC/w=="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedPayloadIsRejected() { decodeInlineImage("data:image/png;base64,!") }

    @Test(expected = IllegalArgumentException::class)
    fun nonBase64PayloadIsRejected() { decodeInlineImage("data:image/png,abc") }
}
