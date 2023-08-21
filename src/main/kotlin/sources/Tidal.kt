package sources

import beatport.api.Artist
import beatport.api.Track
import com.tiefensuche.tidal.api.TidalApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.*

class Tidal : ISource {

    private val api = TidalApi(
        TidalApi.Session(
            "YOUR-CLIENT-ID",
            ::callback
        )
    )

    init {
        if (!readConfig())
            auth()
    }

    private var artists: List<com.tiefensuche.tidal.api.Artist>? = null

    override val name: String
        get() = "Tidal"

    override fun getGenre(): List<Track> {
        return api.getTracks(false).map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
    }

    override fun getPlaylists(): List<String> {
        if (artists == null)
            artists = api.getArtists(false)
        return artists!!.map { it.name }
    }

    override fun getPlaylist(id: Int): List<Track> {
        return api.getArtist(artists!![id].id, false)
            .map { Track(it.id.toString(), listOf(Artist(1, it.artist)), it.title, it.duration) }
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
        val file = File("config.properties")
        if (!file.exists())
            return false
        val prop = Properties()
        FileInputStream(file).use { prop.load(it) }
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
        val file = File("config.properties")
        val prop = Properties()
        FileOutputStream(file).use {
            prop.setProperty("tidal.userId", session.userId.toString())
            prop.setProperty("tidal.countryCode", session.countryCode)
            prop.setProperty("tidal.accessToken", session.accessToken)
            prop.setProperty("tidal.refreshToken", session.refreshToken)
            prop.store(it, "")
        }
    }
}