package dev.otpbridge.protocol

import android.content.Context
import android.os.Build
import dev.otpbridge.data.DeviceStore
import dev.otpbridge.relay.RelayClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CancellationException

/** All state mutations are serialized. UI receives detached snapshots, never live JSON. */
class PhoneBridge(context: Context) {
    private val store = DeviceStore(context)
    private val crypto = SodiumCrypto()
    private var devices = store.load()
    private var operationToken: Long? = null
    private fun checkForeground() { operationToken?.let { RelayClient.checkToken(it) } }
    data class Pending(val channel: String, val body: JSONObject) {
        val key: String get() = "$channel:${body.getString("requestId")}"
    }
    private val requests = linkedMapOf<String, Pending>()
    init {
        internalDevices().forEach { d ->
            val saved = d.optJSONObject("pending") ?: JSONObject()
            saved.keys().forEach { id ->
                val body = saved.getJSONObject(id)
                if (body.optLong("expiresAt") > now()) {
                    val p = Pending(d.getString("channelId"), body)
                    requests[p.key] = p
                }
            }
        }
    }
    private fun internalDevices(): List<JSONObject> = (0 until devices.length()).map { devices.getJSONObject(it) }
    @Synchronized fun list(): List<JSONObject> = internalDevices().map { JSONObject(it.toString()) }
    @Synchronized fun pendingRequests(): List<Pending> = requests.values.map { Pending(it.channel, JSONObject(it.body.toString())) }
    @Synchronized fun isPending(reviewed: Pending): Boolean = requests[reviewed.key]?.body?.toString() == reviewed.body.toString() && reviewed.body.optLong("expiresAt") > now()
    private fun device(id: String) = internalDevices().firstOrNull { it.getString("channelId") == id } ?: error("Browser was revoked")
    private fun client(d: JSONObject) = RelayClient(d.getString("relay"), d.getString("channelId"), d.getString("phoneToken"))
    private fun randomId() = SodiumCrypto.encode(crypto.random(32))
    private fun now() = System.currentTimeMillis()
    private fun requireId(id: String) { require(SodiumCrypto.decode(id).size == 32) { "Invalid identifier" } }
    private fun session(d: JSONObject): SodiumCrypto.Session {
        val own = SodiumCrypto.Keys(SodiumCrypto.decode(d.getString("phonePublicKey")), SodiumCrypto.decode(d.getString("phonePrivateKey")))
        return try { crypto.server(own, SodiumCrypto.decode(d.getString("browserPublicKey"))) } finally { own.privateKey.fill(0) }
    }
    private fun strict(obj: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        StrictJson.fields(obj, required, optional)
    }
    private class PersistenceFailure(cause: Exception) : RuntimeException("Could not persist secure state", cause)
    private fun save() = store.save(devices)
    /** Roll back memory if a durable write fails: never flush an unpersisted decision. */
    private fun durable(change: () -> Unit) {
        val before = devices.toString()
        val pendingBefore = requests.mapValues { Pending(it.value.channel, JSONObject(it.value.body.toString())) }
        try { checkForeground(); change(); checkForeground(); save() } catch (e: Exception) {
            devices = JSONArray(before); requests.clear(); requests.putAll(pendingBefore); if (e is CancellationException) throw e; throw PersistenceFailure(e)
        }
    }
    private fun terminal(d: JSONObject, id: String) {
        val t = d.optJSONObject("terminal") ?: JSONObject().also { d.put("terminal", it) }
        t.put(id, now() + 390000)
        d.optJSONObject("pending")?.remove(id)
        requests.remove("${d.getString("channelId")}:$id")
    }
    private fun append(d: JSONObject, transport: JSONObject) {
        val outbox = d.optJSONArray("outbox") ?: JSONArray().also { d.put("outbox", it) }
        require(outbox.length() < 64) { "Too many undelivered responses" }
        outbox.put(transport)
    }
    private fun envelope(d: JSONObject, body: JSONObject): JSONObject {
        val id = randomId(); val expiry = body.getLong("expiresAt"); val channel = d.getString("channelId")
        val nonce = crypto.random(24); val keys = session(d); val plain = body.toString().toByteArray(Charsets.UTF_8)
        val ciphertext = try { crypto.encrypt(keys.transmit, nonce, plain, "otpbridge:session:1:$channel:$id:$expiry".toByteArray()) }
        finally { keys.receive.fill(0); keys.transmit.fill(0); plain.fill(0) }
        val e = JSONObject().put("kind", "session").put("v", 1).put("channelId", channel).put("id", id).put("expiresAt", expiry).put("nonce", SodiumCrypto.encode(nonce)).put("ciphertext", SodiumCrypto.encode(ciphertext))
        return JSONObject().put("id", id).put("expiresAt", expiry).put("payload", e.toString())
    }
    private fun flush(d: JSONObject) {
        while ((d.optJSONArray("outbox")?.length() ?: 0) > 0) {
            val outbox = d.getJSONArray("outbox"); val m = outbox.getJSONObject(0)
            checkForeground()
            if (m.getLong("expiresAt") > now()) client(d).post(m)
            durable { d.put("outbox", JSONArray((1 until outbox.length()).map { outbox.getJSONObject(it) })) }
        }
    }
    @Synchronized fun pair(qrText: String): JSONObject {
        operationToken = RelayClient.foregroundToken()
        require(qrText.length <= 4096) { "Pairing QR too large" }
        val qr = JSONObject(qrText)
        strict(qr, setOf("type", "v", "relay", "channelId", "phoneToken", "bootstrap", "browserPublicKey", "browserName", "expiresAt"))
        require(qr.getString("type") == "otpbridge.pair" && qr.getInt("v") == 1)
        val deadline = qr.getLong("expiresAt"); require(deadline > now() && deadline - now() <= 300000) { "Pairing QR expired" }
        listOf("channelId", "phoneToken", "bootstrap", "browserPublicKey").forEach { requireId(qr.getString(it)) }
        val browserName = qr.getString("browserName"); require(browserName.isNotBlank() && browserName.length <= 80)
        require(internalDevices().none { it.getString("channelId") == qr.getString("channelId") }) { "Browser already paired" }
        require(devices.length() < 20) { "Maximum paired browsers reached" }
        val keys = crypto.keypair(); val phoneName = Build.MODEL.take(80).ifBlank { "Android phone" }
        val response = JSONObject().put("type", "pair.response").put("v", 1).put("channelId", qr.getString("channelId")).put("browserPublicKey", qr.getString("browserPublicKey")).put("phonePublicKey", SodiumCrypto.encode(keys.publicKey)).put("browserName", browserName).put("phoneName", phoneName).put("expiresAt", deadline)
        val sas = PairTranscript.sas(qr.getString("channelId"), qr.getString("browserPublicKey"), SodiumCrypto.encode(keys.publicKey), browserName, phoneName, deadline)
        val nonce = crypto.random(24); val bootstrap = SodiumCrypto.decode(qr.getString("bootstrap"))
        val ciphertext = try { crypto.encrypt(bootstrap, nonce, response.toString().toByteArray(), "otpbridge:pair:1:${qr.getString("channelId")}".toByteArray()) } finally { bootstrap.fill(0) }
        val payload = JSONObject().put("kind", "pair").put("v", 1).put("nonce", SodiumCrypto.encode(nonce)).put("ciphertext", SodiumCrypto.encode(ciphertext))
        val d = JSONObject(qr.toString()).apply { remove("bootstrap"); put("phonePublicKey", SodiumCrypto.encode(keys.publicKey)); put("phonePrivateKey", SodiumCrypto.encode(keys.privateKey)); put("sas", sas); put("confirmed", false); put("active", false); put("cursor", 0); put("seen", JSONObject()); put("origins", JSONObject()) }
        keys.privateKey.fill(0)
        client(d) // Validate HTTPS relay before persisting trust material.
        durable { devices.put(d); append(d, JSONObject().put("id", randomId()).put("expiresAt", deadline).put("payload", payload.toString())) }
        // Network failure leaves a durable exact pairing response for foreground retry.
        runCatching { flush(d) }
        return JSONObject(d.toString())
    }
    @Synchronized fun confirm(channel: String) {
        operationToken = RelayClient.foregroundToken()
        val d = device(channel); require(d.getLong("expiresAt") > now()) { "Pairing expired" }
        if (!d.optBoolean("confirmed")) {
            val time = now()
            val body = JSONObject().put("type", "pair.confirm").put("requestId", channel).put("createdAt", time).put("expiresAt", minOf(d.getLong("expiresAt"), time + 90000))
            val outbound = envelope(d, body)
            durable { d.put("confirmed", true); append(d, outbound) }
        }
        flush(d)
    }
    @Synchronized fun revoke(channel: String) {
        operationToken = RelayClient.foregroundToken()
        val d = device(channel)
        durable { devices = JSONArray(internalDevices().filter { it.getString("channelId") != channel }); requests.entries.removeAll { it.value.channel == channel } }
        runCatching { client(d).revoke() }
    }
    private fun origin(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host != null && u.userInfo == null && u.rawQuery == null && u.fragment == null && u.path.isNullOrEmpty() && u.toASCIIString() == value && u.host == u.host.lowercase() && u.port != 443
    }.getOrDefault(false)
    @Synchronized fun refresh(allowed: () -> Boolean = { true }): List<String> {
        operationToken = RelayClient.foregroundToken()
        val errors = mutableListOf<String>()
        requests.entries.removeAll { it.value.body.getLong("expiresAt") <= now() }
        for (d in internalDevices().toList()) {
            if (!allowed()) break
            try {
                if (!d.optBoolean("active") && d.getLong("expiresAt") <= now()) continue
                try { flush(d) } catch (e: PersistenceFailure) { throw e } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.add("${d.getString("browserName")}: delivery retry pending") }
                if (!allowed()) break
                val after = d.optLong("cursor"); val result = client(d).fetch(after)
                strict(result, setOf("messages", "cursor"))
                val cursor = result.getLong("cursor"); require(cursor >= after && cursor <= 9007199254740991L) { "Invalid cursor" }
                val messages = result.getJSONArray("messages"); require(messages.length() <= 64)
                var previous = after; val ids = mutableSetOf<String>()
                for (i in 0 until messages.length()) {
                    val t = messages.getJSONObject(i); strict(t, setOf("sequence", "id", "expiresAt", "payload"))
                    val sequence = t.getLong("sequence"); require(sequence > previous && sequence <= cursor && ids.add(t.getString("id"))) { "Invalid message order" }; previous = sequence
                    require(t.getString("payload").toByteArray().size <= 16384) { "Oversized envelope" }
                }
                for (i in 0 until messages.length()) {
                    try { receive(d, messages.getJSONObject(i)) } catch (e: PersistenceFailure) { throw e } catch (e: CancellationException) { throw e } catch (_: Exception) { errors.add("${d.getString("browserName")}: rejected invalid or expired message") }
                }
                if (internalDevices().any { it === d }) durable { d.put("cursor", cursor) }
            } catch (e: PersistenceFailure) { errors.add("Secure state could not be saved; refresh stopped. Retry after resolving device storage."); break }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add("${d.getString("browserName")}: ${e.message}") }
        }
        return errors
    }
    private fun receive(d: JSONObject, t: JSONObject) {
        val time = now(); val e = JSONObject(t.getString("payload")); val channel = d.getString("channelId"); val id = e.getString("id"); requireId(id)
        strict(e, setOf("kind", "v", "channelId", "id", "expiresAt", "nonce", "ciphertext"))
        require(e.getString("kind") == "session" && e.getInt("v") == 1 && e.getString("channelId") == channel && id == t.getString("id") && e.getLong("expiresAt") == t.getLong("expiresAt")) { "Envelope mismatch" }
        val expiry = e.getLong("expiresAt"); require(expiry > time && expiry <= time + 300000) { "Expired message" }
        val seen = d.getJSONObject("seen"); seen.keys().asSequence().toList().forEach { if (seen.getLong(it) < time) seen.remove(it) }
        if (seen.has(id)) return
        val keys = session(d)
        val plain = try { crypto.decrypt(keys.receive, SodiumCrypto.decode(e.getString("nonce")), SodiumCrypto.decode(e.getString("ciphertext")), "otpbridge:session:1:$channel:$id:$expiry".toByteArray()) } finally { keys.receive.fill(0); keys.transmit.fill(0) }
        val body = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        val type = body.getString("type"); val created = body.getLong("createdAt"); val deadline = body.getLong("expiresAt")
        require(created >= 0 && created <= time + 30000 && deadline > time && deadline == expiry && deadline > created && deadline - created <= if (type.startsWith("pair.")) 300000 else 90000) { "Invalid deadline" }
        val requestId = body.getString("requestId"); requireId(requestId)
        val common = setOf("type", "requestId", "createdAt", "expiresAt")
        when (type) {
            "pair.ready" -> {
                strict(body, common)
                require(!d.getBoolean("active") && d.getBoolean("confirmed") && requestId == channel && d.getLong("expiresAt") > time)
                durable { seen.put(id, expiry + 300000); d.put("active", true) }
            }
            "fill.request" -> {
                strict(body, common + setOf("browserName", "origin", "frameOrigin"), setOf("accountRef", "context"))
                require(d.getBoolean("active") && body.getString("browserName") == d.getString("browserName"))
                val site = body.getString("origin"); require(site.length <= 2048 && origin(site) && site == body.getString("frameOrigin"))
                val ref = body.optString("accountRef", ""); if (body.has("accountRef")) requireId(ref)
                val terminals = d.optJSONObject("terminal") ?: JSONObject().also { d.put("terminal", it) }
                terminals.keys().asSequence().toList().forEach { if (terminals.getLong(it) < time) terminals.remove(it) }
                val pending = Pending(channel, body)
                if (terminals.has(requestId) || requests.containsKey(pending.key)) { durable { seen.put(id, expiry + 300000) }; return }
                if (ref.isNotEmpty() && d.getJSONObject("origins").optString(site) != ref) {
                    val response = denial(body, "account-mismatch")
                    val outbound = envelope(d, response)
                    durable { seen.put(id, expiry + 300000); terminal(d, requestId); append(d, outbound) }
                    return
                }
                require(requests.values.count { it.channel == channel } < 16) { "Too many pending requests" }
                durable { seen.put(id, expiry + 300000); requests[pending.key] = pending; val saved = d.optJSONObject("pending") ?: JSONObject().also { d.put("pending", it) }; saved.put(requestId, body) }
            }
            "request.cancelled" -> { strict(body, common); require(d.getBoolean("active")); durable { seen.put(id, expiry + 300000); terminal(d, requestId) } }
            "device.revoked" -> { strict(body, common); durable { devices = JSONArray(internalDevices().filter { it.getString("channelId") != channel }); requests.entries.removeAll { it.value.channel == channel } } }
            else -> error("Unsupported message type")
        }
    }
    private fun current(reviewed: Pending): Pair<JSONObject, JSONObject> {
        val pending = requests[reviewed.key] ?: error("Request no longer pending")
        require(pending.channel == reviewed.channel && pending.body.toString() == reviewed.body.toString()) { "Reviewed request changed" }
        val d = device(reviewed.channel)
        require(d.optBoolean("active") && pending.body.getLong("expiresAt") > now()) { "Request expired or browser revoked" }
        return d to pending.body
    }
    private fun denial(request: JSONObject, reason: String): JSONObject = JSONObject().put("type", "fill.denied").put("requestId", request.getString("requestId")).put("createdAt", now()).put("expiresAt", request.getLong("expiresAt")).put("reason", reason)
    @Synchronized fun deny(reviewed: Pending) {
        operationToken = RelayClient.foregroundToken()
        val (d, body) = current(reviewed); val outbound = envelope(d, denial(body, "denied"))
        durable { terminal(d, body.getString("requestId")); append(d, outbound) }
        flush(d)
    }
    @Synchronized fun approve(reviewed: Pending, accountRef: String, otp: String, validFrom: Long, validUntil: Long) {
        operationToken = RelayClient.foregroundToken()
        val (d, body) = current(reviewed); requireId(accountRef)
        val time = now(); require(otp.matches(Regex("(?:[0-9]{6}|[0-9]{8})")) && validFrom <= time && validUntil - time >= 2000 && validUntil > validFrom) { "Code expired" }
        val site = body.getString("origin"); val requestedRef = body.optString("accountRef", "")
        require(requestedRef.isEmpty() || (requestedRef == accountRef && d.getJSONObject("origins").optString(site) == accountRef)) { "Account binding changed; request again" }
        val response = JSONObject().put("type", "fill.approved").put("requestId", body.getString("requestId")).put("createdAt", time).put("expiresAt", minOf(body.getLong("expiresAt"), validUntil)).put("origin", site).put("frameOrigin", site).put("accountRef", accountRef).put("otp", otp).put("validFrom", validFrom).put("validUntil", validUntil)
        val outbound = envelope(d, response)
        durable { d.getJSONObject("origins").put(site, accountRef); terminal(d, body.getString("requestId")); append(d, outbound) }
        flush(d)
    }
}
