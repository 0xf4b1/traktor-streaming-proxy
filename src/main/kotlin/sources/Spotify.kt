package sources

import Config.prop
import beatport.api.*
import org.json.JSONArray
import org.json.JSONObject
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class Spotify : ISource {

    private var session: Session? = null
    private val playlistIds = mutableListOf<String>()

    private val clientId = prop.getProperty("spotify.clientId", "")
    private val clientSecret = prop.getProperty("spotify.clientSecret", "")
    private var accessToken: String = ""
    private var refreshTokenValue: String = ""
    private var tokenExpiry: Long = 0

    override val name: String
        get() = "Spotify"

    init {
        authenticate()
        createLibrespotSession()
    }

    private fun authenticate() {
        // Try saved refresh token first
        val saved = prop.getProperty("spotify.refreshToken", "")
        if (saved.isNotBlank()) {
            refreshTokenValue = saved
            try {
                refreshAccessToken()
                println("Spotify: authenticated with saved token")
                return
            } catch (e: Exception) {
                println("Spotify: saved token failed: ${e.message}")
                println("Spotify: starting OAuth flow...")
            }
        }

        // OAuth PKCE flow
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val redirectUri = "http://127.0.0.1:5589/callback"
        val scopes = "playlist-read-private playlist-read-collaborative user-library-read streaming"

        val authUrl = "https://accounts.spotify.com/authorize?" +
            "client_id=$clientId&response_type=code" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&scope=${URLEncoder.encode(scopes, "UTF-8")}" +
            "&code_challenge_method=S256&code_challenge=$challenge"

        var authCode: String? = null
        val lock = Object()
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 5589), 0)
        server.createContext("/callback") { exchange ->
            val params = (exchange.requestURI.query ?: "").split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
            authCode = params["code"]
            val msg = if (authCode != null) "Success! You can close this window." else "Error: ${params["error"]}"
            exchange.sendResponseHeaders(200, msg.length.toLong())
            exchange.responseBody.write(msg.toByteArray())
            exchange.responseBody.close()
            synchronized(lock) { lock.notify() }
        }
        server.start()

        println("Spotify: open this URL to authenticate:")
        println(authUrl)
        try { Runtime.getRuntime().exec(arrayOf("open", authUrl)) } catch (_: Exception) {}

        synchronized(lock) { lock.wait(120000) }
        server.stop(0)

        if (authCode == null) throw Exception("Spotify OAuth timed out or was denied")

        // Exchange code for tokens
        val body = "grant_type=authorization_code&code=$authCode" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&client_id=$clientId&code_verifier=$verifier"
        val json = JSONObject(httpPost("https://accounts.spotify.com/api/token", body))
        accessToken = json.getString("access_token")
        refreshTokenValue = json.getString("refresh_token")
        tokenExpiry = System.currentTimeMillis() + json.getInt("expires_in") * 1000L

        prop.setProperty("spotify.refreshToken", refreshTokenValue)
        Config.saveConfig()
        println("Spotify: authenticated successfully")
    }

    private fun refreshAccessToken() {
        val body = "grant_type=refresh_token&refresh_token=$refreshTokenValue&client_id=$clientId"
        val json = JSONObject(httpPost("https://accounts.spotify.com/api/token", body))
        accessToken = json.getString("access_token")
        tokenExpiry = System.currentTimeMillis() + json.getInt("expires_in") * 1000L
        if (json.has("refresh_token")) {
            refreshTokenValue = json.getString("refresh_token")
            prop.setProperty("spotify.refreshToken", refreshTokenValue)
            Config.saveConfig()
        }
    }

    private fun token(): String {
        if (System.currentTimeMillis() > tokenExpiry - 60000) {
            refreshAccessToken()
        }
        return "Bearer $accessToken"
    }

    private fun <T> withRetry(action: () -> T): T {
        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                return action()
            } catch (e: IOException) {
                val msg = e.message ?: ""
                if (msg.contains("429") || msg.contains("rate limit")) {
                    lastException = e
                    val waitMs = (attempt * 2000).toLong()
                    println("Spotify rate limited, waiting ${waitMs}ms before retry $attempt/3...")
                    Thread.sleep(waitMs)
                } else if (msg.contains("401")) {
                    refreshAccessToken()
                    lastException = e
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    private fun spotifyGet(url: String): String {
        val con = URI(url).toURL().openConnection() as? HttpsURLConnection ?: throw IOException("Failed to open connection")
        con.requestMethod = "GET"
        con.setRequestProperty("Authorization", token())
        con.connectTimeout = 30000
        con.readTimeout = 30000

        if (con.responseCode < 400) {
            return con.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val error = con.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
            throw IOException("HTTP ${con.responseCode}: $error")
        }
    }

    private fun httpPost(url: String, body: String): String {
        val con = URI(url).toURL().openConnection() as HttpsURLConnection
        con.requestMethod = "POST"
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        con.doOutput = true
        con.outputStream.write(body.toByteArray())
        con.outputStream.close()

        if (con.responseCode < 400) {
            return con.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val error = con.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "Unknown error"
            throw IOException("HTTP ${con.responseCode}: $error")
        }
    }

    // --- ISource implementation ---

    override fun getPlaylists(): List<Playlist> {
        val response = withRetry { spotifyGet("https://api.spotify.com/v1/me/playlists?limit=50") }
        val json = JSONObject(response)
        return parsePlaylists(json.getJSONArray("items"))
    }

    override fun getPlaylist(id: String): List<Track> {
        return getPlaylistTracks(playlistIds[id.toInt()])
    }

    override fun getGenre(): List<Track> {
        val allTracks = mutableListOf<Track>()
        var url: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        while (url != null) {
            val response = withRetry { spotifyGet(url!!) }
            val json = JSONObject(response)
            allTracks.addAll(parseTracksFromItems(json.getJSONArray("items")))
            url = if (!json.isNull("next")) json.getString("next") else null
            if (url != null) Thread.sleep(200)
        }
        return allTracks
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getTop100(): List<Track> {
        return try {
            val response = withRetry { spotifyGet("https://api.spotify.com/v1/search?q=Release-Radar&type=playlist&limit=1") }
            val json = JSONObject(response)
            val playlists = json.getJSONObject("playlists").getJSONArray("items")
            if (playlists.length() > 0) {
                getPlaylistTracks(playlists.getJSONObject(0).getString("id"))
            } else emptyList()
        } catch (e: Exception) {
            println("Failed to get Release Radar: ${e.message}")
            emptyList()
        }
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = withRetry { spotifyGet("https://api.spotify.com/v1/search?q=$encoded&type=track&limit=10") }
        val json = JSONObject(response)
        return parseTrackObjects(json.getJSONObject("tracks").getJSONArray("items"))
    }

    override fun download(id: String): ByteArray {
        streamUri(id)
        return File("output.mp4").readBytes()
    }

    // --- Parsing ---

    private fun getPlaylistTracks(playlistId: String): List<Track> {
        val allTracks = mutableListOf<Track>()
        var url: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=50"
        while (url != null) {
            val response = withRetry { spotifyGet(url!!) }
            val json = JSONObject(response)
            allTracks.addAll(parseTracksFromItems(json.getJSONArray("items")))
            url = if (!json.isNull("next")) json.getString("next") else null
            if (url != null) Thread.sleep(200)
        }
        return allTracks
    }

    private fun parseTracksFromItems(items: JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val track = when {
                item.has("track") && !item.isNull("track") -> item.getJSONObject("track")
                item.has("item") && !item.isNull("item") -> item.getJSONObject("item")
                else -> item
            }
            if (!track.has("uri") || track.getString("type") != "track") continue
            val playable = if (track.has("is_playable")) track.getBoolean("is_playable") else true
            if (!playable) continue

            val uri = track.getString("uri")
            val trackId = uri.substring(uri.lastIndexOf(':') + 1)
            val artists = mutableListOf<String>()
            val artistsArray = track.getJSONArray("artists")
            for (j in 0 until artistsArray.length()) {
                artists.add(artistsArray.getJSONObject(j).getString("name"))
            }

            tracks.add(Track(trackId, listOf(Artist(1, artists.joinToString())), track.getString("name"), track.getLong("duration_ms")))
        }
        return tracks
    }

    private fun parseTrackObjects(items: JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            val track = items.getJSONObject(i)
            val playable = if (track.has("is_playable")) track.getBoolean("is_playable") else true
            if (!playable) continue

            val uri = track.getString("uri")
            val trackId = uri.substring(uri.lastIndexOf(':') + 1)
            val artists = mutableListOf<String>()
            val artistsArray = track.getJSONArray("artists")
            for (j in 0 until artistsArray.length()) {
                artists.add(artistsArray.getJSONObject(j).getString("name"))
            }

            tracks.add(Track(trackId, listOf(Artist(1, artists.joinToString())), track.getString("name"), track.getLong("duration_ms")))
        }
        return tracks
    }

    private fun parsePlaylists(items: JSONArray): List<Playlist> {
        val result = mutableListOf<Playlist>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            playlistIds.add(item.getString("id"))
            result.add(Playlist((playlistIds.size - 1).toLong(), item.getString("name")))
        }
        return result
    }

    // --- Librespot (streaming only, lazy init) ---

    private var librespotInitializing = false

    @Synchronized
    private fun createLibrespotSession() {
        if (session != null) return
        if (librespotInitializing) throw Exception("Librespot authentication in progress - please complete browser login first")
        librespotInitializing = true

        val credentialsFile = File("credentials.json")
        val conf = Session.Configuration.Builder()
            .setCacheEnabled(false)
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .build()

        if (credentialsFile.exists()) {
            println("Librespot: using stored credentials")
            session = Session.Builder(conf).stored(credentialsFile).create()
            return
        }

        // Do our own OAuth with librespot's client ID but only valid scopes
        val librespotClientId = "65b708073fc0480ea92a077233ca87bd"
        val port = 5588
        val redirectUri = "http://127.0.0.1:$port/login"
        val validScopes = "streaming user-library-read playlist-read-private playlist-read-collaborative"

        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

        val authUrl = "https://accounts.spotify.com/authorize?" +
            "response_type=code&client_id=$librespotClientId" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&code_challenge=$challenge&code_challenge_method=S256" +
            "&scope=${URLEncoder.encode(validScopes, "UTF-8")}"

        var authCode: String? = null
        val lock = Object()
        val server = try {
            com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        } catch (e: java.net.BindException) {
            println("Port $port is already in use - kill any leftover processes and restart")
            librespotInitializing = false
            return
        }
        server.createContext("/login") { exchange ->
            val params = (exchange.requestURI.query ?: "").split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
            authCode = params["code"]
            val msg = if (authCode != null) "Success! You can close this window." else "Error: ${params["error"]}"
            exchange.sendResponseHeaders(200, msg.length.toLong())
            exchange.responseBody.write(msg.toByteArray())
            exchange.responseBody.close()
            synchronized(lock) { lock.notify() }
        }
        server.start()

        println("=".repeat(60))
        println("LIBRESPOT AUTH: Open this URL to enable track downloading:")
        println(authUrl)
        println("=".repeat(60))
        try { Runtime.getRuntime().exec(arrayOf("open", authUrl)) } catch (_: Exception) {}

        synchronized(lock) { lock.wait(120000) }
        server.stop(0)

        if (authCode == null) throw Exception("Librespot OAuth timed out")

        // Exchange code for token
        val tokenBody = "grant_type=authorization_code&code=$authCode" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&client_id=$librespotClientId&code_verifier=$verifier"
        val tokenJson = JSONObject(httpPost("https://accounts.spotify.com/api/token", tokenBody))
        val librespotToken = tokenJson.getString("access_token")

        // Build librespot credentials from the token
        val creds = com.spotify.Authentication.LoginCredentials.newBuilder()
            .setTyp(com.spotify.Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
            .setAuthData(com.google.protobuf.ByteString.copyFromUtf8(librespotToken))
            .build()

        try {
            session = Session.Builder(conf).credentials(creds).create()
            println("Librespot: session created and credentials saved")
        } finally {
            librespotInitializing = false
        }
    }

    private fun streamUri(id: String) {
        val uri = "spotify:track:$id"
        println("Loading track: $uri")

        val stream = session!!.contentFeeder().load(TrackId.fromUri(uri), VorbisOnlyAudioQuality(AudioQuality.HIGH), true, null)
        println("Stream loaded, starting ffmpeg...")

        val proc = Runtime.getRuntime().exec(arrayOf("ffmpeg", "-y", "-f", "ogg", "-i", "pipe:", "output.mp4"))

        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.`in`.stream().read(buffer).also { bytesRead = it } != -1) {
                proc.outputStream.write(buffer, 0, bytesRead)
            }
            stream.`in`.stream().close()
            proc.outputStream.flush()
            proc.outputStream.close()
        } catch (e: Exception) {
            proc.destroy()
            throw Exception("Stream error: ${e.message}", e)
        }

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val stderr = proc.errorStream.bufferedReader().readText()
            throw Exception("ffmpeg failed with exit code $exitCode: $stderr")
        }

        val outputFile = File("output.mp4")
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw Exception("ffmpeg produced no output file")
        }
        println("Download complete: ${outputFile.length()} bytes")
    }
}
