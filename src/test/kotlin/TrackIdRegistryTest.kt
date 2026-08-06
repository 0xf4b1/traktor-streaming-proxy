import sources.TrackIdRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrackIdRegistryTest {
    @Test
    fun roundTripsUrlsWithTraktorSafeIds() {
        val registry = TrackIdRegistry()
        val url = "https://soundcloud.com/artist/track"

        val internalId = registry.encode(url)

        assertEquals("sc00000001", internalId)
        assertEquals(url, registry.decode(internalId))
    }

    @Test
    fun keepsIdsStableAndDistinct() {
        val registry = TrackIdRegistry()

        val first = registry.encode("https://soundcloud.com/artist/first")
        val repeated = registry.encode("https://soundcloud.com/artist/first")
        val second = registry.encode("https://soundcloud.com/artist/second")

        assertEquals(first, repeated)
        assertNotEquals(first, second)
    }
}
