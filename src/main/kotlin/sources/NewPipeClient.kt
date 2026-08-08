package sources

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization

internal object NewPipeClient {
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) {
            return
        }

        synchronized(this) {
            if (!initialized) {
                NewPipe.init(HttpDownloader(), Localization.DEFAULT)
                initialized = true
            }
        }
    }

    private class HttpDownloader : org.schabi.newpipe.extractor.downloader.Downloader() {
        override fun execute(request: Request): Response {
            val headers = mutableMapOf("User-Agent" to USER_AGENT)
            request.headers().forEach { (name, values) ->
                values.firstOrNull()?.let { value -> headers[name] = value }
            }

            val connection = WebRequests.createConnection(
                request.url(),
                request.httpMethod(),
                headers
            )

            if (request.httpMethod() == "POST") {
                WebRequests.post(connection, request.dataToSend() as ByteArray)
            }

            val response = WebRequests.requestAllowErrors(connection)
            return Response(
                response.status,
                connection.responseMessage.orEmpty(),
                connection.headerFields,
                response.value,
                request.url()
            )
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"
}
