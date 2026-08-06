import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PlaylistIdRegistryTest {
    @Test
    fun roundTripsAlphanumericPlaylistIds() {
        val registry = PlaylistIdRegistry()
        val sourceId = "PLx123-AbC_xyz"

        val externalId = registry.encode(0, sourceId)

        assertEquals(SourcePlaylistReference(0, sourceId), registry.decode(externalId))
    }

    @Test
    fun returnsStableIdsForRepeatedRegistration() {
        val registry = PlaylistIdRegistry()

        assertEquals(registry.encode(2, "playlist-id"), registry.encode(2, "playlist-id"))
    }

    @Test
    fun keepsSourcesSeparateWithoutSingleDigitParsing() {
        val registry = PlaylistIdRegistry()

        val first = registry.encode(0, "same-id")
        val twelfth = registry.encode(11, "same-id")

        assertEquals(1, first)
        assertEquals(2, twelfth)
        assertNotEquals(first, twelfth)
        assertEquals(SourcePlaylistReference(11, "same-id"), registry.decode(twelfth))
    }

    @Test
    fun rejectsUnknownExternalIds() {
        assertNull(PlaylistIdRegistry().decode(1234))
    }
}
