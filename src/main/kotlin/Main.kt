import Config.prop
import beatport.api.*
import io.ktor.http.*
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import kotlinx.serialization.json.*
import org.apache.log4j.BasicConfigurator
import sources.ISource
import sources.Spotify
import sources.Tidal
import sources.Youtube
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.min

val sources: ArrayList<ISource> = ArrayList()
val trackIdToSource: HashMap<String, Int> = HashMap()
val traktorIdToTrackId: HashMap<Long, String> = HashMap()

val allSources = mapOf(
    "youtube" to Youtube::class.java,
    "spotify" to Spotify::class.java,
    "tidal" to Tidal::class.java
)

object Config {
    val prop = Properties()

    fun readConfig() {
        val file = File("config.properties")
        if (!file.exists())
            return
        FileInputStream(file).use { prop.load(it) }
    }

    fun saveConfig() {
        val file = File("config.properties")
        FileOutputStream(file).use {
            prop.store(it, "")
        }
    }
}

fun register(source: Class<out ISource>) {
    try {
        sources.add(source.getConstructor().newInstance())
    } catch (ex: Exception) {
        println("Can not instantiate $source: ${ex.printStackTrace()}")
    }
}

fun processTracks(id: Int, tracks: List<Track>): List<TrackResponse> {
    return tracks.map { track ->
        trackIdToSource[track.id] = id
        val traktorId = Utils.encode(track.id.substring(0, min(track.id.length, 10)))
        if (!traktorIdToTrackId.containsKey(traktorId)) {
            traktorIdToTrackId[traktorId] = if (track.id.length > 10) track.id.substring(10) else ""
        }
        TrackResponse(traktorId, track.artists, track.name, track.length_ms, track.release)
    }
}

/**
 * Executes a search across enabled sources.
 *
 * @param q The raw query string from the request.
 * @param hasMoreParameter A flag indicating if the 'more' parameter is present.
 * @return A list of processed track responses.
 */
private fun executeSearch(q: String, hasMoreParameter: Boolean): List<TrackResponse> {
    var query = q
    val enabledSources = if (q.contains(":")) {
        val (sourceName, actualQuery) = q.split(":", limit = 2)
        query = actualQuery
        listOf(allSources[sourceName])
    } else {
        prop.getProperty("search.enabled", "").split(",").map { name -> allSources[name] }
    }

    return sources.mapIndexed { id, source ->
        if (enabledSources.contains(null) || source::class.java in enabledSources) {
            processTracks(id, source.query(query, !hasMoreParameter))
        } else {
            emptyList()
        }
    }.flatten()
}

fun main() {
    BasicConfigurator.configure()

    Config.readConfig()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            Config.saveConfig()
        }
    })

    prop.getProperty("sources.enabled", "").split(",").map { name -> allSources[name] }.forEach {
        if (it != null)
            register(it)
    }

    val alias = "foo"
    var serverConfiguration: NettyApplicationEngine.Configuration.() -> Unit

    if(prop.getProperty("server.useKeystore", "false").toBoolean()) {
        val keystorePassword = "changeit"

        val keyStore = KeyStore.getInstance("JKS").apply {
            File("cert/keystore.jks").inputStream().use {
                load(it, keystorePassword.toCharArray())
            }
        }
        serverConfiguration = {
            sslConnector(
                keyStore,
                alias,
                { keystorePassword.toCharArray() },
                { keystorePassword.toCharArray() }
            ) {
                port = 8443
            }
        }
    } else {
        serverConfiguration = {
            sslConnector(buildKeyStore {
                certificate(alias) {
                    password = alias
                    domains = listOf("api.beatport.com")
                }
            }, alias, { "".toCharArray() }, { alias.toCharArray() }) {
                port = 8443
            }
        }
    }

    embeddedServer(Netty, applicationEnvironment(), serverConfiguration, module = {
        install(CallLogging)
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        var data = ByteArray(0)
        routing {

            get("/v4/auth/o/authorize/") {
                call.respondRedirect("traktor://bp_oauth?code=foo")
            }

            post("/v4/auth/o/token/") {
                call.respond(Auth("foo", 36000, "Bearer", "app:locker user:dj", "bar"))
            }

            get("/v4/auth/logout/") {
                call.respond(HttpStatusCode.OK)
            }

            get("/v4/my/account/") {
                call.respond(Account(prop.getProperty("beatport.accountId").toInt()))
            }

            get("/v4/my/license/") {
                val licenseName = prop.getProperty("beatport.license", "macos")
                val licenseFile = Config::class.java.getResource("licenses/${licenseName}.json")

                if (licenseFile == null) {
                    call.respond(HttpStatusCode.InternalServerError, "License file '${licenseName}' not found")
                } else {
                    call.respondBytes(licenseFile.readBytes())
                }
            }

            get("/v4/catalog/search") {
                val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val results = executeSearch(q, call.parameters.contains("more"))
                val nextUrl = if (results.isNotEmpty()) "api.beatport.com/v4/catalog/search?q=$q&more" else ""
                call.respond(QueryTrackResponse(results, nextUrl))
            }

            get("/search/v1/tracks") {
                val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val results = executeSearch(q, call.parameters.contains("more"))
                call.respond(BeatportSearchResponse(results.toNewSearchApi()))
            }

            get("/v4/catalog/genres") {
                call.respondRedirect("/v4/catalog/genres/")
            }

            get("/v4/catalog/genres/") {
                call.respond(Genres(sources.mapIndexed { id, source -> Genre(id + 1, source.name) }))
            }

            get("/v4/catalog/genres/{id}/tracks/") {
                call.parameters["id"]?.let {
                    call.respond(GenreTrackResponse(processTracks(it.toInt() - 1, sources[it.toInt() - 1].getGenre()), "" /* unused by Traktor */))
                }
            }

            get("/v4/curation/playlists/") {
                call.parameters["genre_id"]?.let {
                    val results = sources[it.toInt() - 1].getCuratedPlaylists(!call.parameters.contains("more")).map { playlist ->
                        Playlist((it + playlist.id).toLong(), playlist.name)
                    }
                    call.respond(CuratedPlaylistsResponse(results, if (results.isNotEmpty()) "api.beatport.com/v4/curation/playlists/?genre_id=$it&more" else ""))
                }
            }

            get("/v4/curation/playlists/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.substring(0, 1).toInt() - 1
                    val results = processTracks(sourceId, sources[sourceId].getCuratedPlaylist(it.substring(1)))
                    call.respond(CuratedPlaylistResponse(results.map { track -> PlaylistItem(track) }, "" /* unused by Traktor */))
                }
            }

            get("/v4/my/playlists/") {
                call.respond(CuratedPlaylistsResponse(sources.mapIndexed { id, source -> source.getPlaylists().map { playlist -> Playlist("${id + 1}${playlist.id}".toLong(), playlist.name) } }.flatten(), "" /* not needed */))
            }

            get("/v4/my/playlists/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.substring(0, 1).toInt() - 1
                    val results = processTracks(sourceId, sources[sourceId].getPlaylist(it.substring(1)))
                    call.respond(CuratedPlaylistResponse(results.map { track -> PlaylistItem(track) }, "" /* unused by Traktor */))
                }
            }

            get("/v4/catalog/genres/{id}/top/100/") {
                call.parameters["id"]?.let {
                    call.respond(GenreTrackResponse(processTracks(it.toInt() - 1, sources[it.toInt() - 1].getTop100()), "" /* unused by Traktor */))
                }
            }

            get("/v4/catalog/tracks/{id}/download/") {
                call.parameters["id"]?.let {
                    val trackId = Utils.decode(it.toLong()) + traktorIdToTrackId[it.toLong()]!!
                    data = sources[trackIdToSource[trackId]!!].download(trackId)
                    call.respond(Download("https://api.beatport.com/output.mp4", "foo", 1337))
                }
            }

            // Serve the last downloaded track
            head("/output.mp4") {
                call.response.header("content-type", "video/mp4")
                call.respondBytes(data)
            }

            get("/output.mp4") {
                call.response.header("content-type", "video/mp4")
                call.respondBytes(data)
            }
        }
    }).start(wait = true)
}

private fun List<TrackResponse>.toNewSearchApi(): List<BeatportTrack> {
    return this.map {
        BeatportTrack(
            artists = it.artists.map {
                BeatportArtist(
                    it.name,
                    "Artist" // Required by traktor
                )
            },
            track_name = it.name,
            track_id = it.id,
            length = it.length_ms
        )
    }
}
