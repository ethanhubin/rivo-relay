package dev.rivo.relay.ui.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decode only QR codes, including light codes on dark backgrounds. */
internal fun decodeQrPixels(width: Int, height: Int, pixels: IntArray): String? {
    val source = RGBLuminanceSource(width, height, pixels)
    val candidates = listOf(source, source.invert())
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    // Preserve detection for camera screenshots and surrounding content first.
    // A clean single-code image can decode even when finder-pattern detection fails.
    for (options in listOf(hints, hints + (DecodeHintType.PURE_BARCODE to true))) {
        for (candidate in candidates) {
            try {
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), options).text
            } catch (_: com.google.zxing.ReaderException) {
                // Try the opposite polarity, then the clean-image fallback.
            }
        }
    }
    return null
}
