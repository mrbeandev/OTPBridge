package dev.otpbridge

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local-only diagnostics ring log in app-private storage. Nothing is uploaded.
 * Every value is redacted when written AND again when a report is exported:
 * no OTP codes, secrets, keys, tokens, payloads, IPs, coordinates, emails,
 * account names or full URLs can reach the file.
 */
object Telemetry {
    private const val MAX_LINES = 800
    private var file: File? = null
    private val forbiddenKey = Regex("(?i)otp|code|secret|seed|token|key|payload|cipher|nonce|lat|lon|ip$|ip_|email|account|label|issuer|uri|bootstrap")
    fun init(context: Context) { file = File(context.filesDir, "telemetry.jsonl") }

    /** Pattern-based scrubbing for free text such as exception messages. */
    fun redact(value: String): String = value.take(300)
        .replace(Regex("otpauth://\\S+", RegexOption.IGNORE_CASE), "[otpauth-uri]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "[ip]")
        .replace(Regex("(?i)\\b[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,7}\\b"), "[ip]")
        .replace(Regex("-?\\d{1,3}\\.\\d{3,},\\s*-?\\d{1,3}\\.\\d{3,}"), "[coords]")
        .replace(Regex("[A-Za-z0-9_-]{32,}"), "[redacted-id]")
        .replace(Regex("\\b[A-Z2-7]{16,}=*\\b"), "[secret]")
        .replace(Regex("(?<![\\d.])\\d{6,8}(?![\\d.])"), "[digits]")
        .replace(Regex("(https?://[^\\s/?#]+)[/?#]\\S*"), "$1/[path]")

    @Synchronized fun track(ev: String, vararg data: Pair<String, Any?>) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("ev", ev.take(60))
        for ((k, v) in data) {
            if (forbiddenKey.containsMatchIn(k) && k != "req" && k != "msg") continue
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Number, is Boolean -> o.put(k, v)
                else -> o.put(k, redact(v.toString()))
            }
        }
        Log.i("OTPBridge", o.toString())
        val f = file ?: return
        runCatching {
            f.appendText(o.toString() + "\n")
            if (f.length() > 256_000) f.writeText(f.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized fun lines(): List<String> = runCatching { file?.readLines()?.filter { it.isNotBlank() } ?: emptyList() }.getOrDefault(emptyList())
    data class Summary(val events: Int, val first: Long?, val last: Long?, val errors: Int, val bytes: Long)
    @Synchronized fun summary(): Summary {
        val ls = lines(); val times = ls.mapNotNull { runCatching { JSONObject(it).getLong("t") }.getOrNull() }
        return Summary(ls.size, times.minOrNull(), times.maxOrNull(), ls.count { it.contains("error", true) || it.contains("rejected", true) || it.contains("failed", true) }, file?.length() ?: 0)
    }
    @Synchronized fun clear() { runCatching { file?.writeText("") } }
    /** 8-char prefix of a random request/message ID, for correlation only. */
    fun short(id: String?): String? = id?.take(8)

    /** Writes a shareable plain-text report into cache/diagnostics and returns it. */
    @Synchronized fun exportReport(context: Context, versionName: String): File {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "otpbridge-android-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        val s = summary()
        out.bufferedWriter().use { w ->
            w.appendLine("OTPBridge Android diagnostics report")
            w.appendLine("Generated: ${iso.format(Date())}")
            w.appendLine("App: $versionName  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  Device: ${redact(Build.MANUFACTURER)} ${redact(Build.MODEL)}")
            w.appendLine("Events: ${s.events}  Errors/rejections: ${s.errors}")
            w.appendLine()
            w.appendLine("PRIVACY: This file never contains OTP codes, TOTP secrets, keys, relay tokens,")
            w.appendLine("encrypted payloads, IP addresses, coordinates, emails or account names.")
            w.appendLine("Values are redacted when recorded and again when exported. Review before sharing.")
            w.appendLine("-".repeat(78))
            for (line in lines()) {
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val t = o.optLong("t"); val ev = o.optString("ev")
                val rest = o.keys().asSequence().filter { it != "t" && it != "ev" }.joinToString(" ") { k -> "$k=${redact(o.opt(k).toString())}" }
                w.appendLine("${iso.format(Date(t))}  ${ev.padEnd(26)} $rest")
            }
        }
        return out
    }
}
