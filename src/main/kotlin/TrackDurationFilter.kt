import beatport.api.Track
import java.util.Properties

internal class TrackDurationFilter private constructor(
    private val maximumDurationMs: Long?
) {
    fun filter(tracks: List<Track>): List<Track> = tracks.filter { track ->
        allows(track.length_ms)
    }

    internal fun allows(durationMs: Long): Boolean =
        durationMs <= 0 || maximumDurationMs == null || durationMs <= maximumDurationMs

    companion object {
        private const val PROPERTY = "server.maxTrackDurationMinutes"
        private const val DEFAULT_MINUTES = 10L
        private const val MILLIS_PER_MINUTE = 60_000L

        fun from(properties: Properties): TrackDurationFilter {
            val configured = properties
                .getProperty(PROPERTY, DEFAULT_MINUTES.toString())
                .trim()
                .toLongOrNull()
            val minutes = configured
                ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / MILLIS_PER_MINUTE }
                ?: DEFAULT_MINUTES.also {
                    System.err.println(
                        "Invalid $PROPERTY; using '$DEFAULT_MINUTES'"
                    )
                }
            return TrackDurationFilter(
                maximumDurationMs = minutes
                    .takeUnless { it == 0L }
                    ?.times(MILLIS_PER_MINUTE)
            )
        }

        internal fun withMaximumMinutes(minutes: Long): TrackDurationFilter =
            TrackDurationFilter(
                minutes.takeUnless { it == 0L }?.times(MILLIS_PER_MINUTE)
            )
    }
}
