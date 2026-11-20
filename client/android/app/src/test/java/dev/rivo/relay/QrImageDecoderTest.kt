package dev.rivo.relay

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
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
    @Test fun cleanSyntheticMaskRecoversWhenFinderPatternDetectionFails() {
        // Fixed public fixture, no real pairing code, host or node identity.
        val link = "https://example.invalid/pair#v=2&code=fixture-215&data=" + "0123456789abcdef".repeat(12)
        val size = 570
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.QR_MASK_PATTERN to 1))
        for (inverted in listOf(false, true)) {
            val pixels = IntArray(size * size) { index ->
                if (matrix[index % size, index / size] xor inverted) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            val source = RGBLuminanceSource(size, size, pixels)
            // Verify this fixture exercises the fallback instead of the ordinary path.
            for (candidate in listOf(source, source.invert())) {
                val ordinary = try {
                    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)),
                        mapOf(DecodeHintType.TRY_HARDER to true)).text
                } catch (_: ReaderException) { null }
                assertNull(ordinary)
            }
            assertEquals(link, decodeQrPixels(size, size, pixels))
        }
    }

    @Test fun blankImageHasNoCode() {
        assertNull(decodeQrPixels(200, 200, IntArray(200 * 200) { 0xffffffff.toInt() }))
    }
}
