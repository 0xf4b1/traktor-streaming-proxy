import beatport.api.Download
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sources.ISource
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

fun Route.audioDownloadRoutes(
    sources: List<ISource>,
    trackRegistry: TrackReferenceRegistry,
    cache: AudioDownloadCache
) {
    get("/v4/catalog/tracks/{id}/download/") {
        val externalId = call.parameters["id"]?.toLongOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid track ID")
        val reference = trackRegistry.decode(externalId)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown track")
        val source = sources.firstOrNull { it.name == reference.sourceName }
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown source")

        cache.prepare(source.cacheKey(reference.sourceTrackId)) {
            source.download(reference.sourceTrackId)
        }
        call.respond(
            Download(
                "https://api.beatport.com/output/$externalId/audio.mp4",
                "foo",
                reference.lengthMs.toInt()
            )
        )
    }

    head("/output/{id}/audio.mp4") {
        val externalId = call.parameters["id"]?.toLongOrNull()
            ?: return@head call.respond(HttpStatusCode.BadRequest)
        val prepared = prepare(externalId, sources, trackRegistry, cache)
            ?: return@head call.respond(HttpStatusCode.NotFound)
        call.respondFile(prepared.toFile())
    }

    get("/output/{id}/audio.mp4") {
        val externalId = call.parameters["id"]?.toLongOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid track ID")
        val prepared = prepare(externalId, sources, trackRegistry, cache)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Unknown track or source")
        call.respondFile(prepared.toFile())
    }
}

private suspend fun prepare(
    externalId: Long,
    sources: List<ISource>,
    trackRegistry: TrackReferenceRegistry,
    cache: AudioDownloadCache
): Path? {
    val reference = trackRegistry.decode(externalId) ?: return null
    val source = sources.firstOrNull { it.name == reference.sourceName } ?: return null
    return cache.prepare(source.cacheKey(reference.sourceTrackId)) {
        source.download(reference.sourceTrackId)
    }.await()
}

private fun ISource.cacheKey(trackId: String): String =
    "$name:${downloadCacheKey(trackId)}"

private suspend fun CompletableFuture<Path>.await(): Path = withContext(Dispatchers.IO) {
    get()
}
