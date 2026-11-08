package dev.rivo.relay

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.rivo.relay.ui.login.decodeQrPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImageDecoderTest {
    @Test fun readsPairingLinkFromNormalAndInvertedImages() {
        val link = "http://100.124.150.1:18767/#code=example&v=2"
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 400, 400)
        for (inverted in listOf(false, true)) {
            val pixels = IntArray(400 * 400) { index ->
                if (matrix[index % 400, index / 400] xor inverted) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            assertEquals(link, decodeQrPixels(400, 400, pixels))
        }
    }
    @Test fun blankImageHasNoCode() {
        assertNull(decodeQrPixels(200, 200, IntArray(200 * 200) { 0xffffffff.toInt() }))
    }
}
