package sources

import beatport.api.*
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

class Youtube : ISource {

    private val next = HashMap<String, Page?>()

    override val name: String
        get() = "YouTube"

    init {
        NewPipe.init(Downloader(), Localization.DEFAULT)
    }

    override fun getGenre(): List<Track> {
        return getTrending()
    }

    override fun query(query: String): List<Track> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        val itemsPage = if (next.containsKey(query) && next[query] != null) {
            extractor.getPage(next[query])
        } else {
            extractor.fetchPage()
            extractor.initialPage
        }
        next[query] = itemsPage.nextPage
        return extractItems(itemsPage.items)
    }

    override fun download(id: String): ByteArray {
        return downloadTrack(getAudioStream(id))
    }

    private fun downloadTrack(path: String): ByteArray {
        val con = URL(path).openConnection()
        con.setRequestProperty("range", "bytes=0-")
        return con.inputStream.readBytes()
    }

    private fun getTrending(): List<Track> {
        val extractor = ServiceList.YouTube.kioskList.getExtractorById("Trending", null)
        extractor.fetchPage()
        return extractItems(extractor.initialPage.items)
    }

    private fun extractItems(items: List<InfoItem>): List<Track> {
        val results = LinkedList<Track>()
        for (item in items) {
            if (item is StreamInfoItem) {
                results.add(Track(item.url.substring(item.url.indexOf('=') + 1), listOf(Artist(1, item.uploaderName)), item.name))
            }
        }
        return results
    }

    private fun getAudioStream(url: String): String {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$url")
        extractor.fetchPage()
        for (stream in extractor.audioStreams) {
            if (stream.format!!.getName() == "m4a") {
                return stream.content.replace("signature", "sig")
            }
        }
        throw IllegalStateException("no audio stream")
    }

    class Downloader : org.schabi.newpipe.extractor.downloader.Downloader() {

        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"
        private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val httpMethod = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()

            var requestBody: RequestBody? = null
            if (dataToSend != null) {
                requestBody = RequestBody.create(null, dataToSend)
            }

            val requestBuilder = okhttp3.Request.Builder()
                .method(httpMethod, requestBody).url(url)
                .addHeader("User-Agent", USER_AGENT)

            for ((headerName, headerValueList) in headers) {
                if (headerValueList.size > 1) {
                    requestBuilder.removeHeader(headerName)
                    for (headerValue in headerValueList) {
                        requestBuilder.addHeader(headerName, headerValue)
                    }
                } else if (headerValueList.size == 1) {
                    requestBuilder.header(headerName, headerValueList[0])
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            val body = response.body
            var responseBodyToReturn: String? = null

            if (body != null) {
                responseBodyToReturn = body.string()
            }

            val latestUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl
            )
        }
    }
}