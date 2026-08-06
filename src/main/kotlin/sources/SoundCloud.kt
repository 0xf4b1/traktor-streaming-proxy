package sources

import Config.prop
import beatport.api.Artist
import beatport.api.Track
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory.TRACKS
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

class SoundCloud : ISource {
    private val nextSearchPages = ConcurrentHashMap<String, Page>()
    private val exhaustedSearches = ConcurrentHashMap.newKeySet<String>()
    private val audioSettings by lazy { SoundCloudAudioSettings.from(prop) }
    private val account by lazy {
        OAuthCredentialStore.fromConfig(
            "soundcloud.oauth.file",
            "SOUNDCLOUD_OAUTH_FILE",
            "https://secure.soundcloud.com/oauth/token"
        )?.let(::SoundCloudAccountClient)
    }
    private val configuredPlaylistUrls by lazy {
        prop.getProperty("soundcloud.playlists", "")
            .split(',')
            .mapNotNull { value ->
                SoundCloudUrl.normalizePlaylist(value).also { normalized ->
                    if (value.isNotBlank() && normalized == null) {
                        System.err.println("Ignoring invalid SoundCloud playlist URL: '${value.trim()}'")
                    }
                }
            }
            .distinct()
    }

    override val name: String
        get() = "SoundCloud"

    init {
        NewPipeClient.initialize()
    }

    override fun getGenre(): List<Track> = getNewAndHot()

    override fun getCuratedPlaylists(reset: Boolean): List<SourcePlaylist> = emptyList()

    override fun getCuratedPlaylist(id: String): List<Track> = emptyList()

    override fun getPlaylists(): List<SourcePlaylist> {
        val configured = configuredPlaylistUrls.mapNotNull { playlistUrl ->
            try {
                val extractor = ServiceList.SoundCloud.getPlaylistExtractor(playlistUrl)
                extractor.fetchPage()
                SourcePlaylist(playlistUrl, extractor.name)
            } catch (exception: Exception) {
                System.err.println(
                    "Could not load SoundCloud playlist '$playlistUrl': ${exception.message}"
                )
                null
            }
        }
        val accountPlaylists = try {
            account?.getPlaylists().orEmpty()
        } catch (exception: Exception) {
            System.err.println("Could not load SoundCloud account playlists: ${exception.message}")
            emptyList()
        }
        return (configured + accountPlaylists).distinctBy(SourcePlaylist::id)
    }

    override fun getPlaylist(id: String): List<Track> {
        if (SoundCloudAccountClient.isAccountId(id)) {
            return account?.getPlaylist(id).orEmpty()
        }
        if (id !in configuredPlaylistUrls) {
            return emptyList()
        }

        val extractor = ServiceList.SoundCloud.getPlaylistExtractor(id)
        extractor.fetchPage()

        val tracks = mutableListOf<Track>()
        var page = extractor.initialPage
        var pageCount = 0
        while (true) {
            tracks.addAll(extractItems(page.items))
            val nextPage = page.nextPage
            if (!Page.isValid(nextPage)) {
                break
            }
            check(++pageCount < MAX_PLAYLIST_PAGES) {
                "SoundCloud playlist '$id' exceeded $MAX_PLAYLIST_PAGES pages"
            }
            page = extractor.getPage(nextPage)
        }
        return tracks
    }

    override fun getTop100(): List<Track> = getNewAndHot()

    override fun query(query: String, reset: Boolean): List<Track> {
        val extractor = ServiceList.SoundCloud.getSearchExtractor(query, listOf(TRACKS), "")
        val page = if (!reset) {
            if (query in exhaustedSearches) {
                return emptyList()
            }
            val nextPage = nextSearchPages[query] ?: return emptyList()
            extractor.getPage(nextPage)
        } else {
            extractor.fetchPage()
            extractor.initialPage
        }
        rememberNextSearchPage(query, page.nextPage)
        return extractItems(page.items)
    }

    override fun download(id: String): ByteArray {
        val stream = resolveAudioStream(id)
        return FfmpegTranscoder.audioUrlToMp4(
            stream.content,
            remux = shouldRemux(stream),
            transcodeBitrate = audioSettings.transcodeBitrate,
            audioFilter = audioSettings.audioProfile.filterChain
        )
    }

    override fun downloadCacheKey(id: String): String = listOf(
        "compatible-mp4-v1",
        id,
        audioSettings.qualityMode.name,
        audioSettings.preferRemux,
        audioSettings.transcodeBitrate,
        audioSettings.audioProfile.name
    ).joinToString("|")

    private fun resolveAudioStream(id: String): AudioStream {
        val extractor = ServiceList.SoundCloud.getStreamExtractor(id)
        extractor.fetchPage()
        return SoundCloudAudioSelector.select(extractor.audioStreams, audioSettings.qualityMode)
            ?: throw IllegalStateException("No playable SoundCloud audio stream found for $id")
    }

    private fun shouldRemux(stream: AudioStream): Boolean =
        audioSettings.preferRemux &&
            !audioSettings.audioProfile.requiresTranscode &&
            SoundCloudAudioSelector.canRemux(stream)

    private fun getNewAndHot(): List<Track> {
        val extractor = ServiceList.SoundCloud.kioskList
            .getExtractorById("New & hot", null)
        extractor.fetchPage()
        return extractItems(extractor.initialPage.items)
    }

    private fun extractItems(items: List<InfoItem>): List<Track> {
        return items.filterIsInstance<StreamInfoItem>().map { item ->
            Track(
                item.url,
                listOf(Artist(1, item.uploaderName)),
                item.name,
                item.duration * 1_000
            )
        }
    }

    private fun rememberNextSearchPage(query: String, nextPage: Page?) {
        if (Page.isValid(nextPage)) {
            nextSearchPages[query] = requireNotNull(nextPage)
            exhaustedSearches.remove(query)
        } else {
            nextSearchPages.remove(query)
            exhaustedSearches.add(query)
        }
    }

    companion object {
        private const val MAX_PLAYLIST_PAGES = 1_000
    }
}
