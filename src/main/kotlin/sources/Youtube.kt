package sources

import Config.prop
import beatport.api.Artist
import beatport.api.Track
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URL
import java.util.*

class Youtube : ISource {

    private val next = HashMap<String, Page?>()
    private val account by lazy {
        OAuthCredentialStore.fromConfig(
            "youtube.oauth.file",
            "YOUTUBE_OAUTH_FILE",
            "https://oauth2.googleapis.com/token"
        )?.let(::YouTubeAccountClient)
    }
    private val configuredPlaylistIds by lazy {
        prop.getProperty("youtube.playlists", "")
            .split(',')
            .mapNotNull(::extractPlaylistId)
            .distinct()
    }

    override val name: String
        get() = "YouTube"

    init {
        NewPipeClient.initialize()
    }

    override fun getGenre(): List<Track> {
        return getTrending()
    }

    override fun getCuratedPlaylists(reset: Boolean): List<SourcePlaylist> {
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getPlaylists(): List<SourcePlaylist> {
        val configured = configuredPlaylistIds.mapNotNull { playlistId ->
            try {
                val extractor = playlistExtractor(playlistId)
                extractor.fetchPage()
                SourcePlaylist(playlistId, extractor.name)
            } catch (exception: Exception) {
                System.err.println("Could not load YouTube playlist '$playlistId': ${exception.message}")
                null
            }
        }
        val accountPlaylists = try {
            account?.getPlaylists().orEmpty()
        } catch (exception: Exception) {
            System.err.println("Could not load YouTube account playlists: ${exception.message}")
            emptyList()
        }
        return (configured + accountPlaylists).distinctBy(SourcePlaylist::id)
    }

    override fun getPlaylist(id: String): List<Track> {
        if (YouTubeAccountClient.isAccountId(id)) {
            return account?.getPlaylist(id).orEmpty()
        }
        if (id !in configuredPlaylistIds) {
            return emptyList()
        }

        val extractor = playlistExtractor(id)
        extractor.fetchPage()

        val tracks = mutableListOf<Track>()
        var page = extractor.initialPage
        var pageCount = 0
        while (true) {
            tracks.addAll(extractItems(page.items))
            val nextPage = page.nextPage ?: break
            check(++pageCount < MAX_PLAYLIST_PAGES) {
                "YouTube playlist '$id' exceeded $MAX_PLAYLIST_PAGES pages"
            }
            page = extractor.getPage(nextPage)
        }
        return tracks
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
        val con = URL(path).openConnection()
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
                results.add(
                    Track(
                        extractVideoId(item.url),
                        listOf(Artist(1, item.uploaderName)),
                        item.name,
                        item.duration * 1000
                    )
                )
            }
        }
        return results
    }

    private fun playlistExtractor(playlistId: String) =
        ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$playlistId")

    private fun extractPlaylistId(value: String): String? {
        val candidate = value.trim()
        if (candidate.isEmpty()) {
            return null
        }

        val id = PLAYLIST_ID_IN_URL.find(candidate)?.groupValues?.get(1) ?: candidate
        if (!PLAYLIST_ID.matches(id)) {
            System.err.println("Ignoring invalid YouTube playlist ID or URL: '$candidate'")
            return null
        }
        return id
    }

    private fun extractVideoId(url: String): String {
        VIDEO_ID_IN_URL.find(url)?.groupValues?.get(1)?.let { return it }
        SHORT_VIDEO_ID_IN_URL.find(url)?.groupValues?.get(1)?.let { return it }
        return url
    }

    private fun getAudioStream(url: String): String {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$url")
        extractor.fetchPage()
        return extractor.audioStreams.filter { it.format!!.name == "m4a" }.maxBy { it.averageBitrate }.content
    }

    companion object {
        private const val MAX_PLAYLIST_PAGES = 1_000
        private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]+$")
        private val PLAYLIST_ID_IN_URL = Regex("[?&]list=([A-Za-z0-9_-]+)")
        private val VIDEO_ID_IN_URL = Regex("[?&]v=([A-Za-z0-9_-]+)")
        private val SHORT_VIDEO_ID_IN_URL = Regex("youtu\\.be/([A-Za-z0-9_-]+)")
    }
}
