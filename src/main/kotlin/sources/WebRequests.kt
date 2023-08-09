package sources

import java.io.BufferedReader
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object WebRequests {

    class Response(val status: Int, val value: String)

    fun post(con: HttpsURLConnection, data: ByteArray): HttpsURLConnection {
        con.requestMethod = "POST"
        con.doOutput = true
        con.outputStream.write(data)
        return con
    }

    fun createConnection(
        url: String,
        method: String = "GET",
        headers: Map<String, String>? = null
    ): HttpsURLConnection {
        val con = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException()
        con.instanceFollowRedirects = false
        con.connectTimeout = 30 * 1000 // 30s
        con.readTimeout = 30 * 1000 // 30s
        con.requestMethod = method
        headers?.forEach {(k, v) -> con.setRequestProperty(k, v)}
        return con
    }

    @Throws(HttpException::class)
    fun request(con: HttpsURLConnection): Response {
        if (con.responseCode < 400) {
            return Response(con.responseCode, con.inputStream.bufferedReader().use(BufferedReader::readText))
        } else {
            throw HttpException(con.responseCode, con.errorStream.bufferedReader().use(BufferedReader::readText))
        }
    }

    class HttpException(val code: Int, message: String?) : IOException(message)
}