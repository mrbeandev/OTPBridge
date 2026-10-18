package dev.otpbridge.protocol

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.security.SecureRandom

/** Raw byte API only: libsodium crypto_kx server role = phone; client role = browser. */
class SodiumCrypto {
    private val sodium = LazySodiumAndroid(SodiumAndroid())
    data class Keys(val publicKey: ByteArray, val privateKey: ByteArray)
    data class Session(val receive: ByteArray, val transmit: ByteArray)
    fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    fun keypair(): Keys {
        val publicKey = ByteArray(32); val privateKey = ByteArray(32)
        check(sodium.cryptoKxKeypair(publicKey, privateKey)) { "Key generation failed" }
        return Keys(publicKey, privateKey)
    }
    fun server(keys: Keys, browserPublicKey: ByteArray): Session {
        require(keys.publicKey.size == 32 && keys.privateKey.size == 32 && browserPublicKey.size == 32)
        val receive = ByteArray(32); val transmit = ByteArray(32)
        check(sodium.cryptoKxServerSessionKeys(receive, transmit, keys.publicKey, keys.privateKey, browserPublicKey)) { "Peer key rejected" }
        return Session(receive, transmit)
    }
    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 24)
        val result = ByteArray(plain.size + 16); val length = LongArray(1)
        check(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(result, length, plain, plain.size.toLong(), aad, aad.size.toLong(), null, nonce, key)) { "Encryption failed" }
        return result.copyOf(length[0].toInt())
    }
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 24 && ciphertext.size in 16..65536)
        val result = ByteArray(ciphertext.size - 16); val length = LongArray(1)
        check(sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(result, length, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key)) { "Encrypted message authentication failed" }
        return result.copyOf(length[0].toInt())
    }
    companion object {
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun decode(text: String): ByteArray {
            require(text.length <= 22000 && text.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid binary encoding" }
            val decoded = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            require(encode(decoded) == text) { "Noncanonical binary encoding" }
            return decoded
        }
    }
}
