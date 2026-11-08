package dev.rivo.relay.ui.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decode only QR codes, including light codes on dark backgrounds. */
internal fun decodeQrPixels(width: Int, height: Int, pixels: IntArray): String? {
    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    for (candidate in listOf(source, source.invert())) {
        try {
            return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
        } catch (_: com.google.zxing.ReaderException) {
            // Try the opposite polarity before reporting that no QR code was found.
        }
    }
    return null
}
