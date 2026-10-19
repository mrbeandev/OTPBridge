package dev.otpbridge.vault

import org.json.JSONArray
import org.json.JSONObject

/** Display-only metadata. Never contains a seed, current code or vault key. */
data class AccountSummary(val id: String, val label: String, val issuer: String, val algorithm: String, val digits: Int, val period: Int)

object AccountSummaryCodec {
    fun encode(records: List<AccountSummary>): String = JSONArray().apply {
        records.forEach { a -> put(JSONObject().put("id", a.id).put("label", a.label).put("issuer", a.issuer).put("algorithm", a.algorithm).put("digits", a.digits).put("period", a.period)) }
    }.toString()
    fun decode(value: String): List<AccountSummary> {
        val array = JSONArray(value)
        return (0 until array.length()).map { i -> val a = array.getJSONObject(i); AccountSummary(a.getString("id"), a.getString("label"), a.getString("issuer"), a.getString("algorithm"), a.getInt("digits"), a.getInt("period")) }
    }
}
