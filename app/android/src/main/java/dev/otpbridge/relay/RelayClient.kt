package dev.otpbridge.relay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException

class RelayClient(private val relay: String, private val channel: String, private val token: String) {
    companion object {
        private val gate = Any()
        private var epoch = 0L
        private var foreground = false
        private val active = mutableSetOf<HttpURLConnection>()
        fun resumeForeground() = synchronized(gate) { foreground = true }
        fun cancelAll() {
            val closing = synchronized(gate) { foreground = false; epoch++; active.toList().also { active.clear() } }
            closing.forEach { it.disconnect() }
        }
        fun foregroundToken(): Long = synchronized(gate) { if (!foreground) throw CancellationException("App is not foreground"); epoch }
        fun checkToken(token: Long) = synchronized(gate) { if (!foreground || token != epoch) throw CancellationException("Foreground operation cancelled") }
    }
    init {
        val uri = URI(relay)
        require(uri.host != null && uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "Relay must be a plain HTTPS origin" }
        require(channel.matches(Regex("[A-Za-z0-9_-]{43}")) && token.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    fun request(method: String, path: String = "", body: JSONObject? = null): JSONObject {
        val connection = URI("${relay.trimEnd('/')}/v1/channels/$channel$path").toURL().openConnection() as HttpURLConnection
        val started = synchronized(gate) {
            if (!foreground) throw CancellationException("Open the app to connect to the relay")
            active.add(connection); epoch
        }
        connection.requestMethod = method; connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token"); connection.setRequestProperty("Accept", "application/json")
        try {
            checkToken(started)
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { checkToken(started); it.write(body.toString().toByteArray()) }
            }
            checkToken(started)
            val code = connection.responseCode
            checkToken(started)
            if (code == 409 && method == "POST") return JSONObject().put("alreadyDelivered", true)
            require(code in 200..299) { "Relay returned HTTP $code" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { checkToken(started); val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 2200000) { "Relay response too large" }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            checkToken(started)
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } finally { synchronized(gate) { active.remove(connection) }; connection.disconnect() }
    }
    fun post(message: JSONObject) = request("POST", "/messages", message)
    fun fetch(cursor: Long) = request("GET", "/messages?after=$cursor")
    fun revoke() = request("DELETE")
}
