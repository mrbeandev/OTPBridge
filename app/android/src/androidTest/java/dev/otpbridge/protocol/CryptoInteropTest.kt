package dev.otpbridge.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class CryptoInteropTest {
    @Test fun typescriptGoldenVectors() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("protocol-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        val crypto = SodiumCrypto()
        val phone = fixture.getJSONObject("phone"); val browser = fixture.getJSONObject("browser")
        val keys = crypto.server(SodiumCrypto.Keys(SodiumCrypto.decode(phone.getString("publicKey")), SodiumCrypto.decode(phone.getString("privateKey"))), SodiumCrypto.decode(browser.getString("publicKey")))
        assertEquals(fixture.getJSONObject("phoneKeys").getString("rx"), SodiumCrypto.encode(keys.receive))
        assertEquals(fixture.getJSONObject("phoneKeys").getString("tx"), SodiumCrypto.encode(keys.transmit))
        val qr = fixture.getJSONObject("qr"); val pair = fixture.getJSONObject("pairEnvelope")
        val pairPlain = crypto.decrypt(SodiumCrypto.decode(qr.getString("bootstrap")), SodiumCrypto.decode(pair.getString("nonce")), SodiumCrypto.decode(pair.getString("ciphertext")), fixture.getString("pairAad").toByteArray())
        val response = JSONObject(String(pairPlain, Charsets.UTF_8)); assertEquals(phone.getString("publicKey"), response.getString("phonePublicKey"))
        val transcript = JSONArray().put(1).put(response.getString("channelId")).put(response.getString("browserPublicKey")).put(response.getString("phonePublicKey")).put(response.getString("browserName")).put(response.getString("phoneName")).put(response.getLong("expiresAt")).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
        val sas = (((hash[0].toInt() and 255) shl 16) or ((hash[1].toInt() and 255) shl 8) or (hash[2].toInt() and 255)).toString().padStart(8, '0')
        assertEquals(fixture.getString("sas"), sas)
        val envelope = JSONObject(fixture.getJSONObject("transport").getString("payload"))
        val plain = crypto.decrypt(keys.receive, SodiumCrypto.decode(envelope.getString("nonce")), SodiumCrypto.decode(envelope.getString("ciphertext")), fixture.getString("sessionAad").toByteArray())
        assertEquals(fixture.getJSONObject("request").toString(), JSONObject(String(plain, Charsets.UTF_8)).toString())
        val nonce = crypto.random(24); val message = "phone reply".toByteArray(); val aad = "test".toByteArray()
        val encrypted = crypto.encrypt(keys.transmit, nonce, message, aad)
        assertArrayEquals(message, crypto.decrypt(keys.transmit, nonce, encrypted, aad))
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        try { crypto.decrypt(keys.transmit, nonce, encrypted, aad); fail("Tampering accepted") } catch (_: IllegalStateException) { }
    }
}
