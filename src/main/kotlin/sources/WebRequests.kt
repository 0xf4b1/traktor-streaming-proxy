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
        val response = requestAllowErrors(con)
        if (response.status >= 400) {
            throw HttpException(response.status, response.value)
        }
        return response
    }

    fun requestAllowErrors(con: HttpsURLConnection): Response {
        val status = con.responseCode
        val stream = if (status < 400) con.inputStream else con.errorStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        return Response(status, body)
    }

    class HttpException(val code: Int, message: String?) : IOException(message)
}
