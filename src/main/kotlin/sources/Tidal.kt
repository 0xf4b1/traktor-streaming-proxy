package sources

import Config.prop
import beatport.api.Artist
import beatport.api.Playlist
import beatport.api.Track
import com.tiefensuche.tidal.api.TidalApi
import java.net.URL
import java.util.*

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

    private var artists = ArrayList<com.tiefensuche.tidal.api.Artist>()
    private val playlists = mutableListOf<Pair<String, PlaylistType>>()
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

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        val current = api.getArtists(false)
        artists.addAll(current)
        return current.mapIndexed { id, artist -> Playlist((artists.size - current.size + id).toLong(), artist.name) }
    }

    override fun getPlaylists(): List<Playlist> {
        val res = api.getMixes().map {
            playlists.add(Pair(it.uuid, PlaylistType.MIX))
            Playlist((playlists.size - 1).toLong(), it.title)
        }.toMutableList()

        res.addAll(api.getPlaylists(true).map {
            playlists.add(Pair(it.uuid, PlaylistType.PLAYLIST))
            Playlist((playlists.size - 1).toLong(), it.title)
        })

        return res
    }

    override fun getPlaylist(id: String): List<Track> {
        playlists[id.toInt()].let {
            return when (it.second) {
                PlaylistType.PLAYLIST -> {
                    val res = api.getPlaylist(it.first, true).toMutableList()
                    do {
                        val next = api.getPlaylist(it.first, false)
                        res.addAll(next)
                    } while (next.isNotEmpty())
                    res
                }
                PlaylistType.MIX -> api.getMix(it.first, true)
            }.map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
        }
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        val res = api.getArtist(artists[id.toInt()].id, true).toMutableList()
        do {
            val next = api.getArtist(artists[id.toInt()].id, false)
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
        val con = URL(api.getStreamUrl(id.toLong())).openConnection()
        return con.getInputStream().readBytes()
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