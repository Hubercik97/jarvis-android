package pl.zamirski.jarvis.net

import java.net.HttpURLConnection
import java.net.URL

/** Proste zapytanie GET (wywoływać z wątku tła). Zwraca treść albo rzuca IOException. */
object Http {
    fun get(url: String, timeoutMs: Int = 15_000): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
