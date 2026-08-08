import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackDurationFilterTest {
    @Test
    fun rejectsOnlyDurationsOverConfiguredLimit() {
        val filter = TrackDurationFilter.withMaximumMinutes(10)

        assertTrue(filter.allows(0))
        assertTrue(filter.allows(-1))
        assertTrue(filter.allows(600_000))
        assertFalse(filter.allows(600_001))
    }

    @Test
    fun zeroDisablesDurationLimit() {
        val filter = TrackDurationFilter.withMaximumMinutes(0)

        assertTrue(filter.allows(Long.MAX_VALUE))
    }

    @Test
    fun readsConfiguredLimit() {
        val properties = Properties().apply {
            setProperty("server.maxTrackDurationMinutes", "12")
        }
        val filter = TrackDurationFilter.from(properties)

        assertTrue(filter.allows(720_000))
        assertFalse(filter.allows(720_001))
    }

    @Test
    fun defaultsToTenMinutesForInvalidValue() {
        val properties = Properties().apply {
            setProperty("server.maxTrackDurationMinutes", "invalid")
        }
        val filter = TrackDurationFilter.from(properties)

        assertTrue(filter.allows(600_000))
        assertFalse(filter.allows(600_001))
    }
}
