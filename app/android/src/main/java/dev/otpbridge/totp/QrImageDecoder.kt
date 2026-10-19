package dev.otpbridge.totp

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Decodes a QR code from a user-picked image, entirely on device. */
object QrImageDecoder {
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val TARGET_EDGE = 1600

    fun decode(resolver: ContentResolver, uri: Uri): String? {
        val bitmap = load(resolver, uri) ?: return null
        try {
            return decodeBitmap(bitmap)
                // Dense transfer QRs in small screenshots may need full resolution.
                ?: if (bitmap.width < 1200) null else load(resolver, uri, 1)?.let { full -> try { decodeBitmap(full) } finally { full.recycle() } }
        } finally { bitmap.recycle() }
    }

    private fun load(resolver: ContentResolver, uri: Uri, forcedSample: Int? = null): Bitmap? {
        resolver.openAssetFileDescriptor(uri, "r")?.use { require(it.length < 0 || it.length <= MAX_IMAGE_BYTES) { "Image is too large" } }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; success is signalled via outWidth/outHeight.
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        if (forcedSample == null) while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = forcedSample ?: sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            // Dark-mode screenshots: retry with inverted luminance.
            try { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text } catch (_: NotFoundException) { null }
        } finally { pixels.fill(0) }
    }
}
