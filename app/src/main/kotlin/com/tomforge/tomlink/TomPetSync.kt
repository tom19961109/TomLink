package com.tomforge.tomlink

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

internal class TomPetSync(context: Context) {
    private val state = context.getSharedPreferences("tompet_sync", Context.MODE_PRIVATE)
    val hasDevice get() = state.contains("secret")
    val code get() = state.getString("code", null)

    fun pair(): String {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        val device = request("/api/tomlink/pair", "POST", secret).getString("device")
        state.edit().putString("secret", secret).putString("code", device.takeLast(6).uppercase()).apply()
        return "$ORIGIN/tomlink/connect?device=$device"
    }

    fun email(): String? {
        val secret = state.getString("secret", null) ?: return null
        val result = request("/api/tomlink/device", "GET", secret)
        return if (result.isNull("email")) null else result.getString("email")
    }

    fun upload(store: StepStore, email: String): Int {
        val secret = state.getString("secret", null) ?: error("請先登入 Gmail。")
        val cursor = "after:$email"
        var after = state.getLong(cursor, 0)
        var count = 0
        val newest = store.lastId()
        while (after < newest) {
            val page = store.page(after, newest)
            if (page.records.length() == 0) break
            val uploaded = request("/api/tomlink/sync", "POST", secret, page.records.toString())
            check(uploaded.getInt("count") == page.records.length()) { "同步未完成，請再試一次。" }
            after = page.lastId
            count += page.records.length()
            state.edit().putLong(cursor, after).apply()
        }
        return count
    }

    fun disconnect() {
        val secret = state.getString("secret", null) ?: return
        request("/api/tomlink/device", "DELETE", secret)
        clearDevice()
    }

    private fun clearDevice() {
        state.edit().remove("secret").remove("code").apply()
    }

    private fun request(path: String, method: String, secret: String, body: String? = null): JSONObject {
        val connection = URL(ORIGIN + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $secret")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader(Charsets.UTF_8)?.use { JSONObject(it.readText()) } ?: JSONObject()
            if (status == 401 && path == "/api/tomlink/device") clearDevice()
            if (status !in 200..299) error(json.optString("detail", "同步服務暫時無法使用。"))
            return json
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ORIGIN = "https://tomforge.com"
    }
}
