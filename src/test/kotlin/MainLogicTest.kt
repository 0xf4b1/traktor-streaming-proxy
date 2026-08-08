import beatport.api.Artist
import beatport.api.Image
import beatport.api.Track
import beatport.api.TrackResponse
import beatport.api.Utils
import beatport.api.releaseWithArt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sources.ISource
import sources.Youtube
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainLogicTest {

    private fun cleanup(responses: List<TrackResponse>) {
        responses.forEach { clearTrackMapping(it.id) }
    }

    private fun withFakeSource(source: ISource, block: (sourceIndex: Int) -> Unit) {
        sources.add(source)
        val index = sources.lastIndex
        try {
            block(index)
        } finally {
            sources.remove(source)
        }
    }

    @Test
    fun processTracks_cachesTrackResponse() {
        val track = Track("abcdefghij", listOf(Artist(1, "Artist")), "Name", 1234L)
        val responses = processTracks(0, listOf(track))
        try {
            assertEquals(1, responses.size)
            assertEquals(Utils.encode("abcdefghij"), responses[0].id)
            assertEquals("Name", responses[0].name)
            assertEquals(responses[0], getStoredTrack(responses[0].id))
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun processTracks_propagatesCoverArtToResponse() {
        val release = releaseWithArt(
            "Album",
            "https://cdn.example/art/400x400.jpg",
            "https://cdn.example/art/{w}x{h}.jpg"
        )
        val track = Track("covertrack1", listOf(Artist(1, "Artist")), "Name", 1234L, release)
        val responses = processTracks(0, listOf(track))
        try {
            assertEquals(1, responses.size)
            val id = responses[0].id
            val expected = coverProxyUrl(id)
            assertEquals(expected, responses[0].image?.uri)
            assertEquals(expected, responses[0].image?.dynamic_uri)
            assertEquals(expected, responses[0].release?.image?.uri)
            assertEquals("https://cdn.example/art/400x400.jpg", getCoverOriginUrl(id))
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun parseCatalogTrackIds_readsIdQueryUsedByTraktor() {
        assertEquals(
            listOf(42L, 99L),
            parseCatalogTrackIds(io.ktor.http.parametersOf("id" to listOf("42", "99")))
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            parseCatalogTrackIds(io.ktor.http.parametersOf("id" to listOf("1,2", "3")))
        )
        assertEquals(
            emptyList(),
            parseCatalogTrackIds(io.ktor.http.parametersOf())
        )
    }

    @Test
    fun isAllowedCoverOriginUrl_acceptsHttpsRemoteHosts() {
        assertTrue(isAllowedCoverOriginUrl("https://avatars.yandex.net/get-music-content/abc/400x400"))
        assertTrue(isAllowedCoverOriginUrl("https://i.ytimg.com/vi/x/hqdefault.jpg"))
        assertTrue(isAllowedCoverOriginUrl(" https://cdn.example/art.jpg "))
    }

    @Test
    fun isAllowedCoverOriginUrl_rejectsCleartextLocalAndPrivate() {
        assertTrue(!isAllowedCoverOriginUrl("http://cdn.example/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("https://localhost/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("https://127.0.0.1/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("https://192.168.0.5/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("https://10.0.0.2/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("https://user:pass@cdn.example/a.jpg"))
        assertTrue(!isAllowedCoverOriginUrl("not-a-url"))
    }

    @Test
    fun coverContentType_prefersUpstreamImageMime() {
        assertEquals(io.ktor.http.ContentType.Image.JPEG, coverContentType(null))
        assertEquals(io.ktor.http.ContentType.Image.JPEG, coverContentType("image/jpg"))
        assertEquals(io.ktor.http.ContentType.Image.PNG, coverContentType("image/png; charset=binary"))
        assertEquals(io.ktor.http.ContentType.parse("image/webp"), coverContentType("image/webp"))
        assertEquals(io.ktor.http.ContentType.Image.JPEG, coverContentType("text/html"))
    }

    @Test
    fun resolveCoverRedirectUrl_allowsHttpsRemoteAndResolvesRelative() {
        assertEquals(
            "https://cdn.example/b.jpg",
            resolveCoverRedirectUrl("https://cdn.example/a.jpg", "https://cdn.example/b.jpg")
        )
        assertEquals(
            "https://cdn.example/art/b.jpg",
            resolveCoverRedirectUrl("https://cdn.example/art/a.jpg", "b.jpg")
        )
        assertEquals(
            "https://cdn.example/other.jpg",
            resolveCoverRedirectUrl("https://cdn.example/art/a.jpg", "/other.jpg")
        )
    }

    @Test
    fun resolveCoverRedirectUrl_rejectsCleartextLocalAndPrivate() {
        assertNull(resolveCoverRedirectUrl("https://cdn.example/a.jpg", "http://cdn.example/b.jpg"))
        assertNull(resolveCoverRedirectUrl("https://cdn.example/a.jpg", "https://127.0.0.1/b.jpg"))
        assertNull(resolveCoverRedirectUrl("https://cdn.example/a.jpg", "https://192.168.0.5/b.jpg"))
        assertNull(resolveCoverRedirectUrl("https://cdn.example/a.jpg", "https://localhost/b.jpg"))
        assertNull(resolveCoverRedirectUrl("https://cdn.example/a.jpg", "//127.0.0.1/b.jpg"))
    }

    @Test
    fun processTracks_ignoresDisallowedCoverOrigin() {
        val release = releaseWithArt("Album", "http://cdn.example/art.jpg")
        val track = Track("badcover01", listOf(Artist(1, "Artist")), "Name", 1L, release)
        val responses = processTracks(0, listOf(track))
        try {
            assertEquals(1, responses.size)
            assertEquals(null, responses[0].image)
            assertEquals(null, responses[0].release?.image)
            assertEquals(null, getCoverOriginUrl(responses[0].id))
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun apiJson_encodesDefaultsButOmitsNullArtwork() {
        val withArt = TrackResponse(
            id = 42L,
            artists = listOf(Artist(1, "Artist")),
            name = "Named",
            length_ms = 1000L,
            release = releaseWithArt("Album", "https://cdn.example/a.jpg"),
            image = Image(id = 42L, uri = "https://api.beatport.com/cover", dynamic_uri = "https://api.beatport.com/cover")
        )
        val withArtJson = apiJson.encodeToString(TrackResponse.serializer(), withArt)
        val withArtObj = Json.parseToJsonElement(withArtJson).jsonObject
        assertEquals("Named", withArtObj["name"]?.jsonPrimitive?.content)
        assertEquals(0L, withArtObj["release"]!!.jsonObject["id"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            "https://cdn.example/a.jpg",
            withArtObj["release"]!!.jsonObject["image"]!!.jsonObject["uri"]!!.jsonPrimitive.content
        )
        assertEquals(
            "https://api.beatport.com/cover",
            withArtObj["image"]!!.jsonObject["uri"]!!.jsonPrimitive.content
        )

        val noArt = TrackResponse(
            id = 43L,
            artists = listOf(Artist(1, "Artist")),
            name = "Bare",
            length_ms = 1000L,
            release = beatport.api.Release(name = "Bare"),
            image = null
        )
        val noArtJson = apiJson.encodeToString(TrackResponse.serializer(), noArt)
        val noArtObj = Json.parseToJsonElement(noArtJson).jsonObject
        assertFalse(noArtObj.containsKey("image"))
        assertFalse(noArtObj["release"]!!.jsonObject.containsKey("image"))
        assertFalse(noArtObj["release"]!!.jsonObject.containsKey("label"))
        assertEquals("Bare", noArtObj["release"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun ensureCatalogTrack_hydratesMissingMappingWithCover() {
        val nativeId = "hydrate01"
        val withArt = Track(
            nativeId,
            listOf(Artist(1, "Artist")),
            "Hydrated",
            1000L,
            releaseWithArt("Album", "https://cdn.example/hydrate/art.jpg")
        )
        withFakeSource(FakeLookupSource(mapOf(nativeId to withArt))) { sourceIndex ->
            val traktorId = Utils.encode(nativeId)
            clearTrackMapping(traktorId)
            val ensured = ensureCatalogTrack(traktorId)
            try {
                assertEquals(traktorId, ensured!!.id)
                assertEquals(coverProxyUrl(traktorId), ensured.image?.uri)
                assertEquals(
                    "https://cdn.example/hydrate/art.jpg",
                    getCoverOriginUrl(traktorId)
                )
                assertEquals(sourceIndex, trackIdToSource[nativeId])
            } finally {
                clearTrackMapping(traktorId)
            }
        }
    }

    @Test
    fun ensureCatalogTrack_backfillsCoverForMappedTrackWithoutArt() {
        val nativeId = "hydrate02"
        val bare = Track(nativeId, listOf(Artist(1, "Artist")), "Bare", 1000L)
        val withArt = Track(
            nativeId,
            listOf(Artist(1, "Artist")),
            "Bare",
            1000L,
            releaseWithArt("Album", "https://cdn.example/hydrate/backfill.jpg")
        )
        withFakeSource(FakeLookupSource(mapOf(nativeId to withArt))) { sourceIndex ->
            val responses = processTracks(sourceIndex, listOf(bare))
            try {
                val id = responses[0].id
                assertEquals(null, getCoverOriginUrl(id))
                val ensured = ensureCatalogTrack(id)
                assertEquals(coverProxyUrl(id), ensured!!.image?.uri)
                assertEquals(
                    "https://cdn.example/hydrate/backfill.jpg",
                    getCoverOriginUrl(id)
                )
            } finally {
                cleanup(responses)
            }
        }
    }

    @Test
    fun mergeCatalogLookup_preservesTitleAndDurationOnPlaceholderLookup() {
        val existing = TrackResponse(
            id = 1L,
            artists = listOf(Artist(1, "Real Artist")),
            name = "Real Title",
            length_ms = 240_000L,
            release = beatport.api.Release(name = "Real Title")
        )
        val lookedUp = Track(
            "dQw4w9WgXcQ",
            listOf(Artist(1, "YouTube")),
            "dQw4w9WgXcQ",
            0L,
            releaseWithArt("dQw4w9WgXcQ", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
        )
        val merged = mergeCatalogLookup(existing, lookedUp)
        assertEquals("Real Title", merged.name)
        assertEquals(240_000L, merged.length_ms)
        assertEquals("Real Artist", merged.artists.single().name)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", merged.release.image?.uri)
        assertEquals("dQw4w9WgXcQ", merged.id)
    }

    @Test
    fun ensureCatalogTrack_doesNotClobberMetaWhenLookupIsPlaceholder() {
        val nativeId = "hydrate03"
        val bare = Track(nativeId, listOf(Artist(1, "Keeper")), "Keep Name", 12_345L)
        val placeholder = Track(
            nativeId,
            listOf(Artist(1, "YouTube")),
            nativeId,
            0L,
            releaseWithArt(nativeId, "https://cdn.example/hydrate/keep.jpg")
        )
        withFakeSource(FakeLookupSource(mapOf(nativeId to placeholder))) { sourceIndex ->
            val responses = processTracks(sourceIndex, listOf(bare))
            try {
                val id = responses[0].id
                val ensured = ensureCatalogTrack(id)!!
                assertEquals("Keep Name", ensured.name)
                assertEquals(12_345L, ensured.length_ms)
                assertEquals("Keeper", ensured.artists.single().name)
                assertEquals(coverProxyUrl(id), ensured.image?.uri)
            } finally {
                cleanup(responses)
            }
        }
    }

    @Test
    fun ensureCatalogTrack_doesNotMisattributeSpotifyPrefixToYouTubePolicy() {
        val youtubePolicySource = object : ISource by FakeLookupSource(emptyMap()) {
            override val name: String = "YouTube"
            override fun lookupTrack(id: String): Track? {
                val videoId = Youtube.resolveVideoIdFromPrefix(id, allowNetworkExpand = false)
                    ?: return null
                return Track(
                    videoId,
                    listOf(Artist(1, "YouTube")),
                    videoId,
                    0L,
                    releaseWithArt(videoId, Youtube.thumbnailUrl(videoId))
                )
            }
        }
        Youtube.prefixCachePersistenceEnabled = false
        Youtube.clearPrefixCacheForTests()
        Youtube.thumbnailProbe = { id -> id.startsWith("4uLU6hMCjM") }
        withFakeSource(youtubePolicySource) {
            val spotifyPrefix = "4uLU6hMCjM"
            val traktorId = Utils.encode(spotifyPrefix)
            clearTrackMapping(traktorId)
            try {
                assertNull(ensureCatalogTrack(traktorId))
                assertNull(getCoverOriginUrl(traktorId))
            } finally {
                clearTrackMapping(traktorId)
                Youtube.thumbnailProbe = Youtube::probeThumbnailExists
                Youtube.clearPrefixCacheForTests()
            }
        }
    }

    @Test
    fun coverCache_putAndGet() {
        val cache = CoverCache(maxSize = 2)
        val jpeg = io.ktor.http.ContentType.Image.JPEG
        cache.put("https://cdn.example/a.jpg", CoverDownload(byteArrayOf(1, 2), jpeg))
        cache.put("https://cdn.example/b.jpg", CoverDownload(byteArrayOf(3, 4), jpeg))
        assertTrue(cache.get("https://cdn.example/a.jpg")!!.bytes.contentEquals(byteArrayOf(1, 2)))
        assertEquals(jpeg, cache.get("https://cdn.example/b.jpg")!!.contentType)
        assertEquals(null, cache.get("https://cdn.example/missing.jpg"))
    }

    @Test
    fun coverCache_evictsEldest() {
        val cache = CoverCache(maxSize = 2)
        val jpeg = io.ktor.http.ContentType.Image.JPEG
        cache.put("https://cdn.example/1.jpg", CoverDownload(byteArrayOf(1), jpeg))
        cache.put("https://cdn.example/2.jpg", CoverDownload(byteArrayOf(2), jpeg))
        cache.put("https://cdn.example/3.jpg", CoverDownload(byteArrayOf(3), jpeg))
        assertEquals(2, cache.size())
        assertEquals(null, cache.get("https://cdn.example/1.jpg"))
        assertTrue(cache.get("https://cdn.example/2.jpg")!!.bytes.contentEquals(byteArrayOf(2)))
        assertTrue(cache.get("https://cdn.example/3.jpg")!!.bytes.contentEquals(byteArrayOf(3)))
    }

    @Test
    fun coverCache_getReturnsDefensiveCopy() {
        val cache = CoverCache(maxSize = 2)
        val original = byteArrayOf(9, 8, 7)
        cache.put("https://cdn.example/x.jpg", CoverDownload(original, io.ktor.http.ContentType.Image.JPEG))
        original[0] = 0
        assertEquals(9, cache.get("https://cdn.example/x.jpg")!!.bytes[0])
        val got = cache.get("https://cdn.example/x.jpg")!!.bytes
        got[0] = 1
        assertEquals(9, cache.get("https://cdn.example/x.jpg")!!.bytes[0])
    }

    @Test
    fun coverCache_evictsByTotalByteSize() {
        val cache = CoverCache(maxSize = 5, maxBytes = 3)
        val jpeg = io.ktor.http.ContentType.Image.JPEG
        cache.put("https://cdn.example/1.jpg", CoverDownload(byteArrayOf(1, 2), jpeg))
        cache.put("https://cdn.example/2.jpg", CoverDownload(byteArrayOf(3, 4), jpeg))

        assertEquals(null, cache.get("https://cdn.example/1.jpg"))
        assertTrue(cache.get("https://cdn.example/2.jpg")!!.bytes.contentEquals(byteArrayOf(3, 4)))
        assertEquals(2L, cache.byteSize())
    }
}
