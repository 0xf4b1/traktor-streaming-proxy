package sources

import Config.prop
import beatport.api.Artist
import beatport.api.Track
import com.tiefensuche.tidal.api.TidalApi
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.SAXParserFactory

class Tidal : ISource {

    private val api = TidalApi(
        TidalApi.Session(
            prop.getProperty("tidal.clientId"),
            prop.getProperty("tidal.clientSecret"),
            ::callback
        )
    )

    init {
        if (!readConfig())
            auth()
    }

    private val playlistTypes = ConcurrentHashMap<String, PlaylistType>()
    private enum class PlaylistType {
        PLAYLIST,
        MIX
    }

    override val name: String
        get() = "Tidal"

    override fun getGenre(): List<Track> {
        val res = api.getTracks(true).toMutableList()
        do {
            val next = api.getTracks(false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res.map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
    }

    override fun getCuratedPlaylists(reset: Boolean): List<SourcePlaylist> {
        return api.getArtists(reset).map { artist ->
            SourcePlaylist(artist.id.toString(), artist.name)
        }
    }

    override fun getPlaylists(): List<SourcePlaylist> {
        val res = api.getMixes().map {
            playlistTypes[it.uuid] = PlaylistType.MIX
            SourcePlaylist(it.uuid, it.title)
        }.toMutableList()

        res.addAll(api.getPlaylists(true).map {
            playlistTypes[it.uuid] = PlaylistType.PLAYLIST
            SourcePlaylist(it.uuid, it.title)
        })

        return res
    }

    override fun getPlaylist(id: String): List<Track> {
        playlistTypes[id]?.let { playlistType ->
            return when (playlistType) {
                PlaylistType.PLAYLIST -> {
                    val res = api.getPlaylist(id, true).toMutableList()
                    do {
                        val next = api.getPlaylist(id, false)
                        res.addAll(next)
                    } while (next.isNotEmpty())
                    res
                }
                PlaylistType.MIX -> api.getMix(id, true)
            }.map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
        }
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        val artistId = id.toLongOrNull() ?: return emptyList()
        val res = api.getArtist(artistId, true).toMutableList()
        do {
            val next = api.getArtist(artistId, false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res.map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
    }

    override fun getTop100(): List<Track> {
        api.getMixes().forEach {
            if (it.title == "My New Arrivals")
                return api.getMix(it.uuid, false).map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
        }
        return emptyList()
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        return api.query(query, reset)
            .map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
    }

    override fun download(id: String): ByteArray {
        val manifest = api.getStreamManifest(id.toLong())
        val factory = SAXParserFactory.newInstance()
        val parser = factory.newSAXParser()
        val mpd = object : DefaultHandler() {
            var initialization: String = ""
            var media: String = ""
            var startNumber = 0
            var endNumber = 0
            override fun startElement(
                uri: String,
                localName: String,
                qName: String,
                attributes: Attributes
            ) {
                when (qName) {
                    "SegmentTemplate" -> {
                        initialization = attributes.getValue("initialization")!!
                        media = attributes.getValue("media")!!
                        startNumber = attributes.getValue("startNumber")!!.toInt()
                    }
                    "S" -> {
                        endNumber++
                        attributes.getValue("r")?.let {
                            endNumber += it.toInt()
                        }
                    }
                }
            }
        }
        parser.parse(InputSource(StringReader(manifest)), mpd)

        val out = ByteArrayOutputStream()
        var con = URL(mpd.initialization).openConnection()
        out.write(con.getInputStream().readBytes())
        for (i in mpd.startNumber..mpd.endNumber) {
            val url = mpd.media.replace("\$Number\$", i.toString())
            con = URL(url).openConnection()
            out.write(con.getInputStream().readBytes())
        }
        return out.toByteArray()
    }

    private fun readConfig(): Boolean {
        if (!prop.containsKey("tidal.userId") || !prop.containsKey("tidal.countryCode") ||
            !prop.containsKey("tidal.accessToken") || !prop.containsKey("tidal.refreshToken")
        )
            return false
        api.session.setAuth(
            prop.getProperty("tidal.userId").toLong(),
            prop.getProperty("tidal.countryCode"),
            prop.getProperty("tidal.accessToken"),
            prop.getProperty("tidal.refreshToken")
        )
        return true
    }

    private fun auth() {
        val verificationUriComplete = api.auth()
        println("verificationUriComplete: $verificationUriComplete")
        while (!api.getAccessToken()) {
            println("Auth pending...")
            Thread.sleep(5_000)
        }
        println("Auth complete.")
    }

    private fun callback(session: TidalApi.Session) {
        prop.setProperty("tidal.userId", session.userId.toString())
        prop.setProperty("tidal.countryCode", session.countryCode)
        prop.setProperty("tidal.accessToken", session.accessToken)
        prop.setProperty("tidal.refreshToken", session.refreshToken)
    }
}
