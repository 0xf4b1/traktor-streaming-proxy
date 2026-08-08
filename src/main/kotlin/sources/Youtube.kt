package sources

import beatport.api.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.util.*

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

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getPlaylists(): List<Playlist> {
        return emptyList()
    }

    override fun getPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getTop100(): List<Track> {
        return emptyList()
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(MUSIC_SONGS), "")
        val itemsPage = if (!reset && next.containsKey(query)) {
            if (next[query] == null)
                return emptyList()
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
        val con = URI.create(path).toURL().openConnection()
        con.setRequestProperty("range", "bytes=0-")
        return con.inputStream.readBytes()
    }

    private fun getTrending(): List<Track> {
        val extractor = ServiceList.YouTube.kioskList.getExtractorById("trending_music", null)
        extractor.fetchPage()
        return extractItems(extractor.initialPage.items)
    }

    private fun extractItems(items: List<InfoItem>): List<Track> {
        val results = LinkedList<Track>()
        for (item in items) {
            if (item is StreamInfoItem) {
                results.add(Track(item.url.substring(item.url.indexOf('=') + 1), listOf(Artist(1, item.uploaderName)), item.name, item.duration * 1000))
            }
        }
        return results
    }

    private fun getAudioStream(url: String): String {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$url")
        extractor.fetchPage()
        val m4a = extractor.audioStreams.filter { it.format?.name == "m4a" && it.isUrl }
        // Prefer progressive HTTP when available; fall back to highest-bitrate m4a URL.
        val stream = m4a
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }
            ?: m4a.maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("No m4a audio stream for YouTube id=$url")
        return stream.content
    }

    class Downloader : org.schabi.newpipe.extractor.downloader.Downloader() {

        private val userAgent = "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"

        override fun execute(request: Request): Response {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = userAgent
            request.headers().forEach { (k, v) -> headers[k] = v[0]}

            val con = WebRequests.createConnection(request.url(), request.httpMethod(), headers)

            if (request.httpMethod() == "POST")
                WebRequests.post(con, request.dataToSend() as ByteArray)

            val res = WebRequests.request(con)

            return Response(res.status, "", con.headerFields, res.value, request.url())
        }
    }
}