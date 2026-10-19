package dev.otpbridge.totp

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic secrets only; temp images in the app cache, deleted after use, never the user's gallery. */
@RunWith(AndroidJUnit4::class)
class QrImageImportTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "qr-import-test").apply { mkdirs() }
    private fun varint(v: Long): ByteArray { val o = java.io.ByteArrayOutputStream(); var x = v; while (true) { if (x and 0x7f.inv().toLong() == 0L) { o.write(x.toInt()); break }; o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; return o.toByteArray() }
    private fun f(n: Int, b: ByteArray) = varint(((n shl 3) or 2).toLong()) + varint(b.size.toLong()) + b
    private fun num(n: Int, v: Int) = varint((n shl 3).toLong()) + varint(v.toLong())
    private fun part(i: Int, total: Int): String {
        val otp = f(1, f(1, "12345678901234567890".toByteArray()) + f(2, "user$i@example.test".toByteArray()) + f(3, "Synthetic$i".toByteArray()) + num(4, 1) + num(5, 1) + num(6, 2))
        val payload = otp + num(3, total) + num(4, i) + num(5, 4242)
        return "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(payload), "UTF-8")
    }
    private fun png(text: String, name: String, dark: Boolean = false): Uri {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 900, 900)
        // Pad into a phone-screenshot-sized canvas, like a real Google Authenticator export screen.
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val bg = if (dark) Color.rgb(32, 33, 36) else Color.WHITE; val fg = if (dark) Color.WHITE else Color.BLACK
        bmp.eraseColor(bg)
        for (y in 0 until 900) for (x in 0 until 900) if (m[x, y]) bmp.setPixel(90 + x, 600 + y, fg)
        val file = File(dir, name); file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
        return Uri.fromFile(file)
    }
    @Test fun decodesScreenshotsAndOrdersPartsRegardlessOfPickOrder() {
        val picks = listOf(png(part(2, 3), "p2.png"), png(part(0, 3), "p0.png", dark = true), png(part(1, 3), "p1.png"))
        val texts = picks.map { QrImageDecoder.decode(ctx.contentResolver, it) }
        assertTrue("decoded (p2 light, p0 dark, p1 light): ${texts.map { it != null }}", texts.all { it != null })
        val ordered = AuthenticatorImport.orderByPart(texts.filterNotNull())
        val labels = ordered.flatMap { AuthenticatorImport.parseText(it).entries.map { e -> e.account.issuer } }
        assertEquals(listOf("Synthetic0", "Synthetic1", "Synthetic2"), labels)
        assertEquals("287082", Totp.generate(AuthenticatorImport.parseText(ordered[0]).entries[0].account, 59))
        picks.forEach { File(it.path!!).delete() }
    }
    @Test fun imageWithoutQrReturnsNull() {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val file = File(dir, "blank.png"); file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNull(QrImageDecoder.decode(ctx.contentResolver, Uri.fromFile(file))); file.delete()
    }
}
