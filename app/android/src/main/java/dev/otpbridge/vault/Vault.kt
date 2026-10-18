package dev.otpbridge.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.otpbridge.totp.Totp
import dev.otpbridge.totp.TotpAccount
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

data class AccountRecord(val id: String, val account: TotpAccount)

class Vault(private val context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val alias = "otpbridge.vault.biometric.v1"
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun un64(value: String) = Base64.decode(value, Base64.NO_WRAP)
    val initialized get() = prefs.contains("wrapped")

    fun biometricStatus(): Int = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        require(!initialized) { "Vault key unavailable. Existing vault cannot be recovered; use your service recovery codes." }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }

    private var prompt: BiometricPrompt? = null
    private var generation = 0L
    fun cancel() { generation++; prompt?.cancelAuthentication(); prompt = null }
    fun unlock(activity: FragmentActivity, reason: String, onError: (String) -> Unit, action: (ByteArray) -> Unit) {
        if (prompt != null) { onError("A biometric operation is already in progress"); return }
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { onError("Open the app before unlocking"); return }
        val operation = ++generation
        if (biometricStatus() != BiometricManager.BIOMETRIC_SUCCESS) { onError("Strong biometrics must be enrolled to use this vault. No PIN fallback is allowed."); return }
        try {
            val creating = !initialized
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (creating) cipher.init(Cipher.ENCRYPT_MODE, key())
            else cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, un64(prefs.getString("wrapIv", "")!!)))
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, message: CharSequence) { if (operation == generation) { prompt = null; onError(message.toString()) } }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (operation != generation || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                    prompt = null
                    var dataKey: ByteArray? = null
                    try {
                        val authenticated = result.cryptoObject?.cipher ?: error("Missing authenticated cipher")
                        if (creating) {
                            dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                            val wrapped = authenticated.doFinal(dataKey)
                            check(prefs.edit().putString("wrapped", b64(wrapped)).putString("wrapIv", b64(authenticated.iv)).commit()) { "Could not save vault" }
                        } else dataKey = authenticated.doFinal(un64(prefs.getString("wrapped", "")!!))
                        action(dataKey!!)
                    } catch (e: Exception) { onError(e.message ?: "Vault operation failed") }
                    finally { dataKey?.fill(0) }
                }
            }
            val activePrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
            prompt = activePrompt
            activePrompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock OTPBridge").setSubtitle(reason).setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build(), BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) { prompt = null; onError("Vault access failed: ${e.message}. Biometric changes may invalidate this vault. Keep your service recovery codes.") }
    }

    fun accounts(key: ByteArray): List<AccountRecord> {
        val stored = prefs.getString("accounts", null) ?: return emptyList()
        val record = JSONObject(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, un64(record.getString("iv"))))
        val plain = cipher.doFinal(un64(record.getString("data")))
        return try {
            val array = JSONArray(String(plain, Charsets.UTF_8))
            (0 until array.length()).map { index -> val obj = array.getJSONObject(index); AccountRecord(obj.getString("id"), Totp.validate(TotpAccount(obj.getString("label"), obj.getString("issuer"), obj.getString("secret"), obj.getString("algorithm"), obj.getInt("digits"), obj.getInt("period")))) }
        } finally { plain.fill(0) }
    }
    fun add(key: ByteArray, account: TotpAccount) = save(key, accounts(key) + AccountRecord(Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Totp.validate(account)))
    fun save(key: ByteArray, records: List<AccountRecord>) {
        val array = JSONArray()
        records.forEach { (id, a) -> array.put(JSONObject().put("id", id).put("label", a.label).put("issuer", a.issuer).put("secret", a.secret).put("algorithm", a.algorithm).put("digits", a.digits).put("period", a.period)) }
        val plain = array.toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            val encrypted = JSONObject().put("iv", b64(cipher.iv)).put("data", b64(cipher.doFinal(plain))).toString()
            check(prefs.edit().putString("accounts", encrypted).commit()) { "Could not save accounts" }
        } finally { plain.fill(0) }
    }
}
