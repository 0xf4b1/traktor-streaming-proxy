import beatport.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import kotlinx.serialization.json.*
import org.apache.log4j.BasicConfigurator
import sources.ISource
import sources.Spotify
import sources.Tidal
import sources.Youtube
import java.io.File
import kotlin.math.min

val sources: ArrayList<ISource> = ArrayList()
val trackIdToSource: HashMap<String, Int> = HashMap()
val traktorIdToTrackId: HashMap<Long, String> = HashMap()

fun register(source: ISource) {
    sources.add(source)
}

fun processTracks(id: Int, tracks: List<Track>): List<TrackResponse> {
    return tracks.map { track ->
        trackIdToSource[track.id] = id
        val traktorId = Utils.encode(track.id.substring(0, min(track.id.length, 10)))
        if (!traktorIdToTrackId.containsKey(traktorId)) {
            traktorIdToTrackId[traktorId] = if (track.id.length > 10) track.id.substring(10) else ""
        }
        TrackResponse(traktorId, track.artists, track.name, track.length_ms)
    }
}

fun main() {
    BasicConfigurator.configure()

    register(Youtube())
    register(Spotify())
    register(Tidal())

    embeddedServer(Netty, port = 8000) {
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
                call.respond(Account(System.getenv("BEATPORT_ACCOUNT_ID").toInt()))
            }

            get("/v4/my/license/") {
                call.respondBytes(
                    File("license").inputStream().readBytes()
                )
            }

            get("/v4/catalog/search") {
                call.parameters["q"]?.let {
                    val results = sources.mapIndexed { id, source -> processTracks(id, source.query(it, !call.parameters.contains("more"))) }.flatten()
                    call.respond(QueryTrackResponse(results, if (results.isNotEmpty()) "api.beatport.com/v4/catalog/search?q=$it&more" else ""))
                }
            }

            get("/v4/catalog/genres") {
                call.respondRedirect("/v4/catalog/genres/")
            }

            get("/v4/catalog/genres/") {
                call.respond(Genres(sources.mapIndexed { id, source -> Genre(id + 1, source.name) }))
            }

            get("/v4/catalog/genres/{id}/tracks/") {
                call.parameters["id"]?.let {
                    call.respond(GenreTrackResponse(processTracks(it.toInt() - 1, sources[it.toInt() - 1].getGenre()), ""))
                }
            }

            get("/v4/curation/playlists/") {
                call.parameters["genre_id"]?.let {
                    call.respond(CuratedPlaylistsResponse(sources[it.toInt() - 1].getPlaylists().mapIndexed { id, name ->
                        Playlist((it + id).toLong(), name) }, ""))
                }
            }

            get("/v4/curation/playlists/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.substring(0,1).toInt() - 1
                    val results = processTracks(sourceId, sources[sourceId].getPlaylist(it.substring(1).toInt()))
                    call.respond(CuratedPlaylistResponse(results.map { track -> PlaylistItem(track) }, ""))
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
    }.start(wait = true)
}