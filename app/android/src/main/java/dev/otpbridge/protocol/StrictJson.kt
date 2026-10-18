package dev.otpbridge.protocol

import org.json.JSONObject

/** org.json accessors coerce strings/numbers; wire v1 explicitly does not. */
object StrictJson {
    fun fields(obj: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = obj.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "Invalid message fields" }
        keys.forEach { key ->
            val value = obj.get(key)
            if (key in setOf("v", "expiresAt", "createdAt", "sequence", "cursor", "validFrom", "validUntil")) {
                require(value is Int || value is Long) { "Integer required: $key" }
                val number = (value as Number).toLong()
                require(number in 0..9007199254740991L) { "Invalid integer: $key" }
            } else if (key != "messages") {
                require(value is String) { "String required: $key" }
                require(value.length <= 22000 && validUnicode(value)) { "Invalid string: $key" }
            }
        }
    }
    fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
            } else { if (Character.isLowSurrogate(c)) return false; i++ }
        }
        return true
    }
}
