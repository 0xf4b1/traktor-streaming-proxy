import beatport.api.Artist
import beatport.api.Track
import beatport.api.Utils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MainLogicTest {

    private fun cleanup(responses: List<beatport.api.TrackResponse>) {
        responses.forEach { response ->
            trackMappings.remove(response.id)
        }
    }

    @Test
    fun processTracks_cachesTrackResponse() {
        val track = Track("abcdefghij", listOf(Artist(1, "Artist")), "Name", 1234L)
        val responses = processTracks(0, listOf(track))
        try {
            assertEquals(1, responses.size)
            val cached = trackMappings.getTrack(responses[0].id)
            assertEquals(responses[0], cached)
            assertEquals("Name", cached!!.name)
            assertEquals(0, trackMappings.getSource(responses[0].id))
            assertEquals(track.id, trackMappings.getTrackId(responses[0].id))
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun processTracks_storesFullTrackIdBeyondTenChars() {
        val track = Track("abcdefghijEXTRA", listOf(Artist(1, "A")), "Long", 1L)
        val responses = processTracks(1, listOf(track))
        try {
            assertEquals(1, responses.size)
            assertEquals(track.id, trackMappings.getTrackId(responses[0].id))
            assertEquals(1, trackMappings.getSource(responses[0].id))
            // Preferred path still encodes the first 10 chars when free
            assertEquals(Utils.encode("abcdefghij"), responses[0].id)
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun processTracks_avoidsPrefixCollisions() {
        val a = Track("12345678901", listOf(Artist(1, "A")), "One", 1L)
        val b = Track("12345678902", listOf(Artist(1, "B")), "Two", 2L)
        val responses = processTracks(0, listOf(a, b))
        try {
            assertEquals(2, responses.size)
            assertNotEquals(responses[0].id, responses[1].id)
            assertEquals(a.id, trackMappings.getTrackId(responses[0].id))
            assertEquals(b.id, trackMappings.getTrackId(responses[1].id))
            // First keeps preferred prefix encoding; second must use hash fallback
            assertEquals(Utils.encode("1234567890"), responses[0].id)
            assertNotEquals(Utils.encode("1234567890"), responses[1].id)
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun allocateTraktorId_reusesMappingForSameFullId() {
        val id = "12345678901"
        val first = allocateTraktorId(2, id)
        val second = allocateTraktorId(2, id)
        try {
            assertEquals(first, second)
            assertEquals(id, trackMappings.getTrackId(first))
            assertEquals(2, trackMappings.getSource(first))
        } finally {
            trackMappings.remove(first)
        }
    }

    @Test
    fun processTracks_namespacesSameRawIdBySource() {
        val firstTrack = Track("1234567890", listOf(Artist(1, "A")), "One", 1L)
        val secondTrack = Track("1234567890", listOf(Artist(1, "B")), "Two", 2L)
        val first = processTracks(0, listOf(firstTrack))
        val second = processTracks(1, listOf(secondTrack))
        val responses = first + second
        try {
            assertNotEquals(first.single().id, second.single().id)
            assertEquals(0, trackMappings.getSource(first.single().id))
            assertEquals(1, trackMappings.getSource(second.single().id))
            assertEquals(firstTrack.id, trackMappings.getTrackId(first.single().id))
            assertEquals(secondTrack.id, trackMappings.getTrackId(second.single().id))
        } finally {
            cleanup(responses)
        }
    }

    @Test
    fun trackMappings_evictsEldestWhenOverCapacity() {
        val mappings = TrackMappings(maxSize = 2)
        val first = mappings.register(0, Track("aaaaaaaaaa", listOf(Artist(1, "A")), "One", 1L))
        val second = mappings.register(0, Track("bbbbbbbbbb", listOf(Artist(1, "B")), "Two", 2L))
        val third = mappings.register(0, Track("cccccccccc", listOf(Artist(1, "C")), "Three", 3L))

        assertEquals(2, mappings.size())
        assertNull(mappings.getTrackId(first.id))
        assertNull(mappings.getTrack(first.id))
        assertEquals("bbbbbbbbbb", mappings.getTrackId(second.id))
        assertEquals("cccccccccc", mappings.getTrackId(third.id))
        assertEquals(second, mappings.getTrack(second.id))
        assertEquals(third, mappings.getTrack(third.id))
    }

    @Test
    fun trackMappings_accessRefreshesLruOrder() {
        val mappings = TrackMappings(maxSize = 2)
        val first = mappings.register(0, Track("aaaaaaaaaa", listOf(Artist(1, "A")), "One", 1L))
        val second = mappings.register(0, Track("bbbbbbbbbb", listOf(Artist(1, "B")), "Two", 2L))
        assertEquals(first, mappings.getTrack(first.id))

        val third = mappings.register(0, Track("cccccccccc", listOf(Artist(1, "C")), "Three", 3L))
        assertEquals(2, mappings.size())
        assertEquals("aaaaaaaaaa", mappings.getTrackId(first.id))
        assertNull(mappings.getTrackId(second.id))
        assertEquals("cccccccccc", mappings.getTrackId(third.id))
    }

    @Test
    fun hashToEncodableKey_isDeterministicAndEncodable() {
        val key = hashToEncodableKey("12345678902")
        assertEquals(10, key.length)
        assertEquals(key, hashToEncodableKey("12345678902"))
        assertNotEquals(key, hashToEncodableKey("12345678902", salt = 1))
        assertEquals(key, Utils.decode(Utils.encode(key)))
    }
}
