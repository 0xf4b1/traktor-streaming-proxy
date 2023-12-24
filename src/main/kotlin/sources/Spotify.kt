package sources

import Config.prop
import beatport.api.*
import com.spotify.connectstate.Connect
import org.json.JSONArray
import org.json.JSONObject
import xyz.gianlu.librespot.audio.HaltListener
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.File
import java.net.URLEncoder
import java.util.*

class Spotify : ISource {

    private var session: Session? = null
    private val nextQueryUrls: HashMap<String, String> = HashMap()
    private val playlists = mutableListOf<String>()

    override val name: String
        get() = "Spotify"

    init {
        createSession()
    }

    override fun getGenre(): List<Track> {
        val res = getUsersSavedTracks(true).toMutableList()
        do {
            val next = getUsersSavedTracks(false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return getArtists(reset)
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        val res = getArtist(id, true).toMutableList()
        do {
            val next = getArtist(id, false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res
    }

    override fun getPlaylists(): List<Playlist> {
        return getUsersPlaylists(true)
    }

    override fun getPlaylist(id: String): List<Track> {
        val res = getPlaylist(id, true).toMutableList()
        do {
            val next = getPlaylist(id, false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res
    }

    override fun getTop100(): List<Track> {
        val res = getReleaseRadar(true).toMutableList()
        do {
            val next = getReleaseRadar(false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return res
    }

    override fun query(query: String, refresh: Boolean): List<Track> {
        val items = request(String.format(QUERY_URL, URLEncoder.encode(query, "utf-8")), refresh, "tracks")
        return parseTracks(items)
    }

    override fun download(id: String): ByteArray {
        streamUri(id)
        return File("output.mp4").readBytes()
    }

    private fun createSession() {
        if (!prop.containsKey("spotify.username") || !prop.containsKey("spotify.password")) {
            throw Exception("Username and/or password missing!")
        }
        val username = prop.getProperty("spotify.username")
        val password = prop.getProperty("spotify.password")
        val conf = Session.Configuration.Builder()
            .setStoreCredentials(false)
            .setCacheEnabled(true)
            .setConnectionTimeout(30 * 1000)
            .build()
        val builder = Session.Builder(conf)
            .setPreferredLocale(Locale.getDefault().language)
            .setDeviceType(Connect.DeviceType.SMARTPHONE)
            .setDeviceId("4ea37d93fef568dcc3e5c2722e775635830accab")
            .userPass(username, password)
        session = builder.create()
    }

    private fun token(): String {
        session?.let {
            return "Bearer " + it.tokens().getToken("user-library-read", "user-follow-read").accessToken
        } ?: throw Exception("No session!")
    }

    private fun getReleaseRadar(refresh: Boolean): List<Track> {
        val response = JSONObject(WebRequests.request(WebRequests.createConnection(RELEASE_RADAR_URL, headers = mapOf("Authorization" to token()))).value)
        val id = response.getJSONObject("playlists").getJSONArray("items").getJSONObject(0).getString("id")
        playlists.add(id)
        return getPlaylist((playlists.size - 1).toString(), refresh)
    }

    private fun getUsersSavedTracks(refresh: Boolean): List<Track> {
        val items = request(USERS_SAVED_TRACKS_URL, refresh)
        return parseTracks(items)
    }

    private fun getArtists(refresh: Boolean): List<Playlist> {
        val items = request(USERS_FOLLOWING, refresh, "artists")
        return parsePlaylists(items)
    }

    private fun getArtist(id: String, refresh: Boolean): List<Track> {
        val items = request(ARTIST_TRACKS.format(playlists[id.toInt()]), refresh, "tracks")
        return parseTracks(items)
    }

    private fun getUsersPlaylists(refresh: Boolean): List<Playlist> {
        val items = request(USERS_PLAYLISTS, refresh)
        return parsePlaylists(items)
    }

    private fun getPlaylist(id: String, refresh: Boolean): List<Track> {
        val items = request(String.format(PLAYLIST_URL, playlists[id.toInt()]), refresh, "tracks")
        return parseTracks(items)
    }

    private fun request(url: String, refresh: Boolean, type: String? = null): JSONArray {
        val request = if (!refresh && url in nextQueryUrls) {
            if (nextQueryUrls[url] == "null") {
                return JSONArray()
            }
            nextQueryUrls[url].toString()
        } else {
            url
        }
        var response = JSONObject(WebRequests.request(WebRequests.createConnection(request, headers = mapOf("Authorization" to token()))).value)
        if (type != null && response.get(type) is JSONObject) {
            response = response.getJSONObject(type)
        }
        nextQueryUrls[url] = if (!response.isNull("next")) response.getString("next") else "null"
        if (type != null && response.has(type))
            return response.getJSONArray(type)
        return response.getJSONArray("items")
    }

    private fun parseTracks(items: JSONArray): List<Track> {
        val result = mutableListOf<Track>()
        if (items.length() == 0) {
            return result
        }
        for (i in 0 until items.length()) {
            var item = items.getJSONObject(i)
            if (item.has("track")) {
                item = item.getJSONObject("track")
            }

            val code = item.getString("uri")
            result.add(Track(code.substring(code.lastIndexOf(':') + 1),
                listOf(Artist(1, item.getJSONArray("artists").getJSONObject(0).getString("name"))),
                item.getString("name"), item.getLong("duration_ms"))
            )
        }
        return result
    }

    private fun parsePlaylists(items: JSONArray): List<Playlist> {
        val result = mutableListOf<Playlist>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            playlists.add(item.getString("id"))
            result.add(Playlist(playlists.size.toLong() - 1, item.getString("name")))
        }
        return result
    }

    private fun streamUri(id: String) {
        val uri = "spotify:track:$id"
        val stream = session!!.contentFeeder().load(
            TrackId.fromUri(uri),
            VorbisOnlyAudioQuality(AudioQuality.HIGH),
            true,
            object : HaltListener {
                override fun streamReadHalted(chunk: Int, time: Long) {}
                override fun streamReadResumed(chunk: Int, time: Long) {}
            })

        val proc = Runtime.getRuntime().exec(arrayOf("ffmpeg", "-y", "-f", "ogg", "-i", "pipe:", "output.mp4"))
        var cur: Int
        while (stream.`in`.stream().read().also { cur = it } != -1) {
            proc.outputStream.write(cur)
        }
        stream.`in`.stream().close()
        proc.outputStream.flush()
        proc.outputStream.close()
        proc.waitFor()
    }

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val QUERY_URL = "$BASE_URL/search?q=%s&type=track"
        private const val USERS_SAVED_TRACKS_URL = "$BASE_URL/me/tracks?limit=50"
        private const val USERS_FOLLOWING = "$BASE_URL/me/following?type=artist&limit=50"
        private const val ARTIST_TRACKS = "$BASE_URL/artists/%s/top-tracks?market=US"
        private const val USERS_PLAYLISTS = "$BASE_URL/me/playlists?limit=50"
        private const val RELEASE_RADAR_URL = "$BASE_URL/search?q=Release-Radar&type=playlist&limit=1"
        private const val PLAYLIST_URL = "$BASE_URL/playlists/%s"
    }
}