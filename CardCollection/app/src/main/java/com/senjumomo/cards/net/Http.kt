package com.senjumomo.cards.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    class HttpException(val code: Int, message: String) : IOException(message)

    suspend fun get(url: String): String = request(url, null)

    suspend fun post(url: String, json: String): String = request(url, json)

    private suspend fun request(url: String, json: String?): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "CardBinder/2.0 (Android)")
            // Headers must all be set before the body is written.
            if (json != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw HttpException(code, "HTTP $code from ${URL(url).host}")
            body
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
