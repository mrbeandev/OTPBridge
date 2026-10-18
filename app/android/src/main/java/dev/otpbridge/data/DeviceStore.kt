package dev.otpbridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing transport secrets are device-encrypted, independent of biometric vault access. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("otpbridge.transport.v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("otpbridge.transport.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    fun load(): JSONArray {
        val record = prefs.getString("data", null) ?: return JSONArray()
        val obj = JSONObject(record)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(obj.getString("iv"), Base64.NO_WRAP))) }
        val bytes = cipher.doFinal(Base64.decode(obj.getString("ciphertext"), Base64.NO_WRAP))
        return try { JSONArray(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
    }
    fun save(devices: JSONArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = devices.toString().toByteArray()
        try {
            val obj = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("ciphertext", Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP))
            check(prefs.edit().putString("data", obj.toString()).commit()) { "Could not save paired browsers" }
        } finally { bytes.fill(0) }
    }
}
