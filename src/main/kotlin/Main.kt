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
import sources.SoundCloud
import sources.Spotify
import sources.SourcePlaylist
import sources.Tidal
import sources.Youtube
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList

val sources: ArrayList<ISource> = ArrayList()
val playlistIdRegistry = PlaylistIdRegistry()
lateinit var trackReferenceRegistry: TrackReferenceRegistry
internal lateinit var trackDurationFilter: TrackDurationFilter

val allSources = mapOf(
    "youtube" to Youtube::class.java,
    "soundcloud" to SoundCloud::class.java,
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
    val sourceName = sources[id].name
    val results = trackDurationFilter.filter(tracks).map { track ->
        val traktorId = trackReferenceRegistry.encode(sourceName, track.id, track.length_ms)
        TrackResponse(traktorId, track.artists, track.name, track.length_ms)
    }
    trackReferenceRegistry.flush()
    return results
}

private fun List<SourcePlaylist>.toApiPlaylists(sourceIndex: Int): List<Playlist> = map { playlist ->
    Playlist(playlistIdRegistry.encode(sourceIndex, playlist.id), playlist.name)
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
    val audioDownloadCache = AudioDownloadCache.from(prop)
    trackDurationFilter = TrackDurationFilter.from(prop)
    trackReferenceRegistry = TrackReferenceRegistry(
        Path.of(prop.getProperty("server.trackRegistryFile", "state/track-ids.properties"))
    )
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            audioDownloadCache.close()
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
                val genreId = call.parameters["genre_id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing genre_id")
                val sourceIndex = genreId.toIntOrNull()?.minus(1)
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid genre_id")
                val source = sources.getOrNull(sourceIndex)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source")

                val results = source
                    .getCuratedPlaylists(!call.parameters.contains("more"))
                    .toApiPlaylists(sourceIndex)
                call.respond(
                    CuratedPlaylistsResponse(
                        results,
                        if (results.isNotEmpty()) {
                            "api.beatport.com/v4/curation/playlists/?genre_id=$genreId&more"
                        } else {
                            ""
                        }
                    )
                )
            }

            get("/v4/curation/playlists/{id}/tracks/") {
                val externalId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid playlist ID")
                val reference = playlistIdRegistry.decode(externalId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown playlist")
                val source = sources.getOrNull(reference.sourceIndex)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source")

                val results = processTracks(
                    reference.sourceIndex,
                    source.getCuratedPlaylist(reference.sourcePlaylistId)
                )
                call.respond(
                    CuratedPlaylistResponse(
                        results.map { track -> PlaylistItem(track) },
                        "" /* unused by Traktor */
                    )
                )
            }

            get("/v4/my/playlists/") {
                val results = sources.flatMapIndexed { sourceIndex, source ->
                    source.getPlaylists().toApiPlaylists(sourceIndex)
                }
                call.respond(CuratedPlaylistsResponse(results, "" /* not needed */))
            }

            get("/v4/my/playlists/{id}/tracks/") {
                val externalId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid playlist ID")
                val reference = playlistIdRegistry.decode(externalId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown playlist")
                val source = sources.getOrNull(reference.sourceIndex)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source")

                val results = processTracks(
                    reference.sourceIndex,
                    source.getPlaylist(reference.sourcePlaylistId)
                )
                call.respond(
                    CuratedPlaylistResponse(
                        results.map { track -> PlaylistItem(track) },
                        "" /* unused by Traktor */
                    )
                )
            }

            get("/v4/catalog/genres/{id}/top/100/") {
                call.parameters["id"]?.let {
                    call.respond(GenreTrackResponse(processTracks(it.toInt() - 1, sources[it.toInt() - 1].getTop100()), "" /* unused by Traktor */))
                }
            }

            audioDownloadRoutes(
                sources,
                trackReferenceRegistry,
                audioDownloadCache,
                trackDurationFilter
            )
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
