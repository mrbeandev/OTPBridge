package dev.otpbridge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Local-only diagnostics ring log in app-private storage. Never records codes,
 * seeds, keys, tokens, payloads, IPs or coordinates. Nothing is uploaded.
 */
object Telemetry {
    private const val MAX_LINES = 800
    private var file: File? = null
    private val forbidden = Regex("(?i)otp|secret|token|key|payload|ciphertext|nonce|latitude|longitude|ip$")
    fun init(context: Context) { file = File(context.filesDir, "telemetry.jsonl") }
    @Synchronized fun track(ev: String, vararg data: Pair<String, Any?>) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("ev", ev)
        for ((k, v) in data) if (!forbidden.containsMatchIn(k)) when (v) {
            null -> o.put(k, JSONObject.NULL)
            is Number, is Boolean -> o.put(k, v)
            else -> o.put(k, v.toString().take(300))
        }
        Log.i("OTPBridge", o.toString())
        val f = file ?: return
        runCatching {
            f.appendText(o.toString() + "\n")
            if (f.length() > 256_000) { val lines = f.readLines(); f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n")) }
        }
    }
    @Synchronized fun read(): String = runCatching { file?.readText() ?: "" }.getOrDefault("")
    @Synchronized fun clear() { runCatching { file?.writeText("") } }
    fun short(id: String?): String? = id?.take(8)
}
