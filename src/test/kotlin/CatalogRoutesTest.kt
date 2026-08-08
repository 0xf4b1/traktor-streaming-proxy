import beatport.api.Artist
import beatport.api.GenreTrackResponse
import beatport.api.Track
import beatport.api.Utils
import beatport.api.releaseWithArt
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogRoutesTest {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private fun cleanup(responses: List<beatport.api.TrackResponse>) {
        responses.forEach { clearTrackMapping(it.id) }
    }

    @Test
    fun catalogTracksById_returnsCachedTrackWithCoverProxyUrl() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val release = releaseWithArt(
            "Album",
            "https://cdn.example/route-art/400x400.jpg"
        )
        val responses = processTracks(
            0,
            listOf(Track("routecover1", listOf(Artist(1, "Artist")), "Route Track", 1234L, release))
        )
        try {
            val id = responses[0].id
            val response = client.get("/v4/catalog/tracks/?id=$id")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<GenreTrackResponse>()
            assertEquals(1, body.results.size)
            assertEquals(id, body.results[0].id)
            assertEquals("Route Track", body.results[0].name)
            val expected = coverProxyUrl(id)
            assertEquals(expected, body.results[0].image?.uri)
            assertEquals(expected, body.results[0].release?.image?.uri)
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun catalogTracksById_emptyQueryReturnsEmptyResults() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val response = client.get("/v4/catalog/tracks/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<GenreTrackResponse>()
        assertEquals(emptyList(), body.results)
    }

    @Test
    fun catalogTracksById_unknownIdReturnsEmptyResults() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val response = client.get("/v4/catalog/tracks/?id=999999999")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<GenreTrackResponse>()
        assertEquals(emptyList(), body.results)
    }

    @Test
    fun coverRoute_servesBytesAndContentType() = testApplication {
        application { module() }
        val previous = coverDownloader
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val release = releaseWithArt("Album", "https://cdn.example/route-cover/a.png")
        val responses = processTracks(
            0,
            listOf(Track("routecover2", listOf(Artist(1, "Artist")), "Cover Track", 1000L, release))
        )
        try {
            val id = responses[0].id
            val origin = getCoverOriginUrl(id)!!
            coverDownloader = { url ->
                assertEquals(origin, url)
                CoverDownload(bytes, ContentType.Image.PNG)
            }
            val response = client.get("/v4/catalog/tracks/$id/cover/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
            assertTrue(response.bodyAsBytes().contentEquals(bytes))
        } finally {
            coverDownloader = previous
            cleanup(responses)
        }
    }

    @Test
    fun coverRoute_usesCacheOnSecondRequest() = testApplication {
        application { module() }
        val previous = coverDownloader
        var downloads = 0
        val release = releaseWithArt("Album", "https://cdn.example/route-cover/cache.png")
        val responses = processTracks(
            0,
            listOf(Track("routecover3", listOf(Artist(1, "Artist")), "Cache Track", 1000L, release))
        )
        try {
            val id = responses[0].id
            coverDownloader = {
                downloads += 1
                CoverDownload(byteArrayOf(1, 2, 3), ContentType.Image.JPEG)
            }
            assertEquals(HttpStatusCode.OK, client.get("/v4/catalog/tracks/$id/cover/").status)
            assertEquals(HttpStatusCode.OK, client.get("/v4/catalog/tracks/$id/cover/").status)
            assertEquals(1, downloads)
        } finally {
            coverDownloader = previous
            cleanup(responses)
        }
    }

    @Test
    fun coverRoute_notFoundWithoutMapping() = testApplication {
        application { module() }
        val response = client.get("/v4/catalog/tracks/424242/cover/")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun catalogTracksById_hydratesAfterMappingRemoved() = testApplication {
        application { module() }
        val client = createClient {
            install(ContentNegotiation) { json(json) }
        }
        val nativeId = "routehydr1"
        val track = Track(
            nativeId,
            listOf(Artist(1, "Artist")),
            "Hydrate Route",
            1000L,
            releaseWithArt("Album", "https://cdn.example/route-cover/hydrate.jpg")
        )
        val fake = FakeLookupSource(mapOf(nativeId to track))
        sources.add(fake)
        val previousDownloader = coverDownloader
        try {
            val traktorId = Utils.encode(nativeId)
            clearTrackMapping(traktorId)
            coverDownloader = {
                CoverDownload(byteArrayOf(1, 2, 3, 4), ContentType.Image.JPEG)
            }
            val response = client.get("/v4/catalog/tracks/?id=$traktorId")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<GenreTrackResponse>()
            assertEquals(1, body.results.size)
            assertEquals(coverProxyUrl(traktorId), body.results[0].image?.uri)

            val cover = client.get("/v4/catalog/tracks/$traktorId/cover/")
            assertEquals(HttpStatusCode.OK, cover.status)
            assertTrue(cover.bodyAsBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        } finally {
            coverDownloader = previousDownloader
            clearTrackMapping(Utils.encode(nativeId))
            sources.remove(fake)
        }
    }

    @Test
    fun coverRoute_badGatewayWhenDownloadFails() = testApplication {
        application { module() }
        val previous = coverDownloader
        val release = releaseWithArt("Album", "https://cdn.example/route-cover/fail.jpg")
        val responses = processTracks(
            0,
            listOf(Track("routecover4", listOf(Artist(1, "Artist")), "Fail Track", 1000L, release))
        )
        try {
            val id = responses[0].id
            coverDownloader = { throw IOException("upstream down") }
            val response = client.get("/v4/catalog/tracks/$id/cover/")
            assertEquals(HttpStatusCode.BadGateway, response.status)
        } finally {
            coverDownloader = previous
            cleanup(responses)
        }
    }
}
