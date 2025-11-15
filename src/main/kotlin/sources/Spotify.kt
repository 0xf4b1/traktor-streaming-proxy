package sources

import beatport.api.*
import io.github.tiefensuche.spotify.api.SpotifyApi
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.File

class Spotify : ISource {

    private val _api = SpotifyApi()
    private val api: SpotifyApi
        get() {
            _api.token = token()
            return _api
        }

    private var session: Session? = null
    private val playlistIds = mutableListOf<String>()

    override val name: String
        get() = "Spotify"

    init {
        createSession()
    }

    override fun getGenre(): List<Track> {
        val res = api.getUsersSavedTracks(true).toMutableList()
        do {
            val next = api.getUsersSavedTracks(false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return mapTracks(res)
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return mapPlaylists(api.getArtists(reset))
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return getAllTracks(playlistIds[id.toInt()], api::getArtist)
    }

    override fun getPlaylists(): List<Playlist> {
        return mapPlaylists(api.getUsersPlaylists(true))
    }

    override fun getPlaylist(id: String): List<Track> {
        return getAllTracks(playlistIds[id.toInt()], api::getPlaylist)
    }

    override fun getTop100(): List<Track> {
        for (category in api.getBrowseCategories(true)) {
            if (category.name == "New Releases") {
                for (playlist in api.getCategoryPlaylists(category.id, true)) {
                    if (playlist.title == "Release Radar") {
                        return getAllTracks(playlist.id, api::getPlaylist)
                    }
                }
            }
        }
        return emptyList()
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        return mapTracks(api.query(query, reset))
    }

    override fun download(id: String): ByteArray {
        streamUri(id)
        return File("output.mp4").readBytes()
    }

    private fun createSession() {
        val conf = Session.Configuration.Builder()
            .setCacheEnabled(false)
            .build()

        session = Session.Builder(conf).oauth().create()
    }

    private fun token(): String {
        session?.let {
            return "Bearer " + it.tokens().getToken().accessToken
        } ?: throw Exception("No session!")
    }

    private fun getAllTracks(id: String, func: (id: String, refresh: Boolean) -> List<io.github.tiefensuche.spotify.api.Track>): List<Track> {
        val res = func(id, true).toMutableList()
        do {
            val next = func(id, false)
            res.addAll(next)
        } while (next.isNotEmpty())
        return mapTracks(res)
    }

    private fun mapTracks(tracks: List<io.github.tiefensuche.spotify.api.Track>): List<Track> {
        return tracks.filter { it.playable }.map { track -> Track(track.id.substring(track.id.lastIndexOf(':') + 1), listOf(Artist(1, track.artist)), track.title, track.duration) }
    }

    private fun mapPlaylists(playlists: List<io.github.tiefensuche.spotify.api.Playlist>): List<Playlist> {
        return playlists.map { artist ->
            playlistIds.add(artist.id)
            Playlist((playlistIds.size - 1).toLong(), artist.title)
        }
    }

    private fun streamUri(id: String) {
        val uri = "spotify:track:$id"
        val stream = session!!.contentFeeder().load(TrackId.fromUri(uri), VorbisOnlyAudioQuality(AudioQuality.HIGH), true, null)
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
}