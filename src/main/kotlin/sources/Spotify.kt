package sources

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

    override val name: String
        get() = "Spotify"

    init {
        createSession()
    }

    override fun getGenre(): List<Track> {
        return getReleaseRadar(false)
    }

    override fun getPlaylists(reset: Boolean): List<String> {
        if (reset)
            return listOf("Release Radar", "Saved Tracks")
        return emptyList()
    }

    override fun getPlaylist(id: Int, reset: Boolean): List<Track> {
        when (id) {
            0 -> return getReleaseRadar(false)
            1 -> return getUsersSavedTracks(false)
        }
        return emptyList()
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
        val username = System.getenv("SPOTIFY_USERNAME")
        val password = System.getenv("SPOTIFY_PASSWORD")
        if (username.isEmpty() || password.isEmpty()) {
            throw Exception("Username and/or password missing!")
        }
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
            return "Bearer " + it.tokens().getToken("user-library-read", "user-library-modify").accessToken
        } ?: throw Exception("No session!")
    }

    private fun getReleaseRadar(refresh: Boolean): List<Track> {
        val response = JSONObject(WebRequests.request(WebRequests.createConnection(RELEASE_RADAR_URL, headers = mapOf("Authorization" to token()))).value)
        val id = response.getJSONObject("playlists").getJSONArray("items").getJSONObject(0).getString("id")
        val items = request(String.format(PLAYLIST_URL, id), refresh, "tracks")
        return parseTracks(items)
    }

    private fun getUsersSavedTracks(refresh: Boolean): List<Track> {
        val items = request(USERS_SAVED_TRACKS_URL, refresh)
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
        if (type != null) {
            response = response.getJSONObject(type)
        }
        nextQueryUrls[url] = if (!response.isNull("next")) response.getString("next") else "null"
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
        private const val USERS_SAVED_TRACKS_URL = "$BASE_URL/me/tracks"
        private const val RELEASE_RADAR_URL = "$BASE_URL/search?q=Release-Radar&type=playlist&limit=1"
        private const val PLAYLIST_URL = "$BASE_URL/playlists/%s"
    }
}